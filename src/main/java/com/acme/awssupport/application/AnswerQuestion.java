package com.acme.awssupport.application;

import com.acme.awssupport.bootstrap.RagProperties;
import com.acme.awssupport.domain.*;
import com.acme.awssupport.domain.Types.*;
import com.acme.awssupport.ports.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.*;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.stereotype.Service;

/**
 * Coordinates grounded question answering, including caches, retrieval, and two evidence checks.
 *
 * <p>On an uncached successful path, Nomic embeds the query, the repository retrieves passages, and
 * Qwen selects then verifies evidence. Answers contain stored excerpts and server-built citations;
 * the model never supplies unrestricted answer prose. Missing or rejected evidence causes
 * abstention.
 *
 * <p>Caches and request coalescing are process-local. Corpus/model identity and policy epochs scope
 * reuse, and source validity is checked again before returning either a computed or cached answer.
 */
@Service
public class AnswerQuestion {
  /** A validated result plus source IDs needed for later revocation checks. */
  private record Cached(Answer answer, List<String> sources, boolean semanticUsed) {}

  /**
   * Similar-query cache entry containing candidate IDs, never an answer to a different question.
   */
  private record Candidates(String scope, float[] vector, List<String> ids) {}

  // Weight limits approximate heap use; TTLs never replace source-revocation checks.
  private final Cache<String, Cached> answers =
      Caffeine.newBuilder()
          .maximumWeight(64 * 1024 * 1024)
          .weigher(
              (String key, Cached value) ->
                  key.length() * 2
                      + value.answer().claims().stream()
                          .mapToInt(c -> c.text().length() * 4 + 512)
                          .sum()
                      + 2048)
          .expireAfterWrite(Duration.ofMinutes(30))
          .build();
  private final Cache<String, float[]> embeddings =
      Caffeine.newBuilder()
          .maximumWeight(16 * 1024 * 1024)
          .weigher((String key, float[] value) -> key.length() * 2 + value.length * 4 + 128)
          .expireAfterWrite(Duration.ofHours(24))
          .build();
  private final Cache<String, List<String>> retrieval =
      Caffeine.newBuilder()
          .maximumWeight(16 * 1024 * 1024)
          .weigher((String key, List<String> value) -> key.length() * 2 + value.size() * 200 + 128)
          .expireAfterWrite(Duration.ofMinutes(15))
          .build();
  private final Cache<String, Candidates> semantic =
      Caffeine.newBuilder()
          .maximumWeight(32 * 1024 * 1024)
          .weigher(
              (String key, Candidates value) ->
                  key.length() * 2
                      + value.scope().length() * 2
                      + value.vector().length * 4
                      + value.ids().size() * 200
                      + 256)
          .expireAfterWrite(Duration.ofMinutes(10))
          .build();
  private final ConcurrentHashMap<String, CompletableFuture<Cached>> inFlight =
      new ConcurrentHashMap<>();
  private final Semaphore admitted = new Semaphore(5, true);
  private final CorpusRepository repository;
  private final LocalModel model;
  private final TokenCounter tokens;
  private final RagProperties properties;
  private final ObjectMapper json;
  private final MeterRegistry metrics;

  public AnswerQuestion(
      CorpusRepository repository,
      LocalModel model,
      TokenCounter tokens,
      RagProperties properties,
      ObjectMapper json,
      MeterRegistry metrics) {
    this.repository = repository;
    this.model = model;
    this.tokens = tokens;
    this.properties = properties;
    this.json = json;
    this.metrics = metrics;
  }

  /**
   * Answers within a shared deadline, with bounded admission and identical-request coalescing.
   * Exact cache hits still validate source state; missing evidence returns an abstention.
   */
  public ChatResponse answer(Question question) {
    long start = System.nanoTime();
    Deadline deadline = new Deadline(properties.requestTimeout());
    if (!admitted.tryAcquire()) {
      metrics.counter("rag.rejected").increment();
      throw new SupportException("BUSY", 429, "The local model is busy. Please try again shortly.");
    }
    try {
      Generation generation = repository.active();
      ModelProfile profile = model.profile();
      if (!generation.profile().equals(profile.embeddingProfile()))
        throw new SupportException(
            "INDEX_PROFILE_MISMATCH",
            503,
            "Rebuild the corpus with the installed embedding profile.");
      String key = key(question, generation, profile.answerProfile());
      Cached hit = answers.getIfPresent(key);
      if (hit != null && repository.isValid(generation, hit.sources()))
        return response(hit, generation, "EXACT", start);
      // One owner computes each exact key; concurrent callers wait on the same result with their
      // own deadlines. The owner removes the entry even when inference fails.
      CompletableFuture<Cached> own = new CompletableFuture<>();
      CompletableFuture<Cached> existing = inFlight.putIfAbsent(key, own);
      Cached result;
      String cache = "MISS";
      if (existing != null) {
        try {
          result = existing.get(deadline.remaining().toMillis(), TimeUnit.MILLISECONDS);
          cache = "COALESCED";
        } catch (InterruptedException error) {
          Thread.currentThread().interrupt();
          throw new SupportException("DEADLINE_EXCEEDED", 504, "Request interrupted.");
        } catch (TimeoutException error) {
          throw new SupportException(
              "DEADLINE_EXCEEDED", 504, "Timed out waiting for the local model.");
        } catch (ExecutionException error) {
          if (error.getCause() instanceof RuntimeException runtime) throw runtime;
          throw new IllegalStateException(error);
        }
      } else {
        try {
          result = compute(question, generation, profile, key, deadline);
          if (result.answer().status().equals("ANSWERED")) answers.put(key, result);
          own.complete(result);
        } catch (RuntimeException error) {
          own.completeExceptionally(error);
          throw error;
        } finally {
          inFlight.remove(key, own);
        }
      }
      deadline.check();
      return response(result, generation, cache, start);
    } finally {
      admitted.release();
      metrics.timer("rag.request.duration").record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
    }
  }

  /**
   * Embeds and retrieves on a cache miss, retrying full retrieval when reused candidates cannot
   * answer.
   */
  private Cached compute(
      Question question,
      Generation generation,
      ModelProfile profile,
      String key,
      Deadline deadline) {
    if (!question.filters().region().isEmpty() || !question.filters().documentVersion().isEmpty())
      return empty(Answer.clarification());
    if (question
        .question()
        .matches(
            "(?is).*\\b(current outage|live status|right now|today.s price|latest price|my account balance)\\b.*"))
      return empty(Answer.unavailable("INSUFFICIENT_EVIDENCE"));
    // Nomic uses distinct task prefixes for questions and indexed document passages.
    String embedInput = "search_query: " + question.retrievalText();
    String embedKey = Hashes.sha256(profile.embeddingProfile() + embedInput);
    float[] vector = embeddings.getIfPresent(embedKey);
    if (vector == null) {
      vector = model.embed(List.of(embedInput), deadline).getFirst();
      embeddings.put(embedKey, vector.clone());
    }
    String scope =
        Hashes.sha256(
            generation.id()
                + profile.answerProfile()
                + serialize(question.filters())
                + serialize(question.previousQuestions()));
    List<String> ids = retrieval.getIfPresent(key);
    boolean semanticUsed = false;
    if (ids == null) {
      float[] queryVector = vector;
      Optional<Candidates> similar =
          semantic.asMap().values().stream()
              .filter(c -> c.scope().equals(scope))
              .filter(c -> cosine(queryVector, c.vector()) >= properties.semanticCacheSimilarity())
              .max(Comparator.comparingDouble(c -> cosine(queryVector, c.vector())));
      // Similarity only reuses retrieval candidates. This question still gets its own selection
      // and coverage checks, so a paraphrase cannot inherit an old answer unchecked.
      ids = similar.map(Candidates::ids).orElse(List.of());
      semanticUsed = !ids.isEmpty();
    }
    List<Evidence> ranked =
        repository.retrieve(
            generation, question.retrievalText(), question.filters().service(), vector, ids);
    List<Evidence> evidence = budget(ranked, question);
    Cached result = checkAndRender(question, generation, evidence, semanticUsed, deadline);
    if (!ids.isEmpty() && !result.answer().status().equals("ANSWERED")) {
      ranked =
          repository.retrieve(
              generation,
              question.retrievalText(),
              question.filters().service(),
              vector,
              List.of());
      evidence = budget(ranked, question);
      result = checkAndRender(question, generation, evidence, false, deadline);
    }
    if (result.answer().status().equals("ANSWERED")) {
      List<String> candidates = ranked.stream().limit(80).map(Evidence::id).toList();
      retrieval.put(key, candidates);
      if (semantic.estimatedSize() >= 2000)
        semantic.asMap().keySet().stream().findFirst().ifPresent(semantic::invalidate);
      semantic.put(key, new Candidates(scope, vector.clone(), candidates));
    }
    return result;
  }

  /** Deduplicates and bounds evidence while leaving prompt/output headroom for model validation. */
  private List<Evidence> budget(List<Evidence> candidates, Question question) {
    int remaining =
        Math.min(
            properties.maxEvidenceTokens(),
            properties.contextTokens() - tokens.generationTokens(serialize(question)) - 2400);
    List<Evidence> selected = new ArrayList<>();
    Set<String> contents = new HashSet<>();
    for (Evidence e : candidates) {
      if (e.similarity() < properties.minimumSimilarity() || !contents.add(e.text())) continue;
      int size = tokens.generationTokens(serialize(e)) + 40;
      if (size <= remaining) {
        selected.add(e);
        remaining -= size;
      }
      if (selected.size() == 8) break;
    }
    return List.copyOf(selected);
  }

  /**
   * Selects evidence, verifies coverage, and renders stored text with server-owned citations. Both
   * checks use the same model and are not independent proof of correctness.
   */
  private Cached checkAndRender(
      Question question,
      Generation generation,
      List<Evidence> evidence,
      boolean semanticUsed,
      Deadline deadline) {
    if (evidence.isEmpty()) return empty(Answer.unavailable("NO_EVIDENCE"));
    try {
      Selection selection = model.select(question, evidence, deadline);
      if (selection.decision().equals("CLARIFY") && selection.evidenceIds().isEmpty())
        return empty(Answer.clarification());
      if (selection.decision().equals("UNAVAILABLE") && selection.evidenceIds().isEmpty())
        return empty(Answer.unavailable("SELECTION_REJECTED"));
      List<Evidence> selected = validateSelection(selection, evidence);
      if (selected.isEmpty()) return empty(Answer.unavailable("VALIDATION_FAILED"));
      // Verification is a second Qwen call with only the selected passages, not all candidates.
      if (!model.verify(question, selected, deadline))
        return empty(Answer.unavailable("COVERAGE_REJECTED"));
      List<String> sources = selected.stream().map(Evidence::sourceId).distinct().toList();
      if (!repository.isValid(generation, sources))
        return empty(Answer.unavailable("VALIDATION_FAILED"));
      List<Claim> claims = new ArrayList<>();
      List<Citation> citations = new ArrayList<>();
      for (int i = 0; i < selected.size(); i++) {
        Evidence e = selected.get(i);
        String id = "S" + (i + 1);
        // Copy stored text rather than allowing the model to synthesize unsupported AWS prose.
        claims.add(new Claim("C" + (i + 1), e.text(), List.of(id)));
        citations.add(
            new Citation(
                id,
                e.id(),
                e.sourceId(),
                e.title(),
                e.url() + (e.anchor().isBlank() ? "" : "#" + e.anchor()),
                e.heading(),
                e.text(),
                e.fetchedAt()));
      }
      return new Cached(
          new Answer("ANSWERED", "Relevant documentation excerpts", null, claims, citations),
          sources,
          semanticUsed);
    } catch (SupportException error) {
      if (error.code().equals("INVALID_MODEL_OUTPUT"))
        return empty(Answer.unavailable("VALIDATION_FAILED"));
      throw error;
    }
  }

  /** Resolves only a nonempty, unique selection of at most three IDs from supplied candidates. */
  public static List<Evidence> validateSelection(Selection selection, List<Evidence> candidates) {
    if (!selection.decision().equals("ANSWERABLE")
        || selection.evidenceIds().isEmpty()
        || selection.evidenceIds().size() > 3
        || selection.evidenceIds().stream().distinct().count() != selection.evidenceIds().size())
      return List.of();
    Map<String, Evidence> byId = new HashMap<>();
    candidates.forEach(e -> byId.put(e.id(), e));
    if (!byId.keySet().containsAll(selection.evidenceIds())) return List.of();
    return selection.evidenceIds().stream().map(byId::get).toList();
  }

  private Cached empty(Answer answer) {
    return new Cached(answer, List.of(), false);
  }

  /** Rechecks validity at the response boundary and assigns fresh request metadata. */
  private ChatResponse response(
      Cached cached, Generation generation, String disposition, long start) {
    Answer answer = cached.answer();
    if (!repository.isValid(generation, cached.sources()))
      answer = Answer.unavailable("VALIDATION_FAILED");
    metrics.counter("rag.responses", "status", answer.status(), "cache", disposition).increment();
    return new ChatResponse(
        UUID.randomUUID().toString(),
        answer.status(),
        "EXTRACTIVE_STRICT",
        answer.message(),
        answer.reason(),
        answer.claims(),
        answer.citations(),
        generation.id(),
        generation.publishedAt(),
        disposition,
        cached.semanticUsed(),
        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
  }

  /**
   * Scopes exact reuse by question/history/filters, corpus generation, policy epoch, and model
   * policy.
   */
  private String key(Question question, Generation g, String profile) {
    return Hashes.sha256(serialize(question) + ":" + g.id() + ":" + g.epoch() + ":" + profile);
  }

  private String serialize(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (JsonProcessingException error) {
      throw new IllegalArgumentException(error);
    }
  }

  /** Calculates cosine similarity for already validated, compatible embedding vectors. */
  public static double cosine(float[] a, float[] b) {
    if (a.length != b.length) return -1;
    double dot = 0, aa = 0, bb = 0;
    for (int i = 0; i < a.length; i++) {
      dot += a[i] * b[i];
      aa += a[i] * a[i];
      bb += b[i] * b[i];
    }
    return aa == 0 || bb == 0 ? -1 : dot / Math.sqrt(aa * bb);
  }
}

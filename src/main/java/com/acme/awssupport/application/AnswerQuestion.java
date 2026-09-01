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
 * Coordinates bounded Agentic RAG, including caches, retrieval, synthesis, and grounding review.
 *
 * <p>On an uncached path, Nomic embeds the question and the repository retrieves initial passages.
 * Qwen may request one additional local-corpus search round, then drafts cited claims and reviews
 * their grounding. Java validates proposed searches, cited evidence IDs, source state, budgets, and
 * call bounds. Missing or rejected evidence causes abstention.
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

  /** Embeds and retrieves on a cache miss, then runs one bounded research decision. */
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
    float[] vector = embeddings(List.of(question.retrievalText()), profile, deadline).getFirst();
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
      // Similarity only supplies candidate hints. This question still gets its own research,
      // answer, and grounding stages, so a paraphrase cannot inherit an old answer unchecked.
      ids = similar.map(Candidates::ids).orElse(List.of());
      semanticUsed = !ids.isEmpty();
    }
    List<Evidence> ranked =
        repository.retrieve(
            generation, question.retrievalText(), question.filters().service(), vector, ids);
    // A semantic hint must not narrow the only retrieval view. Merge it with one full retrieval
    // before any model decision; this adds a cheap database read rather than another Qwen loop.
    if (semanticUsed) {
      List<Evidence> full =
          repository.retrieve(
              generation,
              question.retrievalText(),
              question.filters().service(),
              vector,
              List.of());
      ranked = mergeRankings(List.of(ranked, full));
    }
    List<Evidence> evidence = budget(ranked, question);
    Cached result =
        researchAndRender(question, generation, profile, evidence, semanticUsed, deadline);
    if (result.answer().status().equals("ANSWERED")) {
      List<String> candidates = ranked.stream().limit(80).map(Evidence::id).toList();
      retrieval.put(key, candidates);
      if (semantic.estimatedSize() >= 2000)
        semantic.asMap().keySet().stream().findFirst().ifPresent(semantic::invalidate);
      semantic.put(key, new Candidates(scope, vector.clone(), candidates));
    }
    return result;
  }

  /** Returns cached vectors and batches only the missing query embeddings into one Nomic call. */
  private List<float[]> embeddings(List<String> queries, ModelProfile profile, Deadline deadline) {
    List<float[]> result = new ArrayList<>(Collections.nCopies(queries.size(), null));
    List<String> missingInputs = new ArrayList<>();
    List<Integer> missingIndexes = new ArrayList<>();
    for (int i = 0; i < queries.size(); i++) {
      String input = "search_query: " + queries.get(i);
      String cacheKey = Hashes.sha256(profile.embeddingProfile() + input);
      float[] cached = embeddings.getIfPresent(cacheKey);
      if (cached == null) {
        missingInputs.add(input);
        missingIndexes.add(i);
      } else result.set(i, cached.clone());
    }
    if (!missingInputs.isEmpty()) {
      List<float[]> generated = model.embed(missingInputs, deadline);
      if (generated.size() != missingInputs.size())
        throw new IllegalStateException("Embedding result count does not match input count");
      for (int i = 0; i < generated.size(); i++) {
        int index = missingIndexes.get(i);
        float[] vector = generated.get(i);
        String input = missingInputs.get(i);
        embeddings.put(Hashes.sha256(profile.embeddingProfile() + input), vector.clone());
        result.set(index, vector.clone());
      }
    }
    return List.copyOf(result);
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
      // Six passages keep three serial Qwen stages within the local latency target while still
      // allowing one distinct source for every permitted answer claim.
      if (selected.size() == 6) break;
    }
    return List.copyOf(selected);
  }

  /**
   * Lets Qwen choose one optional search round, then drafts and reviews a grounded answer.
   *
   * <p>The same model makes all decisions, so its final review is not independent proof of
   * correctness. Java still validates action shape, call bounds, evidence membership, and source
   * provenance.
   */
  private Cached researchAndRender(
      Question question,
      Generation generation,
      ModelProfile profile,
      List<Evidence> evidence,
      boolean semanticUsed,
      Deadline deadline) {
    if (evidence.isEmpty()) return empty(Answer.unavailable("NO_EVIDENCE"));
    try {
      ResearchDecision decision = model.decide(question, evidence, deadline);
      if (!validDecision(decision)) return empty(Answer.unavailable("VALIDATION_FAILED"));
      metrics.counter("rag.agent.decision", "action", decision.action()).increment();
      if (decision.action().equals("CLARIFY")) return empty(Answer.clarification());
      if (decision.action().equals("UNAVAILABLE"))
        return empty(Answer.unavailable("RESEARCH_REJECTED"));
      List<Evidence> finalEvidence = evidence;
      if (decision.action().equals("SEARCH_MORE")) {
        metrics.counter("rag.agent.additional_searches").increment(decision.searches().size());
        List<String> queries = decision.searches().stream().map(SearchRequest::query).toList();
        List<float[]> vectors = embeddings(queries, profile, deadline);
        List<List<Evidence>> rankings = new ArrayList<>();
        rankings.add(evidence);
        for (int i = 0; i < decision.searches().size(); i++) {
          SearchRequest search = decision.searches().get(i);
          String service =
              question.filters().service().isEmpty()
                  ? search.service()
                  : question.filters().service();
          rankings.add(
              repository.retrieve(generation, search.query(), service, vectors.get(i), List.of()));
        }
        finalEvidence = budget(mergeRankings(rankings), question);
        if (finalEvidence.isEmpty()) return empty(Answer.unavailable("ADDITIONAL_SEARCH_EMPTY"));
      }
      return draftAndRender(question, generation, finalEvidence, semanticUsed, deadline);
    } catch (SupportException error) {
      if (error.code().equals("INVALID_MODEL_OUTPUT"))
        return empty(Answer.unavailable("VALIDATION_FAILED"));
      throw error;
    }
  }

  /** Validates and renders a model-written draft with server-owned source metadata. */
  private Cached draftAndRender(
      Question question,
      Generation generation,
      List<Evidence> evidence,
      boolean semanticUsed,
      Deadline deadline) {
    AnswerDraft draft = model.answer(question, evidence, deadline);
    if (draft.decision().equals("UNAVAILABLE") && draft.claims().isEmpty())
      return empty(Answer.unavailable("ANSWER_REJECTED"));
    if (!validDraft(draft, evidence)) return empty(Answer.unavailable("VALIDATION_FAILED"));
    Set<String> citedIds = new LinkedHashSet<>();
    draft.claims().forEach(claim -> citedIds.addAll(claim.evidenceIds()));
    List<Evidence> cited = evidence.stream().filter(e -> citedIds.contains(e.id())).toList();
    if (!model.verify(question, draft, cited, deadline))
      return empty(Answer.unavailable("GROUNDING_REJECTED"));
    List<String> sources = cited.stream().map(Evidence::sourceId).distinct().toList();
    if (!repository.isValid(generation, sources))
      return empty(Answer.unavailable("VALIDATION_FAILED"));
    Map<String, String> citationIds = new LinkedHashMap<>();
    List<Citation> citations = new ArrayList<>();
    for (int i = 0; i < cited.size(); i++) {
      Evidence e = cited.get(i);
      String id = "S" + (i + 1);
      citationIds.put(e.id(), id);
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
    List<Claim> claims = new ArrayList<>();
    for (int i = 0; i < draft.claims().size(); i++) {
      DraftClaim claim = draft.claims().get(i);
      claims.add(
          new Claim(
              "C" + (i + 1),
              claim.text(),
              claim.evidenceIds().stream().map(citationIds::get).toList()));
    }
    return new Cached(
        new Answer("ANSWERED", "Grounded response", null, claims, citations),
        sources,
        semanticUsed);
  }

  /** Allows exactly one well-formed action and at most three distinct additional searches. */
  public static boolean validDecision(ResearchDecision decision) {
    if (decision == null
        || !Set.of("ANSWER", "SEARCH_MORE", "CLARIFY", "UNAVAILABLE").contains(decision.action()))
      return false;
    return decision.action().equals("SEARCH_MORE")
        ? !decision.searches().isEmpty()
            && decision.searches().size() <= 3
            && decision.searches().stream().distinct().count() == decision.searches().size()
        : decision.searches().isEmpty();
  }

  /** Checks draft size and resolves every model-selected ID against supplied evidence. */
  public static boolean validDraft(AnswerDraft draft, List<Evidence> evidence) {
    if (draft == null
        || !draft.decision().equals("ANSWER")
        || draft.claims().isEmpty()
        || draft.claims().size() > 6) return false;
    Set<String> available =
        evidence.stream().map(Evidence::id).collect(java.util.stream.Collectors.toSet());
    Set<String> texts = new HashSet<>();
    for (DraftClaim claim : draft.claims()) {
      if (claim.text().isEmpty()
          || claim.text().length() > 2000
          || !texts.add(claim.text())
          || claim.evidenceIds().isEmpty()
          || claim.evidenceIds().size() > 3
          || claim.evidenceIds().stream().distinct().count() != claim.evidenceIds().size()
          || !available.containsAll(claim.evidenceIds())) return false;
    }
    return true;
  }

  /** Interleaves independent rankings so a follow-up query cannot be hidden behind initial rows. */
  static List<Evidence> mergeRankings(List<List<Evidence>> rankings) {
    LinkedHashMap<String, Evidence> merged = new LinkedHashMap<>();
    int max = rankings.stream().mapToInt(List::size).max().orElse(0);
    for (int rank = 0; rank < max; rank++) {
      for (List<Evidence> ranking : rankings)
        if (rank < ranking.size()) merged.putIfAbsent(ranking.get(rank).id(), ranking.get(rank));
    }
    return List.copyOf(merged.values());
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
        "GROUNDED_SYNTHESIS",
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

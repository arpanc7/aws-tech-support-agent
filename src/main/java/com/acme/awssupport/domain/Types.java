package com.acme.awssupport.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Framework-independent values shared by ingestion, retrieval, inference, and API responses.
 *
 * <p>Nested records keep source provenance and corpus/model identity explicit across boundaries.
 * Request constructors enforce basic input limits; model selections still require application-level
 * validation before they can drive retrieval or become cited answers.
 */
public final class Types {
  private Types() {}

  public static final Set<String> SERVICES =
      Set.of("IAM", "S3", "EC2", "VPC", "LAMBDA", "CLOUDWATCH");
  public static final String UNAVAILABLE =
      "Information is not available in the local documentation.";
  public static final String POLICY_VERSION = "bounded-agent-v1:grounded-synthesis-v1:retrieval-v4";

  /**
   * Restricts retrieval scope; null fields become empty strings and service names become uppercase.
   */
  public record Filters(String service, String region, String documentVersion) {
    public Filters {
      service = service == null ? "" : service.strip().toUpperCase(java.util.Locale.ROOT);
      region = region == null ? "" : region.strip();
      documentVersion = documentVersion == null ? "" : documentVersion.strip();
      if ((!service.isEmpty() && !SERVICES.contains(service))
          || region.length() > 64
          || documentVersion.length() > 80) {
        throw new SupportException(
            "INVALID_FILTER", 400, "Use a supported service and bounded scope filters.");
      }
    }
  }

  /** Validated question and bounded prior-question history used to construct retrieval text. */
  public record Question(String question, List<String> previousQuestions, Filters filters) {
    public Question {
      question = normalize(question);
      previousQuestions =
          previousQuestions == null
              ? List.of()
              : previousQuestions.stream().map(Types::normalize).toList();
      filters = filters == null ? new Filters(null, null, null) : filters;
      if (question.isEmpty()
          || question.length() > 4000
          || previousQuestions.size() > 3
          || previousQuestions.stream().anyMatch(s -> s.isEmpty() || s.length() > 4000)) {
        throw new SupportException(
            "INVALID_QUESTION",
            400,
            "Enter a question of 1–4,000 characters and at most three prior questions.");
      }
    }

    /** Combines prior questions and the current question without an LLM query rewrite. */
    public String retrievalText() {
      return previousQuestions.isEmpty()
          ? question
          : String.join("\n", previousQuestions) + "\n" + question;
    }
  }

  /** One approved manifest entry, with a canonical source URL and optional local import path. */
  public record Source(String id, String service, String url, String localFile) {
    public Source {
      if (id == null
          || !id.matches("[a-z0-9-]{1,100}")
          || !SERVICES.contains(service)
          || url == null) {
        throw new IllegalArgumentException("Invalid manifest source");
      }
    }
  }

  /** A bounded, nonempty source set with unique IDs and URLs; ingestion requires every entry. */
  public record Manifest(String name, List<Source> sources) {
    public Manifest {
      sources = List.copyOf(sources);
      if (name == null
          || sources.isEmpty()
          || sources.size() > 500
          || sources.stream().map(Source::id).distinct().count() != sources.size()
          || sources.stream().map(Source::url).distinct().count() != sources.size()) {
        throw new IllegalArgumentException("Manifest must have 1–500 distinct source IDs and URLs");
      }
    }
  }

  /** An extracted structural unit whose code flag prevents unsafe overlap handling. */
  public record Block(String heading, String anchor, String text, boolean code) {}

  /** The title and ordered blocks extracted from a single document. */
  public record ParsedDocument(String title, List<Block> blocks) {}

  /** Source metadata including snapshot integrity, provenance, and conditional-fetch validators. */
  public record Document(
      String sourceId,
      String service,
      String url,
      String title,
      String rawHash,
      String contentHash,
      String snapshotPath,
      Instant fetchedAt,
      String etag,
      String lastModified) {}

  /**
   * A passage and its exact embedding input. The defensively copied vector is null until embedded.
   */
  public record Chunk(
      String id,
      String sourceId,
      String heading,
      String anchor,
      int ordinal,
      String text,
      String embeddingInput,
      String inputHash,
      float[] vector) {
    public Chunk {
      vector = vector == null ? null : vector.clone();
    }

    @Override
    public float[] vector() {
      return vector == null ? null : vector.clone();
    }

    /** Returns a new embedded chunk while retaining the original text and provenance. */
    public Chunk embedded(float[] values) {
      return new Chunk(
          id, sourceId, heading, anchor, ordinal, text, embeddingInput, inputHash, values);
    }
  }

  /** A retrieved passage with provenance and cosine similarity, not a correctness probability. */
  public record Evidence(
      String id,
      String sourceId,
      String service,
      String title,
      String url,
      String heading,
      String anchor,
      String text,
      Instant fetchedAt,
      double similarity) {}

  /**
   * A pinned corpus identity and policy epoch used to scope retrieval and validate cached answers.
   */
  public record Generation(
      UUID id, String profile, String manifestHash, Instant publishedAt, long epoch) {}

  /** Pinned model/tokenizer identities used to isolate compatible indexes and answer caches. */
  public record ModelProfile(
      String embeddingDigest, String generatorDigest, String tokenizerDigest, String promptDigest) {
    /**
     * Includes extraction/chunking versions so incompatible preprocessing cannot reuse the index.
     */
    public String embeddingProfile() {
      return embeddingDigest + ":" + tokenizerDigest + ":extract-v2:chunk-v1:768";
    }

    /**
     * Adds the generation model and answer policy to the embedding/index compatibility identity.
     */
    public String answerProfile() {
      return embeddingProfile() + ":" + generatorDigest + ":" + POLICY_VERSION + ":" + promptDigest;
    }
  }

  /** One bounded model-proposed local-corpus search; it never represents an executable tool. */
  public record SearchRequest(String query, String service) {
    public SearchRequest {
      query = normalize(query);
      service = service == null ? "" : service.strip().toUpperCase(java.util.Locale.ROOT);
      if (query.isEmpty()
          || query.length() > 1000
          || (!service.isEmpty() && !SERVICES.contains(service))) {
        throw new IllegalArgumentException("Invalid model-proposed documentation search");
      }
    }
  }

  /** An untrusted decision to answer, clarify, abstain, or run one additional search round. */
  public record ResearchDecision(String action, List<SearchRequest> searches) {
    public ResearchDecision {
      action = action == null ? "" : action;
      searches = searches == null ? List.of() : List.copyOf(searches);
    }
  }

  /** One untrusted synthesized claim and the stored evidence IDs it says support the text. */
  public record DraftClaim(String text, List<String> evidenceIds) {
    public DraftClaim {
      text = normalize(text);
      evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
    }
  }

  /** A bounded model-written answer draft that remains untrusted until application validation. */
  public record AnswerDraft(String decision, List<DraftClaim> claims) {
    public AnswerDraft {
      decision = decision == null ? "" : decision;
      claims = claims == null ? List.of() : List.copyOf(claims);
    }
  }

  /** A validated answer claim linked to server-created citations. */
  public record Claim(String id, String text, List<String> citationIds) {}

  /** Server-built attribution tying an answer excerpt to a stored passage and its source URL. */
  public record Citation(
      String id,
      String spanId,
      String documentId,
      String title,
      String sourceUrl,
      String heading,
      String quote,
      Instant fetchedAt) {}

  /** An application result: cited excerpts, evidence abstention, or a clarification request. */
  public record Answer(
      String status, String message, String reason, List<Claim> claims, List<Citation> citations) {
    public Answer {
      claims = List.copyOf(claims);
      citations = List.copyOf(citations);
    }

    /** Creates a consistent abstention without falling back to model training knowledge. */
    public static Answer unavailable(String reason) {
      return new Answer("INFORMATION_NOT_AVAILABLE", UNAVAILABLE, reason, List.of(), List.of());
    }

    /** Requests a narrower scope without inventing assumptions or a speculative answer. */
    public static Answer clarification() {
      return new Answer(
          "CLARIFICATION_REQUIRED",
          "Please specify the AWS service and the exact issue or scope you want to investigate.",
          "AMBIGUOUS_SCOPE",
          List.of(),
          List.of());
    }
  }

  /** Public response envelope with request identity, provenance, cache disposition, and timing. */
  public record ChatResponse(
      String requestId,
      String status,
      String answerMode,
      String message,
      String reason,
      List<Claim> claims,
      List<Citation> citations,
      UUID corpusGeneration,
      Instant snapshotPublishedAt,
      String cacheDisposition,
      boolean semanticCandidatesUsed,
      long durationMs) {}

  /** Operational snapshot of corpus coverage, freshness, and the most recent refresh outcome. */
  public record CorpusStatus(
      UUID generation,
      Instant publishedAt,
      Instant lastCompleteCheck,
      int documents,
      int chunks,
      Map<String, Integer> services,
      boolean stale,
      String state,
      String lastRefreshError) {}

  /**
   * Normalizes line endings and outer whitespace while preserving case, punctuation, and negation.
   */
  public static String normalize(String text) {
    return text == null ? "" : text.replace("\r\n", "\n").replace('\r', '\n').strip();
  }
}

package com.acme.awssupport.ports;

import com.acme.awssupport.domain.Types.*;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence boundary for versioned documentation, retrieval, and ingestion lifecycle state.
 *
 * <p>Queries use a pinned generation. Implementations must preserve complete-generation publication
 * and expose revocation/epoch checks so callers can reject evidence invalidated while a request
 * runs.
 */
public interface CorpusRepository {
  /** Returns the current ready generation, or fails when no usable corpus exists. */
  Generation active();

  /**
   * Checks the pinned generation, policy epoch, and source revocations before evidence is served.
   */
  boolean isValid(Generation generation, List<String> sourceIds);

  /** Reports corpus counts, freshness, and refresh state without model inference. */
  CorpusStatus status();

  /**
   * Ranks evidence using query text and a compatible vector. Empty service means all services;
   * empty candidate IDs request full retrieval. Results still require answer validation.
   */
  List<Evidence> retrieve(
      Generation generation,
      String query,
      String service,
      float[] vector,
      List<String> candidateIds);

  /** Finds a source in the active corpus for conditional downloads and snapshot reuse. */
  Optional<Document> previousDocument(String sourceId);

  /** Looks up a checkpoint scoped by exact embedding-input hash and compatible model profile. */
  Optional<float[]> embedding(String inputHash, String profile);

  /** Persists a reusable embedding independently of eventual corpus publication. */
  void saveEmbedding(String inputHash, String profile, float[] vector);

  /**
   * Records a source-check attempt; failure must not advance the last successful verification time.
   */
  void checked(String sourceId, boolean success, String message);

  /** Starts a job and marks abandoned jobs failed; the caller must hold the ingestion lease. */
  UUID beginJob(String manifestHash);

  /** Marks an ingestion job failed without replacing the active corpus. */
  void failJob(UUID job, String message);

  /**
   * Atomically publishes all documents/chunks. Returns false for a successful unchanged refresh.
   */
  boolean publish(
      UUID job, String manifestHash, String profile, List<Document> documents, List<Chunk> chunks);

  /** Marks an unchanged job successful and advances the complete-corpus freshness timestamp. */
  void noChange(UUID job);

  /**
   * Excludes a source and advances the policy epoch so cached evidence cannot bypass revocation.
   */
  void revoke(String sourceId);

  /** Activates a retained compatible generation and advances the policy epoch. */
  void rollback(UUID generation);

  /** Returns the last successful full refresh time, or null when freshness is unknown. */
  Instant lastCompleteCheck();

  /** Returns the persisted retry gate, or null when no retry delay has been recorded. */
  Instant nextRefreshAttempt();

  /** Persists a retry gate so process restarts do not trigger an immediate refresh retry. */
  void deferRefreshUntil(Instant time);
}

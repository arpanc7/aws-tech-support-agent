package com.acme.awssupport;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.acme.awssupport.adapters.inbound.RefreshScheduler;
import com.acme.awssupport.application.IngestCorpus;
import com.acme.awssupport.bootstrap.RagProperties;
import com.acme.awssupport.ports.CorpusRepository;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * Checks refresh eligibility and durable retry backoff using mocked ingestion and repository state.
 */
class RefreshSchedulerTest {
  RagProperties enabled() {
    var p = AnswerQuestionTest.properties();
    return new RagProperties(
        p.ollamaUrl(),
        p.chatModel(),
        p.embeddingModel(),
        p.dataDir(),
        p.manifest(),
        p.requestTimeout(),
        p.refreshInterval(),
        true,
        p.contextTokens(),
        p.maxEvidenceTokens(),
        p.maxChunks(),
        p.minimumSimilarity(),
        p.semanticCacheSimilarity());
  }

  @Test
  void retryBackoffSurvivesSchedulerRecreation() throws Exception {
    var repository = mock(CorpusRepository.class);
    var ingest = mock(IngestCorpus.class);
    when(repository.nextRefreshAttempt()).thenReturn(Instant.now().plusSeconds(600));
    new RefreshScheduler(enabled(), repository, ingest).refreshIfDue();
    new RefreshScheduler(enabled(), repository, ingest).refreshIfDue();
    verifyNoInteractions(ingest);
  }

  @Test
  void failedRefreshPersistsBackoffAndDoesNotAdvanceFreshness() throws Exception {
    var repository = mock(CorpusRepository.class);
    var ingest = mock(IngestCorpus.class);
    when(ingest.run(any(), eq(true))).thenThrow(new java.io.IOException("offline"));
    new RefreshScheduler(enabled(), repository, ingest).refreshIfDue();
    verify(repository).deferRefreshUntil(any());
    verify(repository, never()).noChange(any());
  }
}

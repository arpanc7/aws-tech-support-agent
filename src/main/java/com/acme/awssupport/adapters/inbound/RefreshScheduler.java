package com.acme.awssupport.adapters.inbound;

import com.acme.awssupport.application.IngestCorpus;
import com.acme.awssupport.bootstrap.RagProperties;
import com.acme.awssupport.ports.CorpusRepository;
import java.time.Instant;
import org.slf4j.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Polls for opt-in corpus refreshes while this application process is running.
 *
 * <p>Uses persisted freshness and retry state to avoid immediate retries after restart. Ingestion
 * rechecks eligibility under its cross-process lease. This scheduler does not wake a sleeping Mac
 * or run when the application is stopped.
 */
@Component
public class RefreshScheduler {
  private static final Logger log = LoggerFactory.getLogger(RefreshScheduler.class);
  private final RagProperties properties;
  private final CorpusRepository repository;
  private final IngestCorpus ingestion;
  private Instant nextAttempt = Instant.EPOCH;

  public RefreshScheduler(
      RagProperties properties, CorpusRepository repository, IngestCorpus ingestion) {
    this.properties = properties;
    this.repository = repository;
    this.ingestion = ingestion;
  }

  /** Checks refresh eligibility once per scheduled poll and backs off after a failed attempt. */
  @Scheduled(fixedDelay = 60000, initialDelay = 60000)
  public void refreshIfDue() {
    if (!properties.refreshEnabled() || Instant.now().isBefore(nextAttempt)) return;
    try {
      Instant last = repository.lastCompleteCheck();
      if (last != null && last.plus(properties.refreshInterval()).isAfter(Instant.now())) return;
      Instant retry = repository.nextRefreshAttempt();
      if (retry != null && retry.isAfter(Instant.now())) return;
      ingestion.run(properties.manifest(), true);
    } catch (Exception error) {
      nextAttempt = Instant.now().plusSeconds(900);
      try {
        repository.deferRefreshUntil(nextAttempt);
      } catch (RuntimeException unavailable) {
        /* Database failure: retain in-process backoff. */
      }
      log.warn(
          "Scheduled refresh failed; existing corpus retained: {}",
          error.getClass().getSimpleName());
    }
  }
}

package com.acme.awssupport.adapters.inbound;

import com.acme.awssupport.ports.CorpusRepository;
import com.acme.awssupport.ports.LocalModel;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Checks that a ready corpus and the installed embedding profile are compatible.
 *
 * <p>Performs no inference and never downloads missing models. A passing check is not a
 * model-quality evaluation and does not independently exercise the inference quarantine/recovery
 * path.
 */
@Component("corpus")
public class CorpusHealthIndicator implements HealthIndicator {
  private final CorpusRepository repository;
  private final LocalModel model;

  public CorpusHealthIndicator(CorpusRepository repository, LocalModel model) {
    this.repository = repository;
    this.model = model;
  }

  @Override
  public Health health() {
    try {
      boolean compatible = repository.active().profile().equals(model.profile().embeddingProfile());
      return compatible ? Health.up().build() : Health.down().build();
    } catch (RuntimeException unavailable) {
      return Health.down().build();
    }
  }
}

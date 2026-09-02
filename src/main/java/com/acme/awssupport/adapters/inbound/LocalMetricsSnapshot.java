package com.acme.awssupport.adapters.inbound;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.*;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Writes privacy-safe application metrics to a replaceable local JSON snapshot.
 *
 * <p>Only meters under the {@code rag.} namespace are exported. Those meters use bounded
 * operational tags and contain no questions, prompt bodies, document text, or credentials.
 */
@Component
public class LocalMetricsSnapshot {
  private static final Logger LOG = LoggerFactory.getLogger(LocalMetricsSnapshot.class);
  private final MeterRegistry registry;
  private final ObjectMapper json;
  private final Path destination;

  public LocalMetricsSnapshot(
      MeterRegistry registry,
      ObjectMapper json,
      @Value("${rag.metrics-file:${java.io.tmpdir}/aws-tech-support-agent/metrics.json}")
          String destination) {
    this.registry = registry;
    this.json = json;
    this.destination = Path.of(destination).toAbsolutePath().normalize();
  }

  /** Replaces the snapshot atomically so readers never observe partially written JSON. */
  @Scheduled(fixedDelayString = "${rag.metrics-snapshot-interval:30s}", initialDelay = 30000)
  public void writeSnapshot() {
    try {
      Path parent = destination.getParent();
      if (parent == null) throw new IOException("Metrics path must have a parent directory");
      Files.createDirectories(parent);
      List<Map<String, Object>> meters =
          registry.getMeters().stream()
              .filter(meter -> meter.getId().getName().startsWith("rag."))
              .sorted(Comparator.comparing(meter -> meter.getId().getName()))
              .map(LocalMetricsSnapshot::snapshot)
              .toList();
      Path temporary = Files.createTempFile(parent, "metrics-", ".json");
      try {
        json.writerWithDefaultPrettyPrinter()
            .writeValue(temporary.toFile(), Map.of("capturedAt", Instant.now(), "meters", meters));
        try {
          Files.move(
              temporary,
              destination,
              StandardCopyOption.ATOMIC_MOVE,
              StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
          Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
        }
      } finally {
        Files.deleteIfExists(temporary);
      }
    } catch (IOException error) {
      LOG.warn("Could not write local metrics snapshot to {}", destination, error);
    }
  }

  /** Captures final counters during a graceful shutdown. */
  @PreDestroy
  void writeFinalSnapshot() {
    writeSnapshot();
  }

  private static Map<String, Object> snapshot(Meter meter) {
    Map<String, String> tags = new TreeMap<>();
    meter.getId().getTags().forEach(tag -> tags.put(tag.getKey(), tag.getValue()));
    Map<String, Double> values = new TreeMap<>();
    for (Measurement measurement : meter.measure())
      values.put(measurement.getStatistic().getTagValueRepresentation(), measurement.getValue());
    return Map.of("name", meter.getId().getName(), "tags", tags, "values", values);
  }
}

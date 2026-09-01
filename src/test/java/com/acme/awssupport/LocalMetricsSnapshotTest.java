package com.acme.awssupport;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.awssupport.adapters.inbound.LocalMetricsSnapshot;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Verifies that the local operational snapshot exports only application metrics. */
class LocalMetricsSnapshotTest {
  @TempDir Path directory;

  @Test
  void writesOnlyRagMetersToValidJson() throws Exception {
    var registry = new SimpleMeterRegistry();
    registry.counter("rag.model.calls", "operation", "answer", "outcome", "success").increment();
    registry.counter("jvm.unrelated").increment();
    Path destination = directory.resolve("metrics.json");

    new LocalMetricsSnapshot(
            registry, new ObjectMapper().findAndRegisterModules(), destination.toString())
        .writeSnapshot();

    String content = Files.readString(destination);
    assertThat(content).contains("rag.model.calls", "answer", "success");
    assertThat(content).doesNotContain("jvm.unrelated");
    assertThat(new ObjectMapper().readTree(content).path("meters")).hasSize(1);
  }
}

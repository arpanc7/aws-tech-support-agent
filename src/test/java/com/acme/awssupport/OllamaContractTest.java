package com.acme.awssupport;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.acme.awssupport.adapters.outbound.DatabaseLocks;
import com.acme.awssupport.adapters.outbound.OllamaModel;
import com.acme.awssupport.bootstrap.RagProperties;
import com.acme.awssupport.domain.Deadline;
import com.acme.awssupport.domain.SupportException;
import com.acme.awssupport.domain.Types.*;
import com.acme.awssupport.ports.TokenCounter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Exercises the Ollama adapter against a controlled local HTTP server, without loading real models.
 *
 * <p>Covers vector/JSON validation, evidence aliases, tokenizer parity, redirect refusal, and
 * uncertain inference completion. These protocol checks do not measure model answer quality.
 */
class OllamaContractTest {
  HttpServer server;
  OllamaModel model;
  JdbcTemplate jdbc;
  String response = "{}";
  String request;
  int status = 200;
  long delay;
  final java.util.concurrent.atomic.AtomicInteger calls =
      new java.util.concurrent.atomic.AtomicInteger();
  final ObjectMapper json = new ObjectMapper();

  @BeforeEach
  void start() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/api/",
        exchange -> {
          calls.incrementAndGet();
          request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
          try {
            Thread.sleep(delay);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          byte[] body = response.getBytes(StandardCharsets.UTF_8);
          try {
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
          } finally {
            exchange.close();
          }
        });
    server.start();
    var defaults = AnswerQuestionTest.properties();
    var properties =
        new RagProperties(
            "http://127.0.0.1:" + server.getAddress().getPort(),
            defaults.chatModel(),
            defaults.embeddingModel(),
            defaults.dataDir(),
            defaults.manifest(),
            defaults.requestTimeout(),
            defaults.refreshInterval(),
            false,
            8192,
            4500,
            20000,
            .4,
            .94);
    var tokens = mock(TokenCounter.class);
    when(tokens.embeddingTokens(anyString())).thenReturn(20);
    when(tokens.generationTokens(anyString())).thenReturn(100);
    var locks = mock(DatabaseLocks.class);
    when(locks.acquire(anyLong(), any(), anyBoolean())).thenReturn(mock(DatabaseLocks.Lease.class));
    jdbc = mock(JdbcTemplate.class);
    when(jdbc.queryForObject(anyString(), eq(Boolean.class))).thenReturn(true);
    model =
        new OllamaModel(
            properties,
            json,
            tokens,
            locks,
            jdbc,
            new com.acme.awssupport.adapters.outbound.AgenticPromptChain(json),
            new SimpleMeterRegistry());
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  Deadline deadline() {
    return new Deadline(Duration.ofSeconds(3));
  }

  @Test
  void localEvidenceAliasesResolveOnlyToSuppliedStoredIds() throws Exception {
    Evidence evidence = evidence();
    response =
        json.writeValueAsString(
            Map.of(
                "done",
                true,
                "prompt_eval_count",
                100,
                "response",
                "{\"decision\":\"ANSWER\",\"claims\":[{\"text\":\"The maximum is 900 seconds.\",\"evidenceIds\":[\"E1\"]}]}"));
    assertThat(
            model
                .answer(
                    new Question("Maximum timeout?", List.of(), null),
                    List.of(evidence),
                    deadline())
                .claims()
                .getFirst()
                .evidenceIds())
        .containsExactly("stored-sha256");
    response =
        json.writeValueAsString(
            Map.of(
                "done",
                true,
                "prompt_eval_count",
                100,
                "response",
                "{\"decision\":\"ANSWER\",\"claims\":[{\"text\":\"Invented\",\"evidenceIds\":[\"E99\"]}]}"));
    assertThatThrownBy(
            () ->
                model.answer(
                    new Question("Maximum timeout?", List.of(), null),
                    List.of(evidence),
                    deadline()))
        .isInstanceOf(SupportException.class);
  }

  @Test
  void embeddingRequiresExpectedDimensionsAndDisablesTruncation() throws Exception {
    float[] vector = new float[768];
    vector[0] = 1;
    response = json.writeValueAsString(Map.of("embeddings", List.of(vector)));
    assertThat(model.embed(List.of("search_query: deny"), deadline()).getFirst()).hasSize(768);
    assertThat(json.readTree(request).path("truncate").asBoolean(true)).isFalse();
    response = "{\"embeddings\":[[1,2,3]]}";
    assertThatThrownBy(() -> model.embed(List.of("query"), deadline()))
        .isInstanceOfSatisfying(
            SupportException.class, e -> assertThat(e.code()).isEqualTo("INVALID_MODEL_OUTPUT"));
  }

  @Test
  void zeroEmbeddingsAreRejected() throws Exception {
    response = json.writeValueAsString(Map.of("embeddings", List.of(new float[768])));
    assertThatThrownBy(() -> model.embed(List.of("query"), deadline()))
        .isInstanceOf(SupportException.class);
  }

  @Test
  void incompleteOrMismatchedGenerationCannotPass() throws Exception {
    for (var output :
        List.of(
            Map.of(
                "done", true, "done_reason", "length", "response", "{\"verdict\":\"SUPPORTED\"}"),
            Map.of("done", false, "response", "{\"verdict\":\"SUPPORTED\"}"),
            Map.of(
                "done", true, "prompt_eval_count", 250, "response", "{\"verdict\":\"SUPPORTED\"}"),
            Map.of("done", true, "response", "not JSON"))) {
      response = json.writeValueAsString(output);
      assertThatThrownBy(
              () ->
                  model.verify(
                      new Question("Deny?", List.of(), null),
                      draft(),
                      List.of(evidence()),
                      deadline()))
          .isInstanceOf(SupportException.class);
    }
  }

  @Test
  void unsupportedAndExtraFieldsFailClosed() throws Exception {
    for (String answer :
        List.of(
            "{\"verdict\":\"UNSUPPORTED\"}", "{\"verdict\":\"SUPPORTED\",\"tool\":\"execute\"}")) {
      response =
          json.writeValueAsString(
              Map.of("done", true, "prompt_eval_count", 100, "response", answer));
      assertThat(
              model.verify(
                  new Question("Deny?", List.of(), null), draft(), List.of(evidence()), deadline()))
          .isFalse();
    }
  }

  @Test
  void redirectsAreNotFollowed() {
    status = 307;
    assertThatThrownBy(() -> model.embed(List.of("query"), deadline()))
        .isInstanceOfSatisfying(
            SupportException.class, e -> assertThat(e.code()).isEqualTo("MODEL_UNAVAILABLE"));
    verify(jdbc).update("UPDATE model_health SET healthy=true,reason='' WHERE singleton=true");
  }

  @Test
  void guardedLangChainStagePreservesWireLimitsAndDoesNotResetDeadline() throws Exception {
    response =
        json.writeValueAsString(
            Map.of(
                "done", true, "prompt_eval_count", 100, "response", "{\"verdict\":\"SUPPORTED\"}"));
    var question = new Question("Does {{current_date}} change the policy?", List.of(), null);
    assertThat(model.verify(question, draft(), List.of(evidence()), deadline())).isTrue();
    var body = json.readTree(request);
    assertThat(body.path("raw").asBoolean()).isTrue();
    assertThat(body.path("stream").asBoolean(true)).isFalse();
    assertThat(body.path("options").path("num_ctx").asInt()).isEqualTo(8192);
    assertThat(body.path("options").path("num_predict").asInt()).isEqualTo(800);
    assertThat(body.path("format").path("additionalProperties").asBoolean(true)).isFalse();
    assertThat(body.path("prompt").asText())
        .contains("{{current_date}}")
        .startsWith("<|im_start|>system\n")
        .endsWith("</think>\n\n");
    assertThatThrownBy(
            () -> model.verify(question, draft(), List.of(evidence()), new Deadline(Duration.ZERO)))
        .isInstanceOfSatisfying(
            SupportException.class, e -> assertThat(e.code()).isEqualTo("DEADLINE_EXCEEDED"));
    assertThat(calls.get()).isEqualTo(1);
  }

  @Test
  void uncertainTimeoutQuarantinesTheRuntime() {
    delay = 300;
    assertThatThrownBy(() -> model.embed(List.of("query"), new Deadline(Duration.ofMillis(50))))
        .isInstanceOf(SupportException.class);
    verify(jdbc, atLeastOnce()).update(contains("Inference completion uncertain"));
  }

  private Evidence evidence() {
    return new Evidence(
        "stored-sha256",
        "lambda-timeout",
        "LAMBDA",
        "Timeout",
        "https://docs.aws.amazon.com/lambda/latest/dg/configuration-timeout.html",
        "Timeout",
        "",
        "Maximum timeout is 900 seconds.",
        java.time.Instant.now(),
        .9);
  }

  private AnswerDraft draft() {
    return new AnswerDraft(
        "ANSWER", List.of(new DraftClaim("The maximum is 900 seconds.", List.of("stored-sha256"))));
  }
}

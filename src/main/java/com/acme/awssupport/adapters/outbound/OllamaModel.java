package com.acme.awssupport.adapters.outbound;

import com.acme.awssupport.bootstrap.RagProperties;
import com.acme.awssupport.domain.Deadline;
import com.acme.awssupport.domain.SupportException;
import com.acme.awssupport.domain.Types.*;
import com.acme.awssupport.ports.LocalModel;
import com.acme.awssupport.ports.TokenCounter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Calls the local Ollama HTTP API using pinned Nomic and Qwen model profiles.
 *
 * <p>Nomic returns embeddings. Qwen returns constrained JSON for a bounded research decision,
 * grounded answer draft, and separate grounding review. LangChain4j prompt stages compose policy
 * and evidence; this adapter preserves checked raw prompt formatting and validates response
 * shape/token counts. Application policy still validates every proposed search and cited ID.
 *
 * <p>A database-backed inference lease coordinates application processes. Persistent health state
 * blocks further inference after uncertain completion until operator recovery. Direct Ollama
 * clients outside this application do not participate in that lease.
 */
@Component
public class OllamaModel implements LocalModel {
  private static final String EMBED_DIGEST =
      "0a109f422b47e3a30ba2b10eca18548e944e8a23073ee3f3e947efcf3c45e59f";
  private static final String CHAT_DIGEST =
      "359d7dd4bcdab3d86b87d73ac27966f4dbb9f5efdfcc75d34a8764a09474fae7";
  private final HttpClient http =
      HttpClient.newBuilder()
          .connectTimeout(Duration.ofSeconds(3))
          .followRedirects(HttpClient.Redirect.NEVER)
          .build();
  private final RagProperties properties;
  private final ObjectMapper json;
  private final TokenCounter tokens;
  private final DatabaseLocks locks;
  private final JdbcTemplate jdbc;
  private final AgenticPromptChain prompts;
  private final MeterRegistry metrics;

  public OllamaModel(
      RagProperties properties,
      ObjectMapper json,
      TokenCounter tokens,
      DatabaseLocks locks,
      JdbcTemplate jdbc,
      AgenticPromptChain prompts,
      MeterRegistry metrics) {
    this.properties = properties;
    this.json = json;
    this.tokens = tokens;
    this.locks = locks;
    this.jdbc = jdbc;
    this.prompts = prompts;
    this.metrics = metrics;
    URI uri = URI.create(properties.ollamaUrl());
    if (!"http".equals(uri.getScheme())
        || !"127.0.0.1".equals(uri.getHost())
        || uri.getUserInfo() != null)
      throw new IllegalArgumentException("Ollama must use a fixed loopback HTTP endpoint");
  }

  /**
   * Checks model digests through {@code /api/tags}; tag names alone are not a compatibility
   * guarantee.
   */
  @Override
  public ModelProfile profile() {
    JsonNode tags = request("/api/tags", null, new Deadline(Duration.ofSeconds(5)), false);
    Map<String, String> digests = new HashMap<>();
    tags.path("models")
        .forEach(m -> digests.put(m.path("name").asText(), m.path("digest").asText()));
    if (!EMBED_DIGEST.equals(digests.get(properties.embeddingModel()))
        || !CHAT_DIGEST.equals(digests.get(properties.chatModel()))) {
      throw new SupportException(
          "MODEL_PROFILE_MISMATCH",
          503,
          "Install the pinned Nomic and Qwen3 models before answering questions.");
    }
    return new ModelProfile(EMBED_DIGEST, CHAT_DIGEST, tokens.digest(), prompts.digest());
  }

  /**
   * Calls Nomic through {@code /api/embed} with truncation disabled and validates all returned
   * vectors.
   */
  @Override
  public List<float[]> embed(List<String> inputs, Deadline deadline) {
    try {
      List<float[]> output = embedChecked(inputs, deadline);
      recordModelCall("embedding", "success");
      return output;
    } catch (RuntimeException error) {
      recordModelCall("embedding", "failure");
      throw error;
    }
  }

  private List<float[]> embedChecked(List<String> inputs, Deadline deadline) {
    if (inputs.isEmpty() || inputs.size() > 8)
      throw new IllegalArgumentException("Embedding batches require 1–8 inputs");
    for (String input : inputs)
      if (tokens.embeddingTokens(input) > 2000)
        throw new SupportException(
            "EMBED_INPUT_TOO_LARGE", 422, "An embedding input exceeds the checked token budget.");
    try (var lease = locks.acquire(DatabaseLocks.INFERENCE, deadline, true)) {
      requireHealthy();
      JsonNode result =
          request(
              "/api/embed",
              Map.of(
                  "model",
                  properties.embeddingModel(),
                  "input",
                  inputs,
                  "truncate",
                  false,
                  "keep_alive",
                  "5m"),
              deadline,
              true);
      JsonNode vectors = result.path("embeddings");
      if (!vectors.isArray() || vectors.size() != inputs.size()) throw modelInvalid();
      List<float[]> output = new ArrayList<>();
      for (JsonNode vector : vectors) {
        if (vector.size() != 768) throw modelInvalid();
        float[] values = new float[768];
        double norm = 0;
        for (int i = 0; i < 768; i++) {
          if (!vector.get(i).isNumber()) throw modelInvalid();
          values[i] = (float) vector.get(i).asDouble();
          if (!Float.isFinite(values[i])) throw modelInvalid();
          norm += values[i] * values[i];
        }
        if (norm <= 0) throw modelInvalid();
        output.add(values);
      }
      return output;
    }
  }

  /** Runs the bounded research-decision stage through a deadline-bound guarded model adapter. */
  @Override
  public ResearchDecision decide(Question question, List<Evidence> evidence, Deadline deadline) {
    try {
      ResearchDecision result = prompts.decide(question, evidence, new GuardedChatModel(deadline));
      recordModelCall("research", "success");
      return result;
    } catch (RuntimeException error) {
      recordModelCall("research", "failure");
      throw error;
    }
  }

  /** Drafts a cited answer with the same request deadline; no new timeout budget is created. */
  @Override
  public AnswerDraft answer(Question question, List<Evidence> evidence, Deadline deadline) {
    try {
      AnswerDraft result = prompts.answer(question, evidence, new GuardedChatModel(deadline));
      recordModelCall("answer", "success");
      return result;
    } catch (RuntimeException error) {
      recordModelCall("answer", "failure");
      throw error;
    }
  }

  /** Runs the final grounding review with the same request deadline and no repair loop. */
  @Override
  public boolean verify(
      Question question, AnswerDraft draft, List<Evidence> evidence, Deadline deadline) {
    try {
      boolean result = prompts.verify(question, draft, evidence, new GuardedChatModel(deadline));
      recordModelCall("grounding", result ? "success" : "rejected");
      return result;
    } catch (RuntimeException error) {
      recordModelCall("grounding", "failure");
      throw error;
    }
  }

  private void recordModelCall(String operation, String outcome) {
    metrics.counter("rag.model.calls", "operation", operation, "outcome", outcome).increment();
  }

  /**
   * Request-scoped LangChain4j ChatModel bridge. Supports only two-message, text-only,
   * schema-constrained protocol; no provider client defaults, tools, or automatic retries apply.
   */
  private final class GuardedChatModel implements dev.langchain4j.model.chat.ChatModel {
    private final Deadline deadline;

    private GuardedChatModel(Deadline deadline) {
      this.deadline = deadline;
    }

    @Override
    public dev.langchain4j.model.chat.response.ChatResponse doChat(
        dev.langchain4j.model.chat.request.ChatRequest request) {
      deadline.check();
      if (request.messages().size() != 2
          || !(request.messages().get(0)
              instanceof dev.langchain4j.data.message.SystemMessage system)
          || !(request.messages().get(1) instanceof dev.langchain4j.data.message.UserMessage user)
          || user.contents().size() != 1
          || !(user.contents().getFirst() instanceof dev.langchain4j.data.message.TextContent)
          || (request.toolSpecifications() != null && !request.toolSpecifications().isEmpty())
          || request.responseFormat() == null
          || request.responseFormat().jsonSchema() == null
          || !(request.responseFormat().jsonSchema().rootElement()
              instanceof dev.langchain4j.model.chat.request.json.JsonRawSchema schema))
        throw new IllegalArgumentException("Unsupported guarded prompt stage request");
      try {
        JsonNode result =
            generate(system.text(), user.singleText(), json.readTree(schema.schema()), deadline);
        return dev.langchain4j.model.chat.response.ChatResponse.builder()
            .aiMessage(dev.langchain4j.data.message.AiMessage.from(result.toString()))
            .build();
      } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
        throw new IllegalArgumentException("Invalid prompt stage schema", error);
      }
    }
  }

  /**
   * Builds the complete Qwen ChatML prompt, escaping data delimiters and closing the thinking
   * section so token accounting covers the actual non-thinking input.
   */
  public static String rawPrompt(String system, String data) {
    // Raw mode makes the complete Qwen ChatML input explicit and token-countable.
    return "<|im_start|>system\n"
        + system
        + "<|im_end|>\n<|im_start|>user\n"
        + data.replace("<", "\\u003c").replace(">", "\\u003e")
        + "<|im_end|>\n<|im_start|>assistant\n<think>\n\n</think>\n\n";
  }

  /**
   * Executes schema-constrained generation and checks completeness and local/runtime tokenizer
   * agreement.
   */
  private JsonNode generate(String system, String data, Object schema, Deadline deadline) {
    try {
      String prompt = rawPrompt(system, data);
      int inputTokens = tokens.generationTokens(prompt);
      // Reserve generation capacity and a small margin instead of relying on server truncation.
      if (inputTokens + 800 + 128 > properties.contextTokens())
        throw new SupportException(
            "PROMPT_TOO_LARGE",
            422,
            "Please narrow the question; the evidence will not fit safely.");
      try (var lease = locks.acquire(DatabaseLocks.INFERENCE, deadline, true)) {
        requireHealthy();
        JsonNode result =
            request(
                "/api/generate",
                Map.of(
                    "model",
                    properties.chatModel(),
                    "prompt",
                    prompt,
                    "raw",
                    true,
                    "stream",
                    false,
                    "format",
                    schema,
                    "keep_alive",
                    "5m",
                    "options",
                    Map.of(
                        "num_ctx",
                        properties.contextTokens(),
                        "num_predict",
                        800,
                        "temperature",
                        0.1,
                        "seed",
                        42)),
                deadline,
                true);
        if (!result.path("done").asBoolean()
            || result.path("done_reason").asText().equals("length")
            || !result.path("response").isTextual()) throw modelInvalid();
        int reported = result.path("prompt_eval_count").asInt(-1);
        if (reported < 1 || Math.abs(reported - inputTokens) > 2)
          throw new SupportException(
              "TOKENIZER_MISMATCH",
              503,
              "The local tokenizer and runtime disagree; do not use this model profile.");
        JsonNode parsed =
            json.reader()
                .with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .readTree(result.get("response").asText());
        if (parsed == null || !parsed.isObject()) throw modelInvalid();
        return parsed;
      }
    } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
      throw modelInvalid();
    }
  }

  /**
   * Sends bounded JSON requests within the remaining deadline. Uncertain inference completion
   * persists a quarantine instead of automatically retrying generation.
   */
  private JsonNode request(String path, Object body, Deadline deadline, boolean inference) {
    try {
      HttpRequest.Builder builder =
          HttpRequest.newBuilder(URI.create(properties.ollamaUrl() + path))
              .timeout(deadline.remaining());
      if (body == null) builder.GET();
      else
        builder
            .header("Content-Type", "application/json")
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    json.writer()
                        .with(
                            com.fasterxml.jackson.databind.SerializationFeature
                                .ORDER_MAP_ENTRIES_BY_KEYS)
                        .writeValueAsString(body)));
      var future = http.sendAsync(builder.build(), LimitedBodyHandler.bytes(1024 * 1024));
      HttpResponse<byte[]> response;
      try {
        response = future.get(deadline.remaining().toMillis(), TimeUnit.MILLISECONDS);
      } catch (TimeoutException | InterruptedException error) {
        future.cancel(true);
        if (inference) quarantine();
        if (error instanceof InterruptedException) Thread.currentThread().interrupt();
        throw new SupportException(
            "MODEL_TIMEOUT",
            504,
            "Inference timed out. Restart Ollama and reset model health before retrying.");
      }
      if (response.statusCode() != 200) {
        if (inference) {
          if (response.statusCode() >= 500) quarantine();
          else markHealthy();
        }
        throw new SupportException(
            "MODEL_UNAVAILABLE", 503, "The local model service could not complete this request.");
      }
      // A complete HTTP response makes inference completion known even if its body is invalid.
      if (inference) markHealthy();
      if (response.body().length > 1024 * 1024) throw modelInvalid();
      JsonNode result = json.readTree(new String(response.body(), StandardCharsets.UTF_8));
      if (result == null || !result.isObject()) throw modelInvalid();
      return result;
    } catch (SupportException error) {
      throw error;
    } catch (Exception error) {
      if (inference) quarantine();
      throw new SupportException(
          "MODEL_UNAVAILABLE", 503, "The local model service is unavailable.");
    }
  }

  /** Checks shared health and durably marks inference in progress before sending the request. */
  private void requireHealthy() {
    if (!Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT healthy FROM model_health WHERE singleton=true", Boolean.class)))
      throw new SupportException(
          "MODEL_QUARANTINED",
          503,
          "Restart Ollama and run model-reset; a previous inference has uncertain completion.");
    jdbc.update(
        "UPDATE model_health SET healthy=false,reason='Inference in progress' WHERE singleton=true");
    // A process crash leaves health false. A completed request restores it in request().
  }

  /**
   * Blocks subsequent application inference until an operator resolves uncertain runtime
   * completion.
   */
  private void quarantine() {
    jdbc.update(
        "UPDATE model_health SET healthy=false,reason='Inference completion uncertain' WHERE singleton=true");
  }

  private void markHealthy() {
    jdbc.update("UPDATE model_health SET healthy=true,reason='' WHERE singleton=true");
  }

  private static SupportException modelInvalid() {
    return new SupportException("INVALID_MODEL_OUTPUT", 502, "The model returned invalid output.");
  }
}

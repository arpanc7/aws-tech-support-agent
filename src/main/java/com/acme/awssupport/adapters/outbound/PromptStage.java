package com.acme.awssupport.adapters.outbound;

import com.acme.awssupport.application.Hashes;
import com.acme.awssupport.domain.SupportException;
import com.fasterxml.jackson.databind.*;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.*;
import dev.langchain4j.model.chat.request.json.*;
import dev.langchain4j.model.input.PromptTemplate;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * One reusable LangChain4j prompt stage with a packaged policy and a constrained JSON result.
 *
 * <p>Only reviewed resource text is compiled as a template. Serialized request/evidence data is
 * substituted once and is never evaluated as another template. The supplied model owns the shared
 * request deadline and inference safeguards; this stage adds no retries, memory, or tools.
 */
public final class PromptStage {
  private final String name;
  private final PromptTemplate system;
  private final PromptTemplate user;
  private final ObjectMapper json;
  private final String digest;

  /**
   * Loads immutable classpath templates; missing resources fail startup rather than weaken policy.
   */
  public PromptStage(String name, String systemResource, String userResource, ObjectMapper json) {
    this.name = name;
    this.json = json;
    String systemText = resource(systemResource);
    String userText = resource(userResource);
    system = PromptTemplate.from(systemText);
    user = PromptTemplate.from(userText);
    digest = Hashes.sha256(name + "\n" + systemText + "\n" + userText);
  }

  /** Calls exactly one model stage and rejects tool requests or non-object/trailing JSON output. */
  public JsonNode invoke(ChatModel model, String data, Object schema) {
    try {
      var request =
          ChatRequest.builder()
              .messages(
                  SystemMessage.from(system.apply(Map.of()).text()),
                  UserMessage.from(user.apply(Map.of("data", data)).text()))
              .responseFormat(
                  ResponseFormat.builder()
                      .type(ResponseFormatType.JSON)
                      .jsonSchema(
                          JsonSchema.builder()
                              .name(name)
                              .rootElement(
                                  JsonRawSchema.from(
                                      json.writer()
                                          .with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                                          .writeValueAsString(schema)))
                              .build())
                      .build())
              .build();
      var response = model.chat(request);
      if (response == null
          || response.aiMessage() == null
          || response.aiMessage().hasToolExecutionRequests()
          || response.aiMessage().text() == null) throw invalid();
      JsonNode result =
          json.reader()
              .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
              .readTree(response.aiMessage().text());
      if (result == null || !result.isObject()) throw invalid();
      return result;
    } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
      throw invalid();
    }
  }

  /** Content identity used to isolate answer caches after packaged prompt changes. */
  public String digest() {
    return digest;
  }

  private static String resource(String path) {
    try (var input = PromptStage.class.getResourceAsStream(path)) {
      if (input == null) throw new IllegalStateException("Missing prompt resource: " + path);
      String text = new String(input.readAllBytes(), StandardCharsets.UTF_8).strip();
      if (text.isEmpty()) throw new IllegalStateException("Empty prompt resource: " + path);
      return text;
    } catch (IOException error) {
      throw new IllegalStateException("Cannot load prompt resource: " + path, error);
    }
  }

  private static SupportException invalid() {
    return new SupportException("INVALID_MODEL_OUTPUT", 502, "The model returned invalid output.");
  }
}

package com.acme.awssupport.adapters.outbound;

import com.acme.awssupport.application.Hashes;
import com.acme.awssupport.domain.SupportException;
import com.acme.awssupport.domain.Types.*;
import com.fasterxml.jackson.databind.*;
import dev.langchain4j.model.chat.ChatModel;
import java.util.*;
import org.springframework.stereotype.Component;

/**
 * Typed selection and coverage stages implemented with LangChain4j templates and ChatModel calls.
 *
 * <p>AnswerQuestion orders these stages and validates selected IDs between them. Keeping the policy
 * orchestration outside the framework prevents hidden loops, automatic memory, or unconstrained
 * synthesis. Additional stages must explicitly preserve those application-level gates.
 */
@Component
public class EvidencePromptChain {
  private final ObjectMapper json;
  private final PromptStage selection;
  private final PromptStage coverage;

  public EvidencePromptChain(ObjectMapper json) {
    this.json = json;
    selection =
        new PromptStage(
            "evidence_selection",
            "/prompts/selection-system.txt",
            "/prompts/evidence-user.txt",
            json);
    coverage =
        new PromptStage(
            "evidence_coverage",
            "/prompts/coverage-system.txt",
            "/prompts/evidence-user.txt",
            json);
  }

  /** Fingerprints ordered stages, template contents and the pinned framework contract. */
  public String digest() {
    return Hashes.sha256(
        "langchain4j-1.19.0:stages-v1:" + selection.digest() + ":" + coverage.digest());
  }

  /** Runs selection and resolves model aliases only against the supplied evidence set. */
  public Selection select(Question question, List<Evidence> evidence, ChatModel model) {
    List<String> ids =
        java.util.stream.IntStream.range(0, evidence.size()).mapToObj(i -> "E" + (i + 1)).toList();
    // The schema constrains model output; application checks still enforce decision/ID consistency.
    Map<String, Object> schema =
        Map.of(
            "type",
            "object",
            "additionalProperties",
            false,
            "properties",
            Map.of(
                "decision",
                Map.of("type", "string", "enum", List.of("ANSWERABLE", "UNAVAILABLE", "CLARIFY")),
                "evidenceIds",
                Map.of(
                    "type",
                    "array",
                    "maxItems",
                    3,
                    "uniqueItems",
                    true,
                    "items",
                    Map.of("type", "string", "enum", ids))),
            "required",
            List.of("decision", "evidenceIds"));
    JsonNode result = selection.invoke(model, serialize(data(question, evidence)), schema);
    if (result.size() != 2
        || !result.path("decision").isTextual()
        || !result.path("evidenceIds").isArray()) throw modelInvalid();
    List<String> selected = new ArrayList<>();
    for (JsonNode id : result.get("evidenceIds")) {
      if (!id.isTextual() || !ids.contains(id.asText())) throw modelInvalid();
      selected.add(evidence.get(ids.indexOf(id.asText())).id());
    }
    return new Selection(result.get("decision").asText(), List.copyOf(selected));
  }

  /** Runs a separate coverage check with only the evidence accepted by application validation. */
  public boolean verify(Question question, List<Evidence> evidence, ChatModel model) {
    Map<String, Object> schema =
        Map.of(
            "type",
            "object",
            "additionalProperties",
            false,
            "properties",
            Map.of(
                "verdict",
                Map.of("type", "string", "enum", List.of("SUPPORTED", "UNSUPPORTED", "UNCERTAIN"))),
            "required",
            List.of("verdict"));
    JsonNode result = coverage.invoke(model, serialize(data(question, evidence)), schema);
    return result.size() == 1 && result.path("verdict").asText().equals("SUPPORTED");
  }

  /** Builds request-local evidence aliases; vectors and generated URLs never enter prompt data. */
  private Map<String, Object> data(Question question, List<Evidence> evidence) {
    return Map.of(
        "question",
        question,
        "evidence",
        java.util.stream.IntStream.range(0, evidence.size())
            .boxed()
            .map(
                i -> {
                  Evidence e = evidence.get(i);
                  return Map.of(
                      "id",
                      "E" + (i + 1),
                      "service",
                      e.service(),
                      "title",
                      e.title(),
                      "heading",
                      e.heading(),
                      "text",
                      e.text());
                })
            .toList());
  }

  private String serialize(Object data) {
    try {
      return json.writer()
          .with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
          .writeValueAsString(data);
    } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
      throw new IllegalArgumentException("Cannot serialize prompt data", error);
    }
  }

  private static SupportException modelInvalid() {
    return new SupportException("INVALID_MODEL_OUTPUT", 502, "The model returned invalid output.");
  }
}

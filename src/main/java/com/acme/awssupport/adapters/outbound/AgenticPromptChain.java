package com.acme.awssupport.adapters.outbound;

import com.acme.awssupport.application.Hashes;
import com.acme.awssupport.domain.SupportException;
import com.acme.awssupport.domain.Types.*;
import com.fasterxml.jackson.databind.*;
import dev.langchain4j.model.chat.ChatModel;
import java.util.*;
import org.springframework.stereotype.Component;

/**
 * Bounded research-decision, answer, and grounding stages implemented with LangChain4j.
 *
 * <p>This class only translates reviewed templates and constrained JSON. {@code AnswerQuestion}
 * owns the single optional retrieval round, validates every proposed search and evidence ID, and
 * decides whether a draft may be returned. No framework-managed tools, memory, retries, or loops
 * are enabled.
 */
@Component
public class AgenticPromptChain {
  private static final List<String> ACTIONS =
      List.of("ANSWER", "SEARCH_MORE", "CLARIFY", "UNAVAILABLE");
  private final ObjectMapper json;
  private final PromptStage research;
  private final PromptStage answer;
  private final PromptStage grounding;

  public AgenticPromptChain(ObjectMapper json) {
    this.json = json;
    research =
        new PromptStage(
            "research_decision",
            "/prompts/research-system.txt",
            "/prompts/evidence-user.txt",
            json);
    answer =
        new PromptStage(
            "grounded_answer", "/prompts/answer-system.txt", "/prompts/evidence-user.txt", json);
    grounding =
        new PromptStage(
            "grounding_review",
            "/prompts/grounding-system.txt",
            "/prompts/evidence-user.txt",
            json);
  }

  /** Fingerprints ordered stages, template contents, and the pinned framework contract. */
  public String digest() {
    return Hashes.sha256(
        "langchain4j-1.19.0:bounded-agent-stages-v1:"
            + research.digest()
            + ":"
            + answer.digest()
            + ":"
            + grounding.digest());
  }

  /** Returns one bounded action; search semantics remain the model's choice. */
  public ResearchDecision decide(Question question, List<Evidence> evidence, ChatModel model) {
    Map<String, Object> searchSchema =
        Map.of(
            "type",
            "object",
            "additionalProperties",
            false,
            "properties",
            Map.of(
                "query", Map.of("type", "string", "minLength", 1, "maxLength", 1000),
                "service",
                    Map.of(
                        "type",
                        "string",
                        "enum",
                        java.util.stream.Stream.concat(
                                java.util.stream.Stream.of(""),
                                com.acme.awssupport.domain.Types.SERVICES.stream().sorted())
                            .toList())),
            "required",
            List.of("query", "service"));
    Map<String, Object> schema =
        Map.of(
            "type",
            "object",
            "additionalProperties",
            false,
            "properties",
            Map.of(
                "action", Map.of("type", "string", "enum", ACTIONS),
                "searches", Map.of("type", "array", "maxItems", 3, "items", searchSchema)),
            "required",
            List.of("action", "searches"));
    JsonNode result = research.invoke(model, serialize(data(question, evidence)), schema);
    if (result.size() != 2
        || !result.path("action").isTextual()
        || !ACTIONS.contains(result.path("action").asText())
        || !result.path("searches").isArray()) throw modelInvalid();
    List<SearchRequest> searches = new ArrayList<>();
    try {
      for (JsonNode proposed : result.path("searches")) {
        if (proposed.size() != 2
            || !proposed.path("query").isTextual()
            || !proposed.path("service").isTextual()) throw modelInvalid();
        searches.add(
            new SearchRequest(proposed.path("query").asText(), proposed.path("service").asText()));
      }
    } catch (IllegalArgumentException error) {
      throw modelInvalid();
    }
    String action = result.path("action").asText();
    boolean validSearchAction =
        action.equals("SEARCH_MORE")
            ? !searches.isEmpty()
                && searches.size() <= 3
                && searches.stream().distinct().count() == searches.size()
            : searches.isEmpty();
    if (!validSearchAction) throw modelInvalid();
    return new ResearchDecision(action, searches);
  }

  /** Drafts one to six concise claims and resolves aliases only against supplied evidence. */
  public AnswerDraft answer(Question question, List<Evidence> evidence, ChatModel model) {
    List<String> aliases = aliases(evidence);
    Map<String, Object> claimSchema =
        Map.of(
            "type",
            "object",
            "additionalProperties",
            false,
            "properties",
            Map.of(
                // Ollama 0.33 rejects a 2,000-character grammar nested in six repeated claims.
                // Java enforces nonblank text and the same 2,000-character limit after parsing.
                "text", Map.of("type", "string"),
                "evidenceIds",
                    Map.of(
                        "type",
                        "array",
                        "minItems",
                        1,
                        "maxItems",
                        3,
                        "uniqueItems",
                        true,
                        "items",
                        Map.of("type", "string", "enum", aliases))),
            "required",
            List.of("text", "evidenceIds"));
    Map<String, Object> schema =
        Map.of(
            "type",
            "object",
            "additionalProperties",
            false,
            "properties",
            Map.of(
                "decision",
                Map.of("type", "string", "enum", List.of("ANSWER", "UNAVAILABLE")),
                "claims",
                Map.of("type", "array", "maxItems", 6, "items", claimSchema)),
            "required",
            List.of("decision", "claims"));
    JsonNode result = answer.invoke(model, serialize(data(question, evidence)), schema);
    if (result.size() != 2
        || !result.path("decision").isTextual()
        || !List.of("ANSWER", "UNAVAILABLE").contains(result.path("decision").asText())
        || !result.path("claims").isArray()) throw modelInvalid();
    List<DraftClaim> claims = new ArrayList<>();
    for (JsonNode claim : result.path("claims")) {
      if (claim.size() != 2
          || !claim.path("text").isTextual()
          || claim.path("text").asText().isBlank()
          || !claim.path("evidenceIds").isArray()) throw modelInvalid();
      List<String> ids = new ArrayList<>();
      for (JsonNode alias : claim.path("evidenceIds")) {
        if (!alias.isTextual() || !aliases.contains(alias.asText())) throw modelInvalid();
        ids.add(evidence.get(aliases.indexOf(alias.asText())).id());
      }
      if (ids.isEmpty() || ids.size() > 3 || ids.stream().distinct().count() != ids.size())
        throw modelInvalid();
      claims.add(new DraftClaim(claim.path("text").asText(), ids));
    }
    String decision = result.path("decision").asText();
    if ((decision.equals("ANSWER") && claims.isEmpty())
        || (decision.equals("UNAVAILABLE") && !claims.isEmpty())
        || claims.size() > 6) throw modelInvalid();
    return new AnswerDraft(decision, claims);
  }

  /** Performs one independent grounding review; there is no automatic revision or repair call. */
  public boolean verify(
      Question question, AnswerDraft draft, List<Evidence> evidence, ChatModel model) {
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
    Map<String, Object> reviewData = new LinkedHashMap<>(data(question, evidence));
    reviewData.put("draft", draftData(draft, evidence));
    JsonNode result = grounding.invoke(model, serialize(reviewData), schema);
    return result.size() == 1 && result.path("verdict").asText().equals("SUPPORTED");
  }

  /** Builds request-local evidence aliases; vectors and source URLs never enter model data. */
  private Map<String, Object> data(Question question, List<Evidence> evidence) {
    return Map.of("question", question, "evidence", evidenceData(evidence));
  }

  private List<Map<String, String>> evidenceData(List<Evidence> evidence) {
    return java.util.stream.IntStream.range(0, evidence.size())
        .boxed()
        .map(
            i -> {
              Evidence e = evidence.get(i);
              return Map.of(
                  "id", "E" + (i + 1),
                  "service", e.service(),
                  "title", e.title(),
                  "heading", e.heading(),
                  "text", e.text());
            })
        .toList();
  }

  private List<Map<String, Object>> draftData(AnswerDraft draft, List<Evidence> evidence) {
    Map<String, String> aliases = new HashMap<>();
    for (int i = 0; i < evidence.size(); i++) aliases.put(evidence.get(i).id(), "E" + (i + 1));
    return draft.claims().stream()
        .map(
            claim ->
                Map.<String, Object>of(
                    "text",
                    claim.text(),
                    "evidenceIds",
                    claim.evidenceIds().stream().map(aliases::get).toList()))
        .toList();
  }

  private List<String> aliases(List<Evidence> evidence) {
    return java.util.stream.IntStream.range(0, evidence.size())
        .mapToObj(i -> "E" + (i + 1))
        .toList();
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

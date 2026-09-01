# LangChain4j prompt stages and extension guide

Baseline 0.3 · 2026-08-31. Dependency: `dev.langchain4j:langchain4j-core:1.19.0`.

## What is integrated

We use LangChain4j `PromptTemplate`, `ChatRequest`, `ChatModel`, and structured response-format types. `EvidencePromptChain` exposes typed selection and coverage operations; `PromptStage` is the reusable stage executor. `AnswerQuestion` deliberately owns stage ordering and the validation between stages.

The HTTP transport remains our `OllamaModel.GuardedChatModel`, which preserves the raw Qwen template, deadlines, token parity, schema checks, bounded response bodies, inference locks and quarantine. This is an actual LangChain4j model invocation through a custom provider adapter, not the stock `langchain4j-ollama` client. We do not add Spring AI, AI Services proxies, automatic chat memory, automatic RAG, autonomous agents, or LangSmith/cloud tracing.

```mermaid
flowchart LR
    Q[Question + retrieved text] --> T[selection-system.txt + evidence-user.txt]
    T --> S[PromptStage / ChatModel]
    S --> IDs[Selection JSON]
    IDs --> J[Java validates IDs]
    J --> V[coverage-system.txt + selected text]
    V --> C[PromptStage / ChatModel]
    C --> Verdict[Coverage JSON]
    Verdict --> R[Java validity checks and excerpt rendering]
```

## Resources and inputs

| Resource | Purpose |
| --- | --- |
| `src/main/resources/prompts/selection-system.txt` | Choose the smallest sufficient set, or abstain/clarify |
| `src/main/resources/prompts/coverage-system.txt` | Check whether selected evidence answers the question as asked |
| `src/main/resources/prompts/evidence-user.txt` | Substitutes one `{{data}}` JSON value |

The data object contains the question, prior questions, filters and evidence aliases/service/title/heading/text. It contains neither database vectors nor model-generated citations. System policies and user templates are loaded once from the application classpath. They are not editable by HTTP callers and are not hot-reloaded.

Template variables embedded inside input strings remain literal strings. The raw transport additionally escapes angle brackets in data to preserve ChatML role boundaries. Keep original request/evidence separate from any derived stage output; a model-generated summary must never replace authoritative source text or become citation evidence.

## Adding an approved stage

1. Specify the new stage's purpose, typed inputs/output, rejection rules, place in the flow and maximum inference count in requirements/design/acceptance before implementation.
2. Add a reviewed system resource and, if needed, a data-only template under `resources/prompts`. Do not compile user text as a template or insert untrusted strings into system policy.
3. Create a `PromptStage` with a unique name and explicit JSON schema. Use the request-scoped guarded `ChatModel` supplied to the chain; do not instantiate a client that bypasses its deadline/lock/health checks.
4. Parse and validate the result before handing it to the next stage. Use a typed Java result at the boundary, not arbitrary assistant prose. Preserve the original question and source evidence separately.
5. Call the stage explicitly in `EvidencePromptChain` at the reviewed position. If it changes application policy, change the corresponding port/application contract instead of hiding policy inside an adapter. Update the ordered stage digest and policy version.
6. Recalculate prompt/output/deadline budgets, verify early exits and call limits, add meaningful contract tests, and run both automated and real-model evaluations. Update documentation and measured call counts.

For example, within a chain method that already has the guarded model, a new reviewed stage could consume a validated result:

```java
JsonNode next = nextStage.invoke(
    guardedModel,
    canonicalJsonOfOriginalQuestionEvidenceAndValidatedPriorResult,
    nextStageSchema);
```

This is an extension pattern, not an enabled third production stage. A normal successful request still has selection and coverage only. The current candidate-reuse fallback can run that pair once more; no framework retry or output repair is enabled.

## Identity, rollout and diagnostics

PromptStage hashes stage name and loaded template content. EvidencePromptChain hashes the ordered stage identities and framework/stage-contract version. `ModelProfile.answerProfile()` incorporates that digest; the embedding profile does not. Prompt-only changes therefore require a rebuild/restart and evaluation, but not re-ingestion. An embedding/tokenizer/extraction change does require a compatible corpus rebuild.

No model-written AWS prose is displayed. Future synthesis or query rewriting needs an explicit policy change and evaluation, not just an extra prompt file. Same-model verification remains fallible.

Tests cover literal template-like input, selection-to-coverage evidence isolation, malformed output without repair calls, chaining a prior structured result, and prompt identity changes without embedding identity changes. Ollama protocol tests and application policy tests retain the existing limits. Normal logs do not record prompt bodies or document text; the earlier walkthrough trace is an explicit local diagnostic artifact.

References: [LangChain4j core release](https://repo.maven.apache.org/maven2/dev/langchain4j/langchain4j-core/1.19.0/), [ChatModel API](https://docs.langchain4j.dev/tutorials/chat-and-language-models/), [AI Services and chaining alternatives](https://docs.langchain4j.dev/tutorials/ai-services/). This project intentionally uses the lower-level API to keep policy and side effects explicit.

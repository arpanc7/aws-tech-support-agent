# Prompt stages and bounded agent workflow

Baseline 0.4 · 2026-08-31.

LangChain4j provides the prompt and structured-call building blocks. The application deliberately does not enable an automatic agent executor, tools, chat memory, framework RAG, or output repair. **AnswerQuestion** owns the fixed workflow and **AgenticPromptChain** exposes three typed operations.

~~~mermaid
flowchart LR
    Q[Question + initial evidence] --> R[Research stage]
    R -->|ANSWER| A[Answer stage]
    R -->|SEARCH_MORE| S[Java: one local search round]
    S --> A
    R -->|CLARIFY / UNAVAILABLE| X[Fail closed]
    A --> J[Java alias validation]
    J --> G[Grounding stage]
    G -->|SUPPORTED| C[Server citations]
    G -->|UNSUPPORTED / UNCERTAIN| X
~~~

## Packaged templates

| Resource | Purpose |
| --- | --- |
| src/main/resources/prompts/research-system.txt | Decide ANSWER, SEARCH_MORE, CLARIFY, or UNAVAILABLE; bound searches to one through three |
| src/main/resources/prompts/answer-system.txt | Draft up to six concise claims, each linked to supplied evidence aliases |
| src/main/resources/prompts/grounding-system.txt | Review the complete draft against only its cited evidence |
| src/main/resources/prompts/evidence-user.txt | Substitute canonical JSON once as untrusted data |

Questions, history, prior structured results, and documentation text remain JSON data. They never become template source. Policies tell the model to ignore instructions inside that data. Vectors, internal URLs, database details, and credentials never enter a prompt.

## Fixed call bounds

An answered cache miss makes exactly one research call, one answer call, and one grounding call. A SEARCH_MORE decision adds a batched Nomic embedding call and at most three local database retrievals. It does not add another planner call. Clarify or unavailable can stop after research; an unavailable draft stops before grounding.

All calls share one deadline and one inference lock. A malformed result fails immediately. There is no framework retry, JSON repair, answer revision, recursive search, arbitrary tool invocation, or cloud fallback.

## Structured contracts

The research response schema requires an action and a search array. Java then enforces action/search consistency, distinct normalized queries, length limits, and the supported service allowlist.

The answer response schema requires a decision and claim array. Each claim contains text plus evidence aliases. Java maps aliases to the exact evidence supplied in that request and rejects unknown IDs, empty answers, duplicate IDs, too many claims, or uncited prose.

The grounding schema contains only SUPPORTED, UNSUPPORTED, or UNCERTAIN. The stage sees the question, complete draft, and cited passages. Only SUPPORTED can reach rendering, and Java still performs a final active-corpus and revocation check.

## Adding or changing a stage

1. Add or edit a reviewed UTF-8 resource under src/main/resources/prompts.
2. Define the smallest constrained JSON schema and a typed domain result.
3. Keep policy ordering in AnswerQuestion and transport details in OllamaModel.
4. Reuse the original Deadline; document exact maximum calls and stopping conditions.
5. Validate every model-selected alias or query in Java before it affects the next step.
6. Update the ordered stage digest and policy version.
7. Add tests for normal, unavailable, malformed, injection-like, and boundary outputs.
8. Run formatting, the full verification suite, and real-model smoke probes.

Do not hide application policy inside a prompt adapter. A stage that adds tools, account access, a network destination, persistent memory, retries, or another search round requires a new security and acceptance decision.

## Cache and corpus identity

PromptStage hashes the stage name and template contents. AgenticPromptChain hashes the ordered stage identities and stage-contract version. The answer profile includes this digest; the embedding profile does not. Prompt-only changes therefore require build/restart and answer re-evaluation, but no document re-embedding. Embedding, tokenizer, extraction, or chunking changes require a compatible corpus rebuild.

## Tests and diagnostics

Tests cover literal template-like input, bounded search output, alias mapping, answer-to-grounding evidence isolation, malformed output without repair calls, chaining a prior structured result, and prompt identity changes. Ollama protocol tests assert raw transport limits. Normal logs do not contain prompt bodies or document text. Historical trace artifacts are local diagnostics and are not committed.

# Repository guidance

This is a specification-driven Java 21 AWS documentation RAG application. This file guides coding agents; it is not loaded by the running application and does not replace user instructions.

## Start here

Read README.md, docs/requirements.md, docs/design.md, docs/low-level-design.md and docs/implementation-status.md before changing architecture. Read docs/prompt-chains.md before changing prompts or model calls. Update requirements/design/acceptance alongside behavior changes; distinguish implemented behavior from future work and unverified claims.

Entry point: AwsSupportApplication.main(). REST routing: ChatController. Policy orchestration: AnswerQuestion. Prompt stages: AgenticPromptChain and PromptStage. Local model transport: OllamaModel. UI: src/main/resources/static.

## Boundaries and safety

- Keep domain/ports independent of Spring, SQL, Jackson and LangChain4j. LangChain4j is confined to outbound adapters behind LocalModel.
- Keep the bounded agent policy: initial local retrieval, at most one model-requested search round with no more than three queries, grounded synthesis, and one grounding review. Every displayed claim must cite active stored evidence through server-owned citation IDs/URLs. No tool execution, account access, cloud fallback or query-time web fetches.
- Preserve shared deadlines, bounded prompts/bodies/queues, inference advisory locks, quarantine recovery, revocation checks and exact-cache scope. Similarity is not confidence; semantic cache entries contain candidates, not reusable final answers.
- Treat question/document/previous-stage content as untrusted data. Compile only packaged prompt templates. New stages must be bounded and explicitly ordered; update the stage digest and policy identity, and test rejected/malformed paths. No automatic output repair/retry loop.
- Embedding profile changes require compatible re-ingestion. Prompt-only changes must invalidate answer identity without changing vector-space identity.
- Corpus publication is atomic. Do not destroy existing generations, snapshots, volumes or user data during tests. Integration tests use their own schema.
- Keep runtime platform-neutral. Label ARM64/macOS convenience scripts and reference-machine measurements; never imply untested platforms have passed validation.

## Verification

Use ./scripts/maven spotless:apply for formatting, then ./scripts/maven spotless:check verify. This needs JDK 21 and Docker for Testcontainers, or the documented native PostgreSQL test URL. Load .env without echoing secrets and set RAG_TEST_DB_URL=jdbc:postgresql://127.0.0.1:54329/aws_support when deliberately using the existing local test database. Integration tests isolate their schema.

Run python3 scripts/smoke.py after changes affecting prompts, models, retrieval or grounding, with the updated app running. These developer probes are not the 200-case reviewed acceptance evaluation. Do not weaken assertions or change expected outcomes merely to pass a smoke run. Record failures and remaining limitations honestly.

Add useful class Javadoc and method contracts; avoid comments that only repeat syntax. Keep README, high/low-level diagrams, operations and verification status aligned. Generated artifacts, dependencies and diagnostic logs belong under ignored .cache/ or target/, not source directories.

Never print .env values, database passwords or user chat history. Do not enable prompt/document-body logging by default. No Git repository is assumed; inspect actual repository state before proposing Git operations.

# Implementation and verification status

Updated: 2026-09-01. The user authorized the local demo, LangChain4j integration, and bounded Agentic RAG workflow. This is **not a production release approval or a zero-hallucination claim**.

## Delivered

- Java 21 / Spring Boot application with domain/port boundaries, PostgreSQL adapters, Flyway migrations, operator commands, health, metrics, and a static chat UI.
- Native, local-only Ollama: Qwen3 4B Q4_K_M and Nomic Embed Text v1.5. Runtime/model/tokenizer artifacts are pinned. Qwen was observed loading all 37 layers onto Metal.
- 121 downloaded official pages, six service quotas satisfied, 1,338 embedded passages. Generation: `26b148e5-f1d0-4ba6-b147-e7b064eff68b`.
- HTML snapshots with checksums, structural extraction, tokenizer-based budgets, embedding checkpoints, hybrid exact-vector/lexical/identifier retrieval, atomic publication, rollback and persistent source revocation.
- Bounded research decisions, one optional local-search round, cited grounded synthesis, an independent grounding pass using the same local model, conservative abstention, no unchecked streaming, and safe text rendering with inspectable exact evidence quotes.
- Memory-bounded exact/embedding/retrieval caches and semantic candidate reuse with revalidation. Scope/history/profile/generation keys, request coalescing, bounded admission, deadlines and model quarantine on uncertain completion.
- Opt-in daily refresh with persistent retry backoff, previous-corpus retention on failure, freshness status, snapshot integrity checks, backup tooling and a restore runbook.
- OpenAPI contract, reproducible setup helpers, Maven wrapper, CI configuration, dependency-update configuration, and a generated CycloneDX SBOM (`target/bom.json` / `target/bom.xml`). An SBOM is an inventory, not a vulnerability assessment.

## EC2 definition regression verification (2026-09-01)

- Reproduced the website returning `CLARIFICATION_REQUIRED` for “What is EC2?” with **All supported services** selected. Initial all-service retrieval lacked a deterministic explicit-service scope, and the manifest omitted the EC2 overview page.
- `AnswerQuestion` now applies an explicit filter or infers exactly one supported service named in the current question. It does not infer from history; zero or multiple names remain unscoped. The resolved scope also constrains the optional follow-up search.
- Added the official `What is Amazon EC2?` concepts page and atomically published a 121-document/1,338-passage generation. All 121 sources completed; compatible prior embedding checkpoints were reused.
- Added unit regressions for inferred, explicit, and multi-service scope plus the exact no-filter EC2 real-model smoke case. Unit/policy/security/architecture/model-contract/local-metrics tests: **48 passed**; PostgreSQL integration tests: **6 passed**; total: **54**, none skipped.
- Real-model smoke: **9/9 passed**. The EC2 definition returned `ANSWERED` in 24.016 seconds and cited the locally stored `What is Amazon EC2?` overview. Other cache-miss answers took 17.044–31.051 seconds in this run; the exact cache hit took 8 ms. These observations miss the proposed warm-latency target and are not p95 measurements. Results: `.cache/bounded-agent/smoke-report-ec2-fix.json`.
- The in-app browser reloaded the updated corpus counts and submitted “What is EC2?” with **All supported services** selected. It rendered the grounded definition and inspectable AWS citations from generation `26b148e5`, with an exact validated cache hit on the browser request.

## Bounded Agentic RAG verification (2026-08-31)

- Replaced the extractive selection/coverage flow with three explicit `AgenticPromptChain` stages: research decision, cited answer draft, and grounding review.
- A research decision can request one through three local-corpus queries. Java batches their Nomic embeddings, executes at most one additional retrieval round in the pinned generation, and does not expose arbitrary tools or another planning loop.
- Java validates action consistency, query bounds, service filters, every claim/evidence alias, source state, and server-built citations. The UI now separates synthesized claims from exact stored evidence.
- Unit/policy/security/architecture/model-contract/local-metrics tests: **45 passed**. PostgreSQL integration tests: **6 passed** in an isolated native PostgreSQL schema. Total: **51**, none skipped.
- Updated real-model smoke: **8/8 passed** against Qwen3 4B and Nomic v1.5 with the 120-page/1,330-passage corpus. Answered cache misses completed in 8.1–18.9 seconds in the final warm run; the exact cache hit completed in 13 ms. A browser cold-model check completed in 30.0 seconds. Results: `.cache/bounded-agent/smoke-report-six-passages.json`.
- The real run exposed an Ollama 0.33 grammar limit for a nested 2,000-character repetition. The response schema now leaves that string unbounded at the transport layer and retains the 2,000-character Java check. Deterministic 4xx/invalid responses now release model health; only uncertain completion and server failures quarantine it.
- The in-app browser loaded the UI, reported 120 documents/1,330 passages, submitted an S3 question, rendered GROUNDED_SYNTHESIS claims, opened server-owned AWS documentation links, and exposed exact stored evidence in the provenance panel with no browser console errors. Readiness was UP.
- Local operations now write Spring logs and an atomic privacy-safe `rag.*` metrics JSON snapshot under the system temporary directory. Model counters distinguish embedding, research, answer, and grounding success/rejection/failure.

## Historical LangChain4j migration verification (2026-08-31)

- Added pinned `langchain4j-core:1.19.0` with reusable PromptStage and the then-current typed extractive operations. A custom guarded ChatModel preserved existing Ollama raw HTTP behavior. This table records the completed migration baseline before the bounded-agent change above.
- Moved policy prompts to packaged resources and added a template/stage digest to answer identity. Embedding identity, database schema, and corpus generation remained unchanged.
- Added high-level component/ingestion diagrams, low-level class/sequence/ER diagrams, a prompt extension guide, platform matrix and root AGENTS.md. Corrected earlier design descriptions of unimplemented tables, lifecycle states and scheduling mechanisms.
- The product is not M1-specific. Mac ARM64/16 GB remains the reference environment. Linux/WSL2/other hosts have provisioning guidance but have not passed full native-library, model, performance, security and offline validation.

| Check | Observed result |
| --- | --- |
| Unit/policy/security/architecture/model-contract tests | **42 passed** (including 5 prompt-stage tests and 1 new guarded protocol/deadline test) |
| PostgreSQL integration tests | **6 passed** against native PostgreSQL 17.11/pgvector 0.8.6 in an isolated schema |
| Total automated tests | **48 passed**, none skipped; `.cache/langchain4j/verification.log` |
| Real-model smoke | **8/8 passed** against the restarted Java app; `.cache/langchain4j/smoke-report.json` |
| Payload comparison | A separate Lambda trace had identical embedding, selection and coverage request JSON to the pre-migration capture; same selected claim. `.cache/langchain4j/wire-comparison.json` |
| Diagrams | All **9 Mermaid diagrams** passed parser validation; `.cache/langchain4j/diagrams.log` |
| Javadoc | Regenerated with doclint enabled (missing-tag warnings excluded); no errors |
| Dependency inventory | Regenerated CycloneDX SBOM: **66 components**, including LangChain4j core; not a vulnerability/license approval |
| Runtime | Updated Java app restarted on loopback 8080; readiness UP; existing Ollama/database/corpus retained |

Post-migration smoke observations:

| Probe | Outcome | Wall time |
| --- | --- | ---: |
| s3-deny | ANSWERED | 15.898 s |
| s3-deny-repeat | ANSWERED, EXACT | 0.009 s |
| s3-paraphrase | ANSWERED, candidates reverified | 11.225 s |
| iam-deny | ANSWERED | 10.748 s |
| lambda-timeout | ANSWERED | 8.906 s |
| absent-product | INFORMATION_NOT_AVAILABLE | 10.460 s |
| live-state | INFORMATION_NOT_AVAILABLE | 0.010 s |
| region-scope | CLARIFICATION_REQUIRED | 0.006 s |

These are single developer probes, not held-out quality or p95 evidence. The separate comparison trace ran after smoke testing and benefited from runtime caches; its timing is not an optimization claim. No fresh corpus download or restore audit was performed for this prompt-only change. Original ingestion/restore results below remain historical evidence.

The first sandboxed test attempt failed because Mockito could not attach its test JVM and local HTTP access was restricted. Re-running with the required local process/network permissions passed; no test assertions were weakened. Docker remains unavailable on this host, so the Docker/remote-CI path is still unverified.

## Original baseline verification (2026-08-30)

| Check | Observed result |
| --- | --- |
| Java unit/policy/security/architecture/model-contract tests | 36 passing |
| PostgreSQL integration tests | 6 passing against native PostgreSQL 17.11 with pgvector 0.8.6; unique disposable test schema |
| Full source validation | 120 pages passed extraction/token limits |
| Full ingestion | 120 documents, 1,330 passages, complete generation published |
| Unchanged refresh | `published=false`; generation retained, compatible embedding checkpoints reused |
| Backup restoration | Restored into a new disposable database; 120 snapshot checksums and lexical retrieval parity passed; temporary database removed |
| Real-model developer smoke probes | 8/8 passed; see table below |
| Browser walkthrough | Rendered corpus counts, S3 service filter, question submission, stored excerpts, citations, provenance, and exact cache-hit metadata |
| Docker image architecture | Registry manifest confirms Linux arm64 and amd64; multi-platform digest pinned |

The full Docker/Testcontainers path could not run because this Mac's Docker Desktop failed during its own startup. The database integration suite ran against the real native PostgreSQL/pgvector fallback instead. CI is configured to use Testcontainers, but has not run in a remote CI environment.

Original baseline smoke run (`.cache/smoke-report-stable.json`):

| Probe | Outcome | Wall time |
| --- | --- | ---: |
| S3 explicit bucket-policy deny | ANSWERED | 12.951 s |
| Identical S3 request | ANSWERED, EXACT cache | 0.008 s |
| S3 paraphrase | ANSWERED, semantic candidates rechecked | 11.397 s |
| IAM explicit deny | ANSWERED | 11.468 s |
| Lambda maximum timeout | ANSWERED | 9.772 s |
| Invented/absent product | INFORMATION_NOT_AVAILABLE | 10.495 s |
| Current live outage | INFORMATION_NOT_AVAILABLE | 0.005 s |
| Explicit unsupported region scope | CLARIFICATION_REQUIRED | 0.004 s |

These are single observations on this Mac, not p50/p95 measurements. The model was already warm. Claims/quote equality was checked by the smoke script; this does not independently prove relevance or completeness. The eight probes were used during development, so they are **not held-out quality evaluation**. Earlier runs contained false abstentions; the results were preserved in `.cache/` and led to retrieval and prompt corrections rather than changed expectations.

## Implementation decisions and deviations

- **Native PostgreSQL fallback:** project-local Postgres.app binaries under `.tools/` and data under `data/postgres`; no global Docker settings were changed. Docker Compose remains available for a healthy Docker installation.
- **Extraction v2:** split long lists at item/child boundaries and tables at rows with repeated headers. Preserve code; reject oversized indivisible examples. Two oversized example pages were replaced by related reference pages while retaining all quotas. The manifest was selected by the implementation agent and validated mechanically, not independently reviewed by a company domain expert.
- **Retrieval v2:** English stemming/stop-word handling with disjunctive lexical candidates, reciprocal rank fusion, and separate exact-identifier hits. Generic acronyms such as AWS/IAM are excluded from identifier boosts. A regression test covers the Lambda answer being displaced by generic AWS text.
- **Current answer policy:** use request-local evidence aliases that resolve only to supplied stored IDs and canonical map serialization for reproducible prompts. The model may request one bounded additional local search round, then drafts cited claims and performs a separate grounding review. Synthesized prose is enabled with residual same-model validation risk.
- **Region/version filtering:** supplied region/version values trigger clarification because the baseline corpus cannot reliably establish those scopes. It does not pretend to implement granular regional/version-aware retrieval.
- **Scheduling:** opt-in while Java runs, not a macOS daemon. Database session locks replace a lease-expiry protocol locally. Batches release model capacity between calls; strict chat-priority scheduling is not implemented.
- **UI cancellation:** stops waiting without claiming guaranteed server-side inference cancellation. Inference timeouts quarantine the runtime to avoid uncertain concurrent work.
- **Retention:** immutable generations/checkpoints remain on disk; operator-managed pruning only. No automatic garbage collection.

## Remaining release gates

The local demo is usable; the full production acceptance target in [acceptance.md](acceptance.md) is not complete.

1. Build and independently review the 200-case dataset; measure retrieval coverage, answer usefulness, abstention quality, adversarial behavior and dangerous semantic-neighbor cases on held-out data. Similarity thresholds remain initial settings, not calibrated guarantees.
2. Expand resilience tests for process death during inference/ingestion, deadline/queue pressure, cancellation, TTL/eviction, publication races, and failure/restart scheduling. The existing suite does not cover every A-01–A-27 scenario.
3. Measure sustained load, cold/warm p50/p95 and total unified memory; test physically disconnected operation. The app's request path uses local endpoints and local assets, but a full offline network audit was not performed.
4. Run the pinned Docker path and CI, dependency vulnerability/license review, and a company security review. AWS documentation remains separately owned content and is not redistributed in this source tree.
5. Add company identity, tenant isolation, TLS, secret management, least-privilege migration/runtime roles, distributed capacity management, production observability, HA and independently audited restore procedures before shared deployment.

A same-model second pass is not an independent truth oracle. Verbatim excerpts eliminate model-written factual sentences, but can still be irrelevant, incomplete, maliciously sourced, or stale. Keep these limits visible when demonstrating the system.

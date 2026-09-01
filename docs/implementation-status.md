# Implementation and verification status

Updated: 2026-08-31. The user authorized the local demo and LangChain4j integration. This is **not a production release approval or a zero-hallucination claim**.

## Delivered

- Java 21 / Spring Boot application with domain/port boundaries, PostgreSQL adapters, Flyway migrations, operator commands, health, metrics, and a static chat UI.
- Native, local-only Ollama: Qwen3 4B Q4_K_M and Nomic Embed Text v1.5. Runtime/model/tokenizer artifacts are pinned. Qwen was observed loading all 37 layers onto Metal.
- 120 downloaded official pages, six service quotas satisfied, 1,330 embedded passages. Generation: `21fa20b5-5b91-49ae-9c1e-b146dc23c0a0`.
- HTML snapshots with checksums, structural extraction, tokenizer-based budgets, embedding checkpoints, hybrid exact-vector/lexical/identifier retrieval, atomic publication, rollback and persistent source revocation.
- Strict excerpt-only answers, evidence ID/schema checks, independent coverage pass using the same local model, conservative abstention, no unchecked streaming, and safe text rendering.
- Memory-bounded exact/embedding/retrieval caches and semantic candidate reuse with revalidation. Scope/history/profile/generation keys, request coalescing, bounded admission, deadlines and model quarantine on uncertain completion.
- Opt-in daily refresh with persistent retry backoff, previous-corpus retention on failure, freshness status, snapshot integrity checks, backup tooling and a restore runbook.
- OpenAPI contract, reproducible setup helpers, Maven wrapper, CI configuration, dependency-update configuration, and a generated CycloneDX SBOM (`target/bom.json` / `target/bom.xml`). An SBOM is an inventory, not a vulnerability assessment.

## LangChain4j integration verification (2026-08-31)

- Added pinned `langchain4j-core:1.19.0` with reusable PromptStage and typed EvidencePromptChain operations. A custom guarded ChatModel preserves existing Ollama raw HTTP behavior. No automatic agent, RAG, memory, tool or repair/retry subsystem is enabled.
- Moved policy prompts to packaged resources; added a template/stage digest to answer identity and advanced policy to `extractive-v5:retrieval-v2`. Embedding identity, database schema, corpus generation and strict excerpt rendering remain unchanged.
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
- **Original answer policy v4 (now v5 with prompt-content identity):** use short, request-local evidence aliases that resolve only to supplied stored IDs and canonical map serialization for reproducible prompts across JVM restarts. Prefer the smallest sufficient excerpt set. Coverage checks evaluate the question actually asked, while still requiring relevant qualifications and complete prerequisites for requested procedures. No synthesized mode is implemented.
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

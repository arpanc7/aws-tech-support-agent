# Acceptance and implementation plan

Status: release criteria; implementation verification is in progress · Baseline: requirements/design 0.3

The following are release criteria, not a claim that every scenario passes. Automated unit/integration tests and real-model smoke probes now exist. See [implementation status](implementation-status.md) for actual results. The 200-case human-reviewed quality benchmark has not been completed.

## 1. Requirement traceability

| Case | Requirements | Scenario and expected result |
| --- | --- | --- |
| A-01 Local operation | R-02–R-05, B-01 | After explicit setup/ingestion, disconnect external networking. Java backend, local embeddings, retrieval, and UI still work; no cloud calls or missing CDN assets. |
| A-02 Evidence-backed question | R-06, B-04–B-06 | An answerable fixture returns applicable local sources. Every excerpt is an exact stored span; every synthesized claim, if enabled, has complete supporting evidence. |
| A-03 Missing information | R-07, B-05 | Ask about a service absent from the fixture corpus. Return exactly “Information is not available in the local documentation.” No factual filler. |
| A-04 Missing detail | R-07–R-08, B-05–B-06 | A page mentions the service but lacks the requested limit or remediation. Relevant keywords must not cause an unsupported answer. |
| A-05 Invalid citation | R-08, B-06 | A stub model fabricates a span ID/URL, cites another generation, or changes quoted text. Reject the candidate. |
| A-06 Unsupported claim | R-08, B-06 | A draft adds one unsupported step or changes a numerical limit while citing a real page. Reject the entire draft. Valid provenance alone must not pass. |
| A-07 Injection and rendering | R-08, B-07, B-12 | Malicious user/source text requests policy overrides, tool calls, HTML execution, or secret disclosure. No tools/network actions occur; dangerous output is not rendered; unchecked text never reaches the UI. |
| A-08 Applicability/ambiguity | B-04, B-08 | Conflicting region/version/service or “what about that one?” without a clear referent prompts clarification; no arbitrary choice. |
| A-09 Context integrity | B-08–B-09 | Different prior questions with identical current wording do not share an answer-cache key. Prior user assertions cannot become cited facts. |
| A-10 Exact hot key | R-09, B-09 | Repeat an identical scoped question. Serve the validated cached result without a new model call after checking corpus/policy validity. Concurrent requests coalesce safely. |
| A-11 Semantic reuse | R-09, B-09 | A paraphrase may reuse candidate source IDs but still checks current question coverage and runs the answer policy. Cache-only similarity never returns a prior final answer. |
| A-12 Dangerous similarity | R-08–R-09 | Contrast enable/disable, allow/deny, public/private, S3/S3 Glacier, changed region, changed numbers, and quoted error codes. No unsafe exact match or direct semantic answer replay. |
| A-13 Refresh consistency | B-02–B-03, B-09 | Query while publishing a new corpus. Each response/citation set uses one pinned generation. New requests use the new generation; old cache namespace is not reused. |
| A-14 Revocation | B-02, B-06, B-09 | Withdraw a source for security reasons after a cache hit or during generation. The final revocation check prevents its evidence from being served. |
| A-15 Ingestion interruption | B-03 | Interrupt download, embedding, or publication. Active corpus stays usable. Restart resumes checkpoints without duplicate published chunks. |
| A-16 Ingestion data quality | B-02–B-03, B-12 | Empty pages, oversized files, unexpected redirects, corrupt HTML, broken qualifiers, wrong embedding dimensions, and nonfinite vectors fail visibly. No incomplete generation is published. |
| A-17 Dependency failure | B-10 | Stop Ollama or PostgreSQL, remove a model, or start with no corpus. Return typed unavailable errors; never an invented answer or cached result whose validity cannot be checked. |
| A-18 Load and cancellation | B-10–B-11 | Exceed queue limits and disconnect clients. Reject overflow, honor deadlines, clean single-flight waiters, and do not release model capacity while timed-out work is still running. |
| A-19 Cache boundaries | B-09–B-11 | Fake-clock TTL expiry, max-weight eviction, policy/model changes, differing filters, and cache restart all preserve correctness. Empty/failed answers are not cached. |
| A-20 Local security | B-01, B-12 | Cross-origin/invalid-Host requests fail; ports are not LAN accessible; chat cannot fetch URLs; cloud inference is disabled; logs contain no prompts or secrets by default. |
| A-21 UI behavior | R-05, B-07, B-13 | Keyboard-only flow can ask, inspect local evidence, clear, and stop. Unavailable/clarification/error states are distinct. No previous answer is mistaken for the pending answer. |
| A-22 Restore | B-02–B-03 | Restore database and snapshots into a clean local environment, validate generation/model compatibility, and reproduce retrieval on the frozen corpus. |
| A-23 Architecture | R-02, B-14 | Domain dependency rules pass; model, retrieval, policy, and cache adapters can be replaced with test doubles without changing domain code. |
| A-24 Preprocessing and token budgets | B-04, B-15 | HTML entities, Unicode, warnings, code indentation, JSON, `s3:GetObject`, ARNs, and negations preserve meaning. Embedding and chat token budgets use their respective pinned tokenizers; mismatches and overflow fail visibly. |
| A-25 Refresh scheduling | B-03, B-11, B-16 | With a fake clock, simulate sleep/restart, offline periods, concurrent manual triggers, lease expiry, and queued chat. Coalesce missed runs, prevent overlap, yield inference between batches, and retain the healthy corpus on failure. |
| A-26 Incremental updates | B-02–B-03, B-09, B-16 | A `304`/unchanged refresh performs zero embeddings and keeps the generation/cache namespace. A changed chunk reuses only compatible unchanged embedding inputs. Addition, removal, metadata changes, and rollback retain correct provenance. |
| A-27 Seed coverage and freshness | R-04, B-02, B-13 | Full demo has at least 120 distinct substantive pages with service quotas and labeled coverage; 30-page slice is marked partial. Failed source checks do not advance freshness, and stale/latest-state questions receive the specified warning/abstention behavior. |

## LangChain4j and portability acceptance additions

| Case | Requirements | Scenario and expected result |
| --- | --- | --- |
| L-01 Template boundary | R-10, B-12, B-17 | Template-like and ChatML-like input stays literal data; fixed policy cannot be replaced through variables. |
| L-02 Stage isolation | R-10, B-06, B-18 | Coverage receives only validated selection, with the original question and a distinct policy; no automatic memory or tools. |
| L-03 Bounded chain | B-10, B-11, B-18 | Malformed output does not trigger repair calls. Selection/coverage share deadlines, and candidate fallback is bounded to one retry. |
| L-04 Prompt identity | B-09, B-17 | Changed packaged prompt content changes answer identity while leaving the embedding profile compatible. |
| L-05 Framework boundary | B-14 | Domain/ports have no LangChain4j dependency; actual model calls use the guarded adapter. |
| L-06 Wire compatibility | B-15, B-18 | Raw prompt role delimiters, deterministic JSON, schema, options and token parity remain checked after template composition. |
| L-07 Documentation and platform | R-03, R-11 | High/low-level diagrams match actual classes/schema; setup distinguishes tested platforms from intended targets. Validate native libraries and smoke/performance/offline behavior on each new target before certification. |

These add to, not replace, A-01–A-27. Automated checks do not certify an untested OS or eliminate the human-reviewed quality gate.

## 2. Evaluation dataset

Build a reviewed, versioned set of at least 200 cases tied to a frozen corpus generation. Keep 50 development/calibration cases separate from 150 held-out acceptance cases. Group paraphrases and questions derived from the same source fact in the same split to prevent leakage.

The held-out set should contain at least:

| Category | Count |
| --- | ---: |
| Answerable AWS support questions, including multi-source cases | 50 |
| Unanswerable questions, including absent services and missing details in present services | 40 |
| Ambiguous, contradictory, or version/region-sensitive questions | 20 |
| Prompt injection and output-handling attacks | 20 |
| Cache-sensitive paraphrases and near-opposite questions | 20 |

Each case records question/history/filters, expected decision, acceptable evidence span IDs, required qualifications, and prohibited claims. Human reviewers label evidence before inspecting model answers where practical. Keep test fixtures small and redistributable; store source provenance and usage restrictions separately.

Measure retrieval independently from generation. For multi-source cases, score required-evidence coverage, not just whether any relevant page appeared. Calibrate similarity/evidence thresholds only on development cases. Any change to model, prompt, corpus, preprocessing, thresholds, or cache equivalence policy triggers the relevant evaluation again.

## 3. Proposed release gates

| Metric / invariant | Gate |
| --- | --- |
| Citation integrity | 100% of displayed citations resolve to the pinned, non-revoked source spans |
| Strict excerpt fidelity | 100% verbatim evidence text; zero model-written AWS prose |
| Retrieval evidence recall@8 | At least 90% mean required-evidence recall across answerable held-out questions |
| Appropriate answer rate | At least 80% of answerable held-out questions answered correctly with necessary qualifications; prevents “always abstain” from passing |
| Unanswerable handling | At least 95% correct abstention, with zero observed substantive fabricated answers; other outcomes are reviewed and reported |
| Synthesized mode, if proposed | Zero observed unsupported claims or critical errors on held-out cases and human review; otherwise keep the mode disabled |
| Semantic cache correctness | Zero wrong-answer replays across the cache-sensitive suite; all hits preserve current-question validation |
| Safety/resilience invariants | All applicable A-01 through A-27 automated scenarios pass; all security-critical findings resolved |
| Performance | Report p50/p95 cold/warm latency and memory; compare against proposed targets, document misses before deciding scope |

A finite test set with zero observed errors does not prove zero real-world hallucinations. Report denominators and confidence intervals for quality rates. Safety gates and availability/usefulness metrics must both be reported; do not tune the system to abstain on everything or quietly relax guardrails to meet latency.

## 4. Test layers

- Unit/property tests: stable chunk/span identity, normalization, generation isolation, citation validation, prompt budgets, cache key separation, TTL/eviction, deadline and retry policy. Prefer behavior-based tests over implementation mirrors.
- Integration tests: real PostgreSQL/pgvector with Testcontainers, migrations, lexical/vector search, publication races, rollback, restoration, and query consistency. Test exact search before ANN optimizations.
- Model adapter contract tests: local HTTP stub for invalid JSON/schema, truncated output, wrong dimensions, tool calls, timeouts, disconnects, and unsupported model options.
- Real-model evaluation: explicit local profile using pinned Ollama/model digests and frozen corpus; separate from fast CI. Do not claim mock tests measure hallucination quality.
- UI end-to-end tests: accessible interaction, evidence display while offline, safe rendering, busy/unavailable/error states, and absence of unchecked streaming.
- Security/load tests: prompt injection, SSRF redirects in ingestion, Host/Origin policy, queue pressure, cancellation, cache poisoning, and revocation races.

CI must run compilation, formatting/static analysis, unit/architecture tests, dependency checks, and database integration tests. Real-model evaluations are a recorded release gate even if not run on every pull request. Failures remain visible; they are not silently skipped because a developer machine lacks the model.

## 5. Implementation sequence after design approval

| Milestone | Deliverable | Exit condition |
| --- | --- | --- |
| M0 — Baseline | Resolve open decisions, approve specs, choose exact compatible versions/licenses, prepare seed manifest and evaluation labels | Reviewed design, accepted targets, dependency/model lock manifest |
| M1 — Skeleton | Java module structure, REST contract/OpenAPI, health/config, local dependency setup, initial static UI shell | Build/architecture checks pass; missing dependencies fail clearly |
| M2 — Corpus | Fetch/extract/chunk/embed pipeline, source snapshots, schema, atomic generation publication | Provenance/resume/rollback and tokenization tests pass on the 30-page IAM/S3/EC2 slice |
| M3 — Retrieval | Hybrid retrieval, filters, evidence selection, retrieval diagnostics | Retrieval evaluation reaches the agreed gate without generation |
| M4 — Safe answer | Strict excerpt selection, evidence gates, citation rendering, abstention/error/clarification states | Grounding and adversarial acceptance cases pass |
| M5 — Cache and resilience | Exact answer/embedding/retrieval caches, semantic candidate reuse, admission control, deadlines, incremental refresh scheduler, metrics | Cache separation, concurrency, failure, revocation, and incremental/scheduled refresh cases pass |
| M6 — Demo release | Completed UI, 120-page six-service corpus, offline walkthrough, restore runbook, measured performance, optional evaluated synthesis mode | Coverage and release gates reviewed; known limitations documented |

Each milestone gets a small reviewed change set. Specification changes precede incompatible code changes. Expanding corpus size, enabling synthesized prose, directly reusing semantic answers, or sharing the service beyond localhost requires an explicit decision and updated acceptance coverage.

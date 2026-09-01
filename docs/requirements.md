# Requirements: AWS Tech Support Agent

Version: 0.4 bounded-agent baseline · Date: 2026-08-31 · Agentic RAG implementation authorized by user

`MUST` denotes a mandatory behavior. `SHOULD` denotes a default that requires a documented reason to change. Limits are starting configurations; authorization to implement is not acceptance of unmeasured quality claims. See [implementation status](implementation-status.md) for completed work and remaining release gates.

## 1. Product objective

Help a user understand or troubleshoot AWS products using information in an explicitly downloaded, locally indexed documentation corpus. The system must expose its sources and must decline to answer when the local evidence is insufficient. It is a documentation assistant, not an AWS operator: it cannot inspect an account or execute remediation.

## 2. Confirmed requirements

| ID | Requirement |
| --- | --- |
| R-01 | Use specification-driven development. Review requirements, design, and acceptance criteria before implementation. |
| R-02 | Implement the backend and ingestion pipeline in Java with production engineering practices and a path to distributed deployment. |
| R-03 | Run locally with a lightweight, open-source model on a compatible host. Mac M1 Pro/16 GB is the measured reference environment, not a product restriction; other platforms require native dependency and performance verification. |
| R-04 | Download approved AWS documentation locally and build persistent local vector storage. |
| R-05 | Provide a lightweight chat UI for questions about AWS products/services. |
| R-06 | Ground substantive AWS statements in the available corpus and present citations to supporting evidence. |
| R-07 | When sufficient information is absent, return **“Information is not available in the local documentation.”** Do not fill gaps using model knowledge. |
| R-08 | Apply explicit, testable guardrails against unsupported output, prompt injection, and invalid citations. |
| R-09 | Provide local caching for hot/repeated questions and similar questions without weakening grounding or correctness. |
| R-10 | Use LangChain4j prompt templates and model APIs for explicit, extensible prompt stages while preserving the application's evidence-validation chain and guarded local transport. |
| R-11 | Maintain high- and low-level design diagrams, prompt-extension guidance, portable setup instructions, and repository guidance in AGENTS.md. |
| R-12 | Let the local model decide whether the initial evidence is sufficient or whether one additional local search round is needed, while keeping execution bounds in Java. |

## 3. Current local scope

These defaults bound the current local implementation. Production expansion requires updated design and acceptance evidence.

| Area | Current baseline |
| --- | --- |
| Users | One trusted local user; English questions and documentation |
| Coverage | IAM, S3, EC2, VPC, Lambda, and CloudWatch; launch with IAM/S3/EC2 first |
| Corpus | Selected minimum: 120 curated, distinct HTML documentation pages across six services; 30 pages for the first IAM/S3/EC2 implementation slice; at most 20,000 chunks in the initial performance profile |
| Document sources | Explicit manifest of official AWS documentation URLs; approved local snapshots of those pages |
| UI | Browser UI served by the Java backend; no separate frontend server or cloud assets |
| Hardware | Tested reference: Mac ARM64 with 16 GB unified memory. Portable runtime targets and setup limits are documented in platform-setup.md; reserve at least 10 GB free disk initially. |
| Runtime | Host Java/Ollama with Docker or native PostgreSQL/pgvector. Reference host uses native PostgreSQL because Docker Desktop failed to start. |
| Freshness | Manual refresh plus configurable daily incremental refresh while the app is running, online, and idle; source-check and snapshot dates visible; no guarantee of current AWS behavior |
| Answer policy | Bounded grounded synthesis: each claim cites active local evidence and the complete draft passes a separate grounding review |
| Similar queries | Reuse retrieval candidates and revalidate against the current question; do not serve a cached answer solely because vectors are similar |

Initial exclusions: AWS SDK calls or account access, shell execution, autonomous actions, live web search during answering, pricing/current outage guarantees, PDF/OCR ingestion, arbitrary uploads, fine-tuning, multi-tenancy, SSO, Kubernetes, and multi-region availability. Future work must not silently enter this scope.

## 4. Behavioral requirements

| ID | Mandatory behavior |
| --- | --- |
| B-01 | Network access for setup/refresh must be separate from the answer path. Once dependencies and models are installed, queries MUST use local resources only. |
| B-02 | Every published document MUST retain canonical URL, title, service, content hash, fetch time, and corpus generation. Unknown source version/date MUST stay unknown. |
| B-03 | Ingestion MUST be repeatable and resumable. Failed or incomplete refreshes MUST NOT replace a healthy active corpus. |
| B-04 | Retrieval MUST combine semantic retrieval with lexical matching for AWS error codes, API names, and identifiers. Explicit service/version filters MUST apply before evidence selection. When no service filter is selected and the current question names exactly one supported service, Java MUST use that service as the retrieval scope; zero or multiple service names remain unscoped. |
| B-05 | Evidence sufficiency MUST be assessed before answering. A nearest neighbor, high similarity, valid JSON, or model confidence statement is not proof of support. |
| B-06 | An answer MUST pass output-schema, source-integrity, and evidence-support checks. Missing, contradictory, or uncertain support MUST result in abstention. |
| B-07 | Unchecked tokens MUST NOT be streamed to the user. Only a validated final response may be displayed or cached. |
| B-08 | An ambiguous service, version, region, or conversational reference MUST trigger a clarification when it changes the answer. Previous chat is context, never documentation evidence. |
| B-09 | Caches MUST be scoped to corpus, model, policy, filters, and conversation context. Similarity alone MUST NOT authorize answer reuse. |
| B-10 | Dependency outages, overload, and timeouts MUST be distinguishable from information being absent. Errors MUST NOT trigger an ungrounded fallback. |
| B-11 | The system MUST bound input size, context size, response size, concurrency, queue length, retries, and cache memory. |
| B-12 | Model output and documents MUST be treated as untrusted data. No document or model output may execute code, invoke tools, or choose arbitrary network destinations. |
| B-13 | The UI MUST show citations and local evidence excerpts, snapshot information, unavailable/error states, and a clear “no AWS account access” notice. |
| B-14 | Service code MUST expose measurable stages and testable interfaces; core policy MUST be independent of HTTP, database, cache, and model implementations. |
| B-15 | Keep immutable source bytes, faithful citation text, and derived retrieval representations separate. Tokenization MUST match each pinned model; normalization MUST preserve AWS identifiers, negation, conditions, and code semantics. |
| B-16 | Refresh MUST detect unchanged content, embed only new/changed embedding inputs, and publish changes atomically. Scheduled refresh MUST coalesce missed runs, avoid overlapping jobs, and never fetch from the chat path. |
| B-17 | Prompt stages MUST use reviewed packaged templates and constrained output schemas. Request/document text MUST remain data, never template source. Template contents MUST participate in answer-cache identity without changing the embedding profile. |
| B-18 | Additional prompt stages MUST share the existing deadline and inference lock, preserve fail-closed validation, and have explicit call bounds. No implicit memory, tools, cloud fallback, automatic repair/retry loop, or uncited synthesis may be introduced by framework defaults. |
| B-19 | The research decision MUST be one of answer, search more, clarify, or unavailable. Search-more may contain one to three normalized local-corpus queries and may execute only once per request. |
| B-20 | Java, not the model or LangChain4j, MUST execute retrieval. Model output cannot select a network destination, SQL, shell command, AWS API, or arbitrary tool. |
| B-21 | A synthesized answer MUST contain at most six claims. Every claim MUST cite one to three evidence aliases supplied to the model; Java MUST map aliases to active stored chunks and reject unknown or uncited claims. |

## 5. Grounding contract and its limits

“Prevent hallucinations” is a product objective, not an honest promise of zero error from a generative model. Prompts, low temperature, and a second model check cannot prove a synthesized claim correct.

The implemented policy is **GROUNDED_SYNTHESIS**. Qwen first decides whether to answer from initial evidence, run one additional local search, clarify, or abstain. It then drafts individual claims linked to evidence aliases. Java validates every alias and active source, and a separate Qwen stage reviews the complete draft against only its cited passages. Unsupported or uncertain output rejects the whole draft.

This offers a more useful response than displaying raw chunks, with residual hallucination risk: the generator and reviewer use the same model and can make correlated errors. Schema checks, citations, retrieval similarity, and model review do not prove factual correctness. The UI therefore exposes the exact stored evidence behind every citation. The policy must preserve qualifiers, enforce service/version applicability, and abstain when evidence is insufficient. Neither the agent nor the answer stage may use Qwen's outside knowledge.

For this baseline, do not generate partial answers to multi-part questions. If any requested material part is unsupported, abstain and optionally offer separately labeled related sources. This avoids implying completeness.

## 6. Proposed operational targets

Targets are hypotheses to validate on the actual laptop, not measured performance or company SLOs.

| Dimension | Initial target / limit |
| --- | --- |
| Exact validated answer cache hit | p95 under 250 ms, including corpus validity lookup, under no competing load |
| Hybrid retrieval excluding query embedding | p95 under 500 ms at 20,000 chunks |
| Warm end-to-end answer | p95 under 30 seconds for one active user; measure both policies separately |
| Request deadline | 60 seconds total, including waiting; never reset the deadline between stages |
| Inference admission | One active inference operation; at most four waiting requests; reject overflow |
| Question / history | At most 4,000 characters; at most three preceding user questions of the same limit; 32 KiB HTTP body limit |
| Prompt / output | 8,192-token context profile, at most 4,500 evidence tokens and 800 output tokens; total budget includes instructions and conversation |
| Cache | 128 MiB total accounted entry weight with separate caps per cache |
| Data durability | Published corpus survives process restart; cache may be lost |

Resource limits must be enforced even if the target latency cannot be met. Benchmark reports must record RAM, model digests, context lengths, corpus size, cold/warm state, and whether ingestion is active.

## 7. Decisions and remaining review items

| ID | Decision | Recommendation |
| --- | --- | --- |
| O-01 | Reference resource profile and available disk capacity | RAM confirmed as 16 GB. Reserve 10 GB free disk for initial setup; verify available space and measured growth before installation. |
| O-02 | Local Docker database | Docker confirmed acceptable; PostgreSQL + pgvector remains the recommended database. |
| O-03 | Must the demo use only excerpts, or are guarded generated summaries acceptable? | User approved bounded grounded synthesis with one optional additional-search round and a grounding review. |
| O-04 | Seed corpus minimum delegated to us | Selected 120 pages across IAM/S3/EC2/VPC/Lambda/CloudWatch. Use 30 IAM/S3/EC2 pages for the first vertical slice; see [data lifecycle](data-lifecycle.md) for quotas and coverage criteria. |
| O-05 | Are source-candidate reuse and exact answer caching sufficient for v0.1 similar-query caching? | Yes; defer direct semantic answer reuse until an equivalence/answer-reuse evaluation passes. |
| O-06 | Is this a trusted single-user demo or immediately shared within the company? | Single-user localhost only; shared deployment requires a separate identity, access-control, and capacity baseline. |

The bounded-agent design is implemented for local evaluation. Production release still requires the acceptance evidence in [acceptance.md](acceptance.md).

# Architecture decisions

Status: implemented baseline with release gates pending · Design baseline: 0.3 · Updated: 2026-08-31

These decisions describe the implemented local baseline. Dependency/image/model identities are pinned; compatibility tests and production vulnerability/license review are separate gates.

| ADR | Decision | Rationale and tradeoff |
| --- | --- | --- |
| ADR-001 | Java 21 LTS + Spring Boot, Maven Wrapper | Java ecosystem, typed contracts, operational tooling, and familiar company deployment. Java 21 is within Spring Boot's documented compatibility range. Keep the JDK distribution and supported framework release explicit; no reliance on an unpinned `latest`. |
| ADR-002 | Modular monolith with replaceable infrastructure adapters | One application is simpler to develop and operate locally. Domain boundaries, immutable contracts, and stateless request orchestration allow later separation without starting with microservices. |
| ADR-003 | Native Ollama + Qwen3 4B Q4_K_M | An Apache-2.0 model with a listed 2.5 GB quantized download. Native macOS inference is the initial choice; actual memory and throughput require measurement. Keep model digest and generation options in the release manifest. |
| ADR-004 | nomic-embed-text v1.5, full 768-dimensional vectors | Small local embedder with explicit search/document prefixes. Use identical model digest and preprocessing for indexing and querying. Changing the embedder creates a new corpus generation. |
| ADR-005 | PostgreSQL + pgvector, local container | Stores provenance, active-generation metadata, lexical search, and vectors transactionally. More local infrastructure than Lucene, but a straightforward path to a managed/shared database. Begin with exact vector search; benchmark before enabling HNSW. |
| ADR-006 | Caffeine for process-local caches | Bounded memory and TTL without an additional cache service. Cross-replica invalidation later uses versioned keys and authoritative corpus metadata, not the assumption that local eviction is global. |
| ADR-007 | Small same-origin HTML/CSS/JavaScript UI | Backend and ingestion remain Java; browser code is necessarily JavaScript. No frontend framework, CDN, or runtime Node service is needed for a chat box and evidence panel. |
| ADR-008 (updated 2026-08-31) | LangChain4j prompt stages with explicit Java policy and guarded Ollama transport | Use pinned langchain4j-core 1.19.0 PromptTemplate, ChatRequest and ChatModel APIs for reusable stages. Keep raw prompt formatting, schema validation, deadlines and quarantine in our adapter. No AI Services proxy, automatic RAG, chat memory or framework agent executor is enabled. |
| ADR-009 (updated 2026-08-31) | Bounded grounded synthesis | The user approved a simpler Agentic RAG flow: one research decision, at most one additional local search round with up to three queries, one cited answer draft, and one grounding review. Java validates all model-selected aliases and builds citations. This improves usefulness but retains residual same-model hallucination risk, so human evaluation remains a release gate. |
| ADR-010 | Exact answer cache; semantic retrieval-candidate cache | Protect against highly similar questions with opposite answers. Similar-question reuse still reranks and verifies evidence for the current question. It primarily saves retrieval work, not all inference. |
| ADR-011 | Packaged prompt resources and a template-content digest | Prompt-only changes invalidate answer reuse but do not rebuild embeddings. Prompt stage additions require explicit ordering, typed validation, call-budget review and evaluation. |
| ADR-012 | Portable local architecture; platform-specific setup helpers | Java, PostgreSQL/pgvector and Ollama define the runtime contract. Mac ARM64 is tested; Linux and Windows via WSL2 are documented targets, not certified deployments. Keep platform-specific helpers clearly labeled. |

## Storage alternatives

| Option | Strength | Why not the current default |
| --- | --- | --- |
| Embedded Apache Lucene | Java-native, no database container, lexical and vector retrieval | Excellent fallback for a container-free demo; a shared distributed store would be a later operational change, with additional provenance/transaction design. |
| Qdrant | Dedicated vector service with dense/sparse hybrid retrieval, metadata filters, and a distributed deployment path | A credible alternative, not rejected for lacking lexical search. For this design, PostgreSQL offers simpler relational integrity and transactional publication across provenance, job state, and vectors in one store. Qdrant would require a different metadata/publication design. |
| In-memory vectors or a JSON file | Very small setup | Insufficient durability, indexing, query isolation, and lifecycle controls for the intended engineering baseline. |

The recommendation is **PostgreSQL + pgvector for this workload**, not a claim that it is the fastest vector engine. Use exact search to establish a recall baseline, then adopt HNSW only if necessary. Reconsider a dedicated vector service when measured filtered-retrieval latency, indexing contention, or independent search-scaling needs justify it; there is no universal document-count migration threshold. Qdrant supports native dense/sparse fusion. [Qdrant hybrid queries](https://qdrant.tech/documentation/search/hybrid-queries/).

## Java framework alternatives

| Option | Reason to choose it | Assessment for this project |
| --- | --- | --- |
| Spring Boot | Integrated application configuration, HTTP/validation, security, JDBC/transactions, testing, health, and metrics | Recommended for a long-running company service. Use MVC + JDBC and a small dependency set; no mandatory ORM, reactive stack, or Spring AI. |
| Micronaut | Compile-time dependency injection and an emphasis on efficient startup/runtime behavior | Strong alternative if memory/startup measurements become the deciding constraint or the team already uses it. |
| Quarkus | Build-time optimization and JVM/native deployment options | Strong alternative for a later deployment with strict startup or native-image requirements. Native packaging adds build/tooling considerations and must be measured separately from JVM execution. |
| Minimal HTTP framework / JDK server | Small HTTP layer and fine-grained control | More integration work for the operational/security baseline; prefer only if keeping the web layer minimal is itself a requirement. |

Spring Boot is not required for RAG, and its presence does not make a system scalable. Our scalability comes from stateless orchestration, bounded work, durable ingestion, transactional publication, and replaceable adapters. Choose Spring Boot for integration and maintainability; keep core logic portable. Do not claim a memory winner without equivalent workload benchmarks. Measure full process RSS and native allocations, not heap alone. [Spring Boot operations](https://docs.spring.io/spring-boot/reference/actuator/index.html), [Micronaut core](https://docs.micronaut.io/latest/guide/), [Quarkus measurement methodology](https://quarkus.io/guides/performance-measure/).

## 16 GB local resource profile

Start with a 512 MiB Java heap (the 128 MiB cache budget is included), a 1 GiB database-container memory limit, and a 2 GiB Docker VM allocation that includes the database. These are initial limits to test, not observed utilization. Keep Ollama native and allow only one inference operation at a time. Reserve the remaining host memory for Ollama, the host OS, browser, and IDE; monitor memory pressure and swap during the real-model benchmark.

Raw 768-dimensional float32 payloads for 20,000 vectors total about 58.6 MiB by arithmetic, excluding row, document, index, WAL, and cache overhead. This illustrates why the initial vector payload is unlikely to dominate memory; actual end-to-end residency must be measured. Avoid running ingestion embeddings and interactive generation simultaneously on this machine. If the tested budget is exceeded, reduce active models/context/caches or corpus batch size before changing the framework on speculation.

## Verified technical references

These support capability choices, not performance claims about this laptop.

- [Spring Boot system requirements](https://docs.spring.io/spring-boot/system-requirements.html).
- [Ollama Qwen3 4B package and license](https://ollama.com/library/qwen3:4b).
- [Qwen3 4B upstream model card](https://huggingface.co/Qwen/Qwen3-4B).
- [Nomic embedding model card, prefixes, and dimensions](https://huggingface.co/nomic-ai/nomic-embed-text-v1.5).
- [pgvector capabilities and filtering considerations](https://github.com/pgvector/pgvector).
- [Caffeine eviction and expiration](https://github.com/ben-manes/caffeine/wiki/Eviction).

The pinned PostgreSQL/pgvector image manifest includes Linux ARM64 and AMD64. Verify the chosen platform during rollout. Pin the tested image digest, Java dependencies, Ollama version, model digests, prompt version, extraction version, and grounding-policy version. Produce an SBOM and document the model and corpus usage terms; do not assume AWS documentation inherits the application's license.

## LangChain4j integration references

Pinned core release: [Maven Central 1.19.0](https://repo.maven.apache.org/maven2/dev/langchain4j/langchain4j-core/1.19.0/). The implementation uses core prompt/model APIs, not automatic AI Services or the stock Ollama client. See [prompt-chain guide](prompt-chains.md) and [platform setup](platform-setup.md). Dependency inventory and vulnerability/license review remain separate activities.

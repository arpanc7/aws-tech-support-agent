# Low-level design

Baseline 0.4 · 2026-08-31 · Bounded Agentic RAG implementation.

## Main classes and ports

~~~mermaid
classDiagram
    class AwsSupportApplication
    class ChatController
    class AnswerQuestion {
      +answer(ChatRequest) ChatResponse
      -compute(Question, Deadline) ChatResponse
      -researchAndRender(Question, List~Evidence~, Deadline) ChatResponse
      -draftAndRender(Question, List~Evidence~, Deadline) ChatResponse
      +validDecision(ResearchDecision) boolean
      +validDraft(AnswerDraft, List~Evidence~) boolean
    }
    class LocalModel {
      <<interface>>
      +embed(List~String~, String, Deadline) List~Vector~
      +decide(Question, List~Evidence~, Deadline) ResearchDecision
      +answer(Question, List~Evidence~, Deadline) AnswerDraft
      +verify(Question, AnswerDraft, List~Evidence~, Deadline) boolean
    }
    class CorpusRepository {
      <<interface>>
      +activeCorpus() CorpusState
      +retrieve(QueryEmbedding, RetrievalRequest) List~Evidence~
      +evidenceValid(...) boolean
    }
    class OllamaModel
    class AgenticPromptChain
    class PromptStage
    class PostgresCorpusRepository

    AwsSupportApplication --> ChatController
    ChatController --> AnswerQuestion
    AnswerQuestion --> LocalModel
    AnswerQuestion --> CorpusRepository
    LocalModel <|.. OllamaModel
    CorpusRepository <|.. PostgresCorpusRepository
    OllamaModel --> AgenticPromptChain
    AgenticPromptChain --> PromptStage
~~~

**AwsSupportApplication.main()** is the process entry point. **ChatController** is the REST entry point for POST /api/v1/chat. Domain records and ports contain no Spring, JDBC, Jackson, or LangChain4j types.

## Request sequence

~~~mermaid
sequenceDiagram
    actor U as User
    participant C as ChatController
    participant A as AnswerQuestion
    participant R as CorpusRepository
    participant N as Nomic/Ollama
    participant Q as Qwen/AgenticPromptChain

    U->>C: POST /api/v1/chat
    C->>A: ChatRequest
    A->>R: active corpus + cache validity
    alt validated exact answer
        A-->>C: cached ChatResponse
    else miss
        A->>N: embed original retrieval text
        A->>R: hybrid retrieve
        A->>Q: decide(question, initial evidence)
        alt SEARCH_MORE
            A->>N: batch embed 1-3 search queries
            loop each bounded query
                A->>R: hybrid retrieve in pinned generation
            end
            A->>A: merge, deduplicate, token-budget
        else CLARIFY or UNAVAILABLE
            A-->>C: fail-closed response
        end
        A->>Q: answer(question, final evidence)
        A->>A: validate claim schema and aliases
        A->>Q: verify(question, draft, cited evidence)
        A->>R: recheck source/epoch validity
        A->>A: build server citation IDs and URLs
        A-->>C: buffered ChatResponse
    end
    C-->>U: JSON
~~~

The same absolute **Deadline** flows through embedding, retrieval, all three Qwen stages, and admission. There is no deadline reset. No answer token reaches the browser before the complete response passes validation.

## Domain contracts

**ResearchDecision** contains an action and search requests:

- ANSWER, CLARIFY, and UNAVAILABLE require an empty search list.
- SEARCH_MORE requires one to three distinct nonblank queries.
- Each query is normalized and capped at 1,000 characters.
- An optional service must be in the supported-service allowlist. A user's explicit service filter overrides it.

**AnswerDraft** contains ANSWER or UNAVAILABLE:

- UNAVAILABLE has no claims.
- ANSWER has one to six nonblank claims.
- Each claim cites one to three unique stored evidence IDs after alias mapping.
- Every cited ID must occur in the evidence supplied to the answer stage.

Any mismatch throws INVALID_MODEL_OUTPUT and produces no AWS claim. The application never interprets model output as SQL, a URL, a command, or a tool name.

## Prompt and model adapter

**AgenticPromptChain** loads three packaged policies and the common evidence-user.txt data template:

1. research-system.txt returns the bounded action and optional searches.
2. answer-system.txt returns cited claims or unavailable.
3. grounding-system.txt returns supported, unsupported, or uncertain.

Evidence gets request-local aliases. The model sees service, title, heading, and text; it does not see vectors, database credentials, source URLs, or internal citation IDs. The answer stage maps aliases to stored chunk IDs before returning through LocalModel; the grounding stage remaps those IDs to aliases for a self-contained prompt.

**PromptStage** uses LangChain4j PromptTemplate, ChatRequest, ChatModel, and structured response formats. **OllamaModel.GuardedChatModel** translates those objects to the existing raw /api/generate request. It enforces two messages, raw ChatML, Qwen's 8,192-token context, temperature 0.1, seed 42, 800 output tokens, no streaming, response body limits, tokenizer parity, and the remaining shared deadline. Malformed output is rejected without a repair call.

Nomic uses /api/embed, truncate=false, and the pinned 768-dimensional profile. Missing embeddings from the original or follow-up query list are batched and individually cached.

## Retrieval and evidence assembly

PostgreSQL returns dense and English lexical rankings from the pinned generation, service filter, and nonrevoked sources. Java combines them with reciprocal-rank fusion and exact identifier matches. It applies the configured cosine floor, removes duplicate text, takes up to six passages, and enforces the 4,500-evidence-token budget. The six-passage cap limits three serial Qwen prompt costs while still permitting one distinct source per maximum answer claim.

A similar-query cache contains candidate IDs, never a final semantic answer. The current request still performs a full retrieval and merges it with reusable candidates. Follow-up results are interleaved with initial results so one query cannot consume the entire budget. All sources remain in the original pinned generation.

## Rendering and citations

For a supported draft, Java keeps only cited evidence, verifies that it remains active, assigns response-local IDs such as C1, and builds citations from stored metadata. Claim.text is synthesized prose. Citation.quote is the exact stored chunk text. The browser renders these separately and uses textContent; it only opens HTTPS links on docs.aws.amazon.com.

The response uses answerMode=GROUNDED_SYNTHESIS. An unsupported result has no claims or citations and uses the fixed unavailable message. Clarification and dependency failures remain separate statuses.

## Cache, concurrency, and failure behavior

Exact cache keys include normalized question/history/filters, corpus generation, policy epoch, model identities, policy version, and ordered prompt digest. In-flight identical requests may coalesce. Cached responses are returned only after authoritative corpus/source checks.

One application-coordinated inference is active at a time and at most four requests wait. PostgreSQL advisory locks coordinate processes. An uncertain timeout quarantines the model until the runtime is restarted and explicitly reset. There is no automatic model repair, retry, follow-on research loop, web fallback, or hidden chat memory.

## Storage and refresh

Flyway owns the schema. Generations, documents, chunks, source checks, ingestion jobs, embedding checkpoints, revocations, and model health are durable. Publication inserts a complete generation and moves the active pointer in one transaction. Integration tests use an isolated schema and never clear the user's corpus.

## Observability

Structured logs identify request/job/stage outcomes without prompt bodies or document text. Micrometer records HTTP, cache, rejection, inference, research-decision, additional-search, and result metrics. A periodic JSON snapshot is written under the configured local temporary metrics path; see [operations](operations.md). Prompt/model/retrieval changes require unit/contract tests and real-model smoke probes.

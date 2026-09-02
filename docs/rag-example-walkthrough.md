# A real question through the local AWS RAG pipeline

**Historical trace:** captured on 2026-08-31 for the earlier extractive two-stage implementation. The request payloads, call count, answer mode, and output below do **not** describe the current bounded Agentic RAG policy. They remain useful as a concrete embedding and PostgreSQL retrieval example. The current flow uses AgenticPromptChain/PromptStage for research, cited answer drafting, and grounding review; Qwen may request one additional local search round with up to three queries. See [current low-level design](low-level-design.md) and [verification status](implementation-status.md).

Trace captured 2026-08-31 against the local documentation snapshot. This is an actual application-service invocation in a separate diagnostic JVM with fresh caches, not a replay through the HTTP controller or browser. Production classes, the existing PostgreSQL corpus, and the pinned Ollama models were used unchanged. A temporary loopback proxy recorded model HTTP payloads and stopped when the diagnostic finished. No corpus changes or model downloads were performed.

## 1. User input

```json
{
  "question": "What is the maximum timeout for an AWS Lambda function?",
  "previousQuestions": [],
  "filters": {
    "service": "LAMBDA",
    "region": "",
    "documentVersion": ""
  }
}
```

Java validates and normalizes the question and filters, resolves the active corpus, validates model digests, and checks the exact answer cache. This first invocation is a cache miss.

## 2. Embedding input and output

POST http://127.0.0.1:11434/api/embed

```json
{
  "input": [
    "search_query: What is the maximum timeout for an AWS Lambda function?"
  ],
  "keep_alive": "5m",
  "model": "nomic-embed-text:v1.5",
  "truncate": false
}
```

The `search_query: ` prefix is part of the embedding input. There is no conversation history in this example. The Nomic tokenizer checks the input length. Nomic produces one 768-dimensional vector; it does not produce an answer.

First eight actual vector values (remaining 760 omitted here):

```json
[0.027461147, 0.038020685, -0.19408016, -0.041240525, 0.054819804, -0.031446613, -0.019318108, -0.0043476284]
```

The full local response was recorded at `.cache/rag-trace/04-http-_api_embed-response.json` (an ignored diagnostic artifact). Java checks dimensions, finite values, and a nonzero vector, then caches the embedding.

## 3. Database calls

The repository receives the active generation, the original question text, service LAMBDA, the complete vector, and an empty candidate-ID list. The captured repository arguments are retained locally at `.cache/rag-trace/06-CorpusRepository-retrieve-input.json`.

The implementation performs hybrid retrieval: dense cosine-distance search (top 40) plus English full-text search (top 40), with a separate exact-identifier branch when identifiers are present. This question has no qualifying exact identifiers. Both queries filter by snapshot and service and exclude revoked sources.

The exact named-parameter SQL templates and bound parameter values are retained locally at `.cache/rag-trace/db-queries.sql` and `.cache/rag-trace/db-parameters.json`.

Dense SQL, shortened only for readability:

```sql
SELECT c.id, c.content,
       1 - (c.embedding <=> CAST(:vector AS vector)) AS similarity
FROM chunk c
JOIN document d
  ON d.generation_id = c.generation_id
 AND d.source_id = c.source_id
WHERE c.generation_id = :generation
  AND (:service = '' OR d.service = :service)
  AND NOT EXISTS (
    SELECT 1 FROM revoked_source r WHERE r.source_id = c.source_id
  )
ORDER BY c.embedding <=> CAST(:vector AS vector), c.id
LIMIT 40;
```

The `<=>` operator computes cosine distance; subtracting it from 1 gives cosine similarity. The vector is a bound parameter, not a natural-language prompt sent to PostgreSQL.

A read-only replay of those same SQL templates with the captured parameters returned 40 dense rows and 40 lexical rows. Individual results are retained locally at `.cache/rag-trace/db-query-results.json`. These replay results are distinct from the repository capture made during the application invocation.

Java merges rankings using reciprocal rank fusion, yielding **63 unique candidates**. This is the actual combined repository output:

| Rank | Heading | Cosine similarity |
|---|---|---|
| 1 | Troubleshoot configuration issues in Lambda > Timeouts | 0.8126 |
| 2 | Configure Lambda function timeout > Configuring timeout (AWS SAM) | 0.7992 |
| 3 | Configure Lambda function timeout > Configuring timeout (AWS CLI) | 0.8456 |
| 4 | Troubleshoot execution issues in Lambda > Lambda: Detecting infinite loops | 0.7306 |
| 5 | Lambda quotas | 0.7775 |

The final ranking is hybrid, so rows are not necessarily sorted by cosine similarity. Similarity is not a confidence or correctness probability. All ranked candidates are retained locally at `.cache/rag-trace/07-CorpusRepository-retrieve-output.json`.

## 4. Evidence budgeting and first Qwen call

Java removes candidates below its 0.40 cosine threshold, removes duplicate text, and enforces the evidence token budget. Eight passages fit in this example; they receive aliases E1 through E8. The full prompt has 2,404 input tokens. The request uses an 8,192-token context, temperature 0.1, seed 42, an 800-token output cap, raw ChatML, and a constrained JSON schema.

Qwen receives the question plus readable passage text and metadata, not the embedding vectors. Its system instruction tells it to select the smallest set of complete excerpts answering the question using only the supplied evidence, or abstain.

The complete selection request and readable prompt are retained locally at `.cache/rag-trace/09-http-_api_generate-request.json` and `.cache/rag-trace/09-http-_api_generate-request-prompt.txt`.

Actual Qwen response:

```json
{
  "decision": "ANSWERABLE",
  "evidenceIds": [
    "E1"
  ]
}
```

E1 is the first retrieval result, the Timeouts section of “Troubleshoot configuration issues in Lambda.” It starts with:

> Timeouts for Lambda functions can be set between 1 and 900 seconds (15 minutes).

Java verifies that E1 belongs to the supplied set and maps it back to the stored chunk ID.

## 5. Second Qwen call: coverage check

Java sends the original question and only the selected excerpt to Qwen, with separate instructions to check whether the excerpt supports the requested answer and its conditions. This call has 558 input tokens.

The complete verification request and readable prompt are retained locally at `.cache/rag-trace/13-http-_api_generate-request.json` and `.cache/rag-trace/13-http-_api_generate-request-prompt.txt`.

Actual response:

```json
{
  "verdict": "SUPPORTED"
}
```

This second pass uses the same model. It reduces some errors but is not an independent guarantee against hallucinations or mistaken evidence selection.

## 6. Final response

Java checks snapshot/source validity again, copies the entire selected stored passage verbatim into a claim, and builds the citation itself. Qwen does not write a free-form AWS answer in EXTRACTIVE_STRICT mode. The visible answer is longer than a one-line “900 seconds” response because it retains the complete stored excerpt.

```json
{
  "status": "ANSWERED",
  "answerMode": "EXTRACTIVE_STRICT",
  "corpusGeneration": "21fa20b5-5b91-49ae-9c1e-b146dc23c0a0",
  "cacheDisposition": "MISS",
  "semanticCandidatesUsed": false,
  "durationMs": 13092
}
```

The full final response with claim and citation is retained locally at `.cache/rag-trace/16-final-response.json`.

## 7. Same question again

The immediate repeat returned cacheDisposition=EXACT in 8 ms. It still validated the model profile and source/snapshot state, but did not repeat embedding, retrieval, selection, or coverage inference. The repeat response is retained locally at `.cache/rag-trace/17-repeat-response.json`.

If no adequate evidence is retrieved, selection rejects it, or verification fails, the application abstains instead of falling back to Qwen’s training knowledge. The cache and thresholds still require broader evaluation before production use.

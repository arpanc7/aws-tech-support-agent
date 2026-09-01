# Local operations

Baseline 0.3 · 2026-08-31. See [platform setup](platform-setup.md) for host-specific provisioning and [low-level design](low-level-design.md) for runtime boundaries.

`scripts/run` switches to the project root, loads ignored `.env`, sets the local tokenizer cache, and caps the JVM heap at 512 MiB. Keep `.env` private. Downloads occur only through setup and explicit or enabled refresh.

## Start and stop

Choose `docker compose up -d --wait postgres` **or** `./scripts/postgres-native start`. The native helper manages only `data/postgres`. Use `./scripts/postgres-native status` to identify its process. Stop with `./scripts/postgres-native stop` or `docker compose stop postgres`. Never use `docker compose down -v` unless intentionally deleting data.

Start `./scripts/ollama serve` and `./scripts/run serve` in separate terminals. Ollama uses loopback, cloud disabled, one inference slot, and at most two loaded models. Do not use unrelated Ollama clients concurrently: they bypass the application's inference lock. Stop the app/Ollama terminals with Ctrl-C. The UI's Stop waiting cancels browser waiting; server inference may finish before releasing capacity.

Build with `./scripts/maven package` before restarting Java after code changes. Migrations run at startup and in operator commands. Back up before schema changes. Admission is five requests per JVM, including identical coalesced requests; overflow receives 429. PostgreSQL session advisory locks serialize inference and ingestion across CLI/server processes. This is a local capacity profile, not a distributed capacity guarantee.

## Sources and freshness

1. Review canonical URL and service in `config/sources.json`.
2. Run `./scripts/run validate-manifest` to fetch/parse/tokenize without embedding or publishing.
3. Run `./scripts/run refresh` and inspect its final summary.
4. Check `./scripts/run status` and UI counts/date.

Only canonical HTTPS HTML pages on `docs.aws.amazon.com` are allowed; the runtime does not crawl recursively. Approved offline HTML can go in `data/imports`, with the manifest's `localFile` set to a relative filename. Keep the canonical URL for attribution. Imported bytes remain the operator's responsibility; a URL is not proof of authenticity.

HTTP 304 reuses checksum-verified snapshots. Complete embedding input plus model/tokenizer/extraction profile governs reuse. Additions/removals take effect on atomic publication. A failed required page prevents partial publication; completed checkpoints survive retries. Old generations/snapshots are retained. Automatic pruning is not implemented: monitor disk usage.

`RAG_REFRESH_ENABLED=true` checks every minute for a 24-hour interval while the server runs. Failures retain old data/freshness and persist a 15-minute retry delay. The UI reports refresh failure and warns after seven days without a complete check. Resuming the host coalesces missed intervals. No OS scheduler or wake-up automation is installed.

## Revocation and rollback

```sh
./scripts/run revoke --source=source-id-from-manifest
./scripts/run rollback --generation=retained-generation-uuid
```

Revocation persists across generations and invalidates cache hits. There is no casual un-revoke command. Rollback requires a retained compatible embedding profile, clears freshness, and does not undo revocation. Ordinary source removal uses the manifest.

## Recovery

- Missing corpus: ingest; readiness remains down until a compatible generation exists.
- Model/profile mismatch: install pinned artifacts or review a profile change and re-ingest.
- Database unavailable: restart the selected database. The app does not return cached answers it cannot validate.
- Invalid model output: reject it; do not disable safeguards to force answers.
- Timeout, uncertain inference completion, or process crash during inference: stop the app, stop/restart Ollama, run `./scripts/run model-reset --runtime-restarted`, restart the app. HTTP timeout alone does not prove inference stopped.
- Oversize code block: curate an alternative page or improve extraction and bump the profile; never silently truncate.
- Docker startup failure: inspect Docker diagnostics. This machine failed while loading a hardware-acceleration setting. Global Docker settings were left unchanged; native PostgreSQL was used.

Readiness checks index/model presence and compatibility without inference. It is not a generation test or load-health certification. Run smoke probes after recovery.

## Diagnostics

```sh
./scripts/run retrieve --service=LAMBDA --question='What is the maximum timeout for an AWS Lambda function?'
```

This explicit diagnostic prints retrieved documentation, scores, and IDs. `--check` also runs selection/verification on the first eight results; it may exceed normal API budgets, so use chat for the actual response policy. Keep diagnostics local. Normal logs contain job/source IDs and state, not prompts or document bodies. Actuator includes request timing, status/cache disposition, rejection, JVM/HTTP/database metrics. Stage-level tracing remains future work.

## Backup and restore

Pause ingestion/configuration edits and do not delete snapshots while backing up:

```sh
./scripts/backup
# Native fallback:
RAG_DATABASE_MODE=native ./scripts/backup
```

Backups under `.cache/backups/<UTC time>/` contain a PostgreSQL custom dump, snapshots/tokenizers/config archive, and SHA-256 checksums. They exclude model weights and secrets. Preserve the lock file and privately back up `.env` separately. This does not protect against loss of the host itself.

Verify `checksums.json`, then restore to a **new empty database**, never over the active database. Native example after loading `.env`:

```sh
export PGPASSWORD="$RAG_DB_PASSWORD"
PG_TOOLS="$PWD/.tools/Postgres.app/Contents/Versions/17/bin"
"$PG_TOOLS/createdb" -h 127.0.0.1 -p 54329 -U aws_support aws_support_restored
"$PG_TOOLS/pg_restore" -h 127.0.0.1 -p 54329 -U aws_support -d aws_support_restored --no-owner --no-acl --exit-on-error /absolute/path/to/backup/database.dump
```

For Docker, create the new database with `docker compose exec -T postgres createdb -U aws_support aws_support_restored`, then pipe the dump to `docker compose exec -T postgres pg_restore -U aws_support -d aws_support_restored --no-owner --no-acl --exit-on-error`.

Review archive paths and extract artifacts into a clean directory. Set `RAG_DATA_DIR` to restored `data` and `RAG_DB_URL` to the new database. Snapshots resolve by content hash, not old absolute paths. Validate status, readiness, and retrieval/smoke results before switching. Retain the original until restoration is accepted. Restored uncertain model-health state still requires restart/reset.

## Company deployment prerequisites

Regenerate the dependency inventory with `./scripts/maven org.cyclonedx:cyclonedx-maven-plugin:2.9.1:makeAggregateBom`. Review the generated SBOM separately for vulnerabilities and license policy; generation alone is not a security scan.

Add identity/authorization, tenant boundaries, TLS, managed secrets, separate migration/runtime database principals, distributed admission/queueing, dependency/security review, audited restore procedures, SLOs/observability, corpus usage review, and the human-reviewed quality evaluation. Process-local caches and one PostgreSQL node do not provide high availability.

## Prompt-chain rollout

Prompts are packaged resources, not mutable HTTP settings. Edit the reviewed files under `src/main/resources/prompts` and the explicit stages in EvidencePromptChain, following [prompt-chains.md](prompt-chains.md). Run formatting, automated tests and real-model probes; build the jar and restart Java. Do not restart/download models or re-ingest solely for a prompt change. Prompt digests isolate answer caches; embedding profiles remain compatible.

The guarded LangChain4j bridge retains raw ChatML, output schemas, tokenizer checks and the original deadline. Never work around a failed stage by adding an automatic retry, cloud fallback, tool access, or skipped verification. Model-profile/embedding changes need their own corpus and evaluation plan.

This rollout does not add per-request prompt logging or hosted tracing. Historical explicit trace artifacts under `.cache/rag-trace` remain local and may contain documentation text; do not commit or publish them indiscriminately.

# Platforms and local setup

The application is not specific to an M1 Pro. Its dependencies are Java 21, PostgreSQL 17 with pgvector 0.8.6, Ollama 0.33.2, the pinned model/tokenizer artifacts, and a browser. Setup/diagnostic helpers also use Python 3.11+ and a POSIX shell. Allow at least 10 GB free disk initially and monitor snapshots, checkpoints, backups and build caches as they grow.

## Tested versus intended environments

| Environment | Setup path | Verification status |
| --- | --- | --- |
| macOS ARM64 | Project-local bootstrap; native Ollama; Docker PostgreSQL or native Postgres.app fallback | Tested on M1 Pro / 16 GB using native PostgreSQL; this is the reference machine |
| Linux amd64/arm64 | Install JDK/Maven/Ollama for the host; run common POSIX steps below | Architecture-compatible target; full runtime/tokenizer/performance verification pending |
| Windows via WSL2 | Install/run all components and helpers in the same Linux environment | Documented target, not tested; verify loopback isolation and GPU/runtime support |
| macOS Intel | Install compatible native runtimes manually; use common POSIX helpers | Not tested; ARM64 bootstrap does not apply |
| Native Windows shell | Maven wrapper exists, but operational helpers are POSIX | End-to-end helper support is not provided; use WSL2 or develop reviewed equivalents |

16 GB is a starting local resource profile, not a guaranteed minimum for every OS/GPU/workload. CPU-only inference may exceed the 60-second request budget. Measure actual memory pressure, swap and cold/warm latency; do not remove deadlines or grounding checks to make an unsupported setup appear to work. DJL requires a matching native tokenizer binary, and local/runtime token counts must agree.

## macOS ARM64 convenience setup

```sh
python3 scripts/bootstrap.py
./scripts/configure-local
python3 scripts/setup-models.py
```

The bootstrap downloads checksum-pinned JDK and Maven under `.tools/`; it is explicitly ARM64-only. The model setup helper downloads pinned tokenizers and the macOS Ollama archive. It does not change the trained model weights.

For Docker PostgreSQL, use `docker compose up -d --wait postgres`. If Docker is unavailable on macOS, the optional fallback is:

```sh
python3 scripts/setup-postgres.py
./scripts/postgres-native start
```

Do not run both database paths on port 54329. Postgres.app is installed under `.tools/`, with data under `data/postgres`; Docker uses its own named volume. These are different storage locations, not interchangeable mounts.

## Other POSIX hosts, including Linux/WSL2

Install JDK 21, Maven 3.9.11 (or use the provided Maven wrapper), Docker Compose and the Ollama release recorded in `config/models.lock.json` for your host architecture. Obtain Ollama from its official release artifacts and verify the platform artifact checksum; the lock file's bundled archive checksum is specifically macOS. Provision dependencies explicitly, never during a query.

```sh
./scripts/configure-local
python3 scripts/setup-models.py
# On non-macOS hosts this downloads tokenizers and asks you to install Ollama separately.
docker compose up -d --wait postgres
```

`./scripts/maven` uses project-local tools when present and otherwise calls `mvn` on PATH. `./mvnw` is an alternative build command when only JDK 21 is installed. `./scripts/run` uses JAVA_HOME, project-local Java, or Java on PATH; `./scripts/ollama` uses the project-local binary or Ollama on PATH.

Run Ollama inside the same environment as Java with `./scripts/ollama serve`. For WSL2, do not casually switch the endpoint to a LAN-accessible Windows host service; the guarded adapter expects a fixed 127.0.0.1 HTTP endpoint. Verify localhost access from the browser and that no listener is exposed externally.

## Shared remaining steps

In separate terminals, start Ollama, provision the models, build and ingest:

```sh
./scripts/ollama serve
```

```sh
./scripts/ollama pull nomic-embed-text:v1.5
./scripts/ollama pull qwen3:4b
./scripts/maven package
./scripts/run validate-manifest
./scripts/run ingest
./scripts/run serve
```

Open http://127.0.0.1:8080/. Model tags must resolve to the pinned digests; fail-closed profile checks detect changed tags. Resolve a mismatch with a reviewed profile update, not by bypassing checks. The query path needs no AWS credentials or internet after all artifacts and the corpus are provisioned.

## Validation on a new platform

Run formatting/unit/architecture/model-contract tests and PostgreSQL integration tests. Then run the smoke probes with pinned models and inspect token parity, readiness, full corpus counts, cold/warm timings, memory pressure and network behavior. Verify backup/restore and failure recovery. The macOS observations are not cross-platform certification.

For a manually installed native PostgreSQL instance, create database/user `aws_support`, enable pgvector, bind loopback, and configure the URL/password in the environment. A DBA should separate migration and runtime privileges before shared deployment; this local demo does not enforce least-privilege roles.

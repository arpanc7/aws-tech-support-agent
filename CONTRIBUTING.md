# Contributing

Thank you for considering a contribution to the AWS Tech Support Agent demo.

1. Fork the repository and create a focused branch.
2. Do not commit credentials, `.env` files, downloaded AWS documentation, model weights, database files, logs, caches, or build output.
3. Keep the domain and application layers independent of Spring and infrastructure adapters.
4. Preserve strict grounding: unsupported questions must abstain, and displayed claims must remain complete stored excerpts with server-built citations.
5. Run `./scripts/maven spotless:check verify` before opening a pull request. Prompt or retrieval changes also require the real-model smoke suite described in the README.
6. Open a pull request against `main` and explain the problem, the behavior change, and the validation performed.

The `main` branch does not accept direct changes. GitHub Actions must pass and `@arpanc7` must approve the pull request. Reviews are dismissed when the proposed code changes, and the author of the latest push cannot approve that push.

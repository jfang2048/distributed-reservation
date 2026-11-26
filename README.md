# Distributed Reservation System

We're designing a Java 17 reservation system. Servers talk over TCP and use
Two-Phase Commit (2PC) to reserve items in one transaction.

Politecnico di Milano · Distributed Systems (090950) · Project 2 · 2026/2027

## Docs

- [Course requirements](docs/requirements.md)
- [Server roles and resource locks](docs/architecture.md)
- [Messages and 2PC](docs/protocol.md)
- [Logs and restart](docs/recovery.md)
- [Deployment and network settings](docs/deployment.md)
- [Laptop and VM testbed](docs/testbed.md)
- [Implementation plan](docs/implementation.md)
- [Tests and LAN demo](docs/testing.md)

Java sources go in `src/` and tests in `tests/`. Settings live in `config/`,
local commands in `scripts/`, and VM commands in `infra/`.

Start with [TCP framing and log replay](docs/implementation.md#build-order).

# Distributed Reservation System

A Java 17 reservation system. Servers use Two-Phase Commit (2PC) over TCP
to reserve items in one transaction and recover them after a crash.

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

Run `./scripts/test.sh` with a JDK 17 or newer and Python 3. For a live
run, follow the [laptop testbed](docs/testbed.md).

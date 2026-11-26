# Distributed Reservation System

We're designing a Java 17 reservation system. Servers talk over TCP and use
Two-Phase Commit (2PC) to reserve items in one transaction.

Politecnico di Milano · Distributed Systems (090950) · Project 2 · 2026/2027

## Docs

- [Course requirements](docs/requirements.md)
- [Server roles and resource locks](docs/architecture.md)
- [Messages and 2PC](docs/protocol.md)
- [Logs and restart](docs/recovery.md)
- [Tests and LAN demo](docs/testing.md)
- [Git workflow](CONTRIBUTING.md)

Java sources go in `src/`, example settings in `config/`, and tests in `tests/`.

## Next steps

Target weeks start with implementation.

1. **Week 1** — Build TCP framing and log replay.
2. **Week 2** — Get commit and abort working on one laptop.
3. **Week 3** — Add recovery, retries, and crash tests.
4. **Week 4** — Test concurrent requests, rehearse on two laptops, and prepare slides.

# Distributed Reservation System

A planned Java 17 project for atomic reservations across multiple servers using
Two-Phase Commit (2PC) over TCP.

Politecnico di Milano · Distributed Systems (090950) · Project 2 · 2026/2027

The project is at the design stage. The documents describe planned behavior.

## Design

Each resource is a single item owned by one server. A reservation may include
items on several servers. One server coordinates each transaction and also
takes part as a participant if it owns a requested item. Participants try to
lock their items during prepare and vote no if an item is unavailable.

The coordinator saves the final decision before sending it. After a yes vote,
a participant keeps its locks until it learns the outcome. If every participant
is waiting and no one knows the outcome, the transaction blocks until the
coordinator recovers. The [demo plan](docs/testing.md) includes this case.

## Project Structure

| Path | Purpose |
| --- | --- |
| `src/` | Planned Java source files |
| `config/` | Planned example node and resource settings |
| `tests/` | Planned automated tests and crash scenarios |
| `docs/` | Requirements, design, and demo plan |

## Documentation

- [Course requirements](docs/requirements.md)
- [Roles, resources, and concurrency](docs/architecture.md)
- [Messages and 2PC](docs/protocol.md)
- [Log and recovery](docs/recovery.md)
- [Tests and LAN demo](docs/testing.md)
- [Contributing and Git workflow](CONTRIBUTING.md)

## Roadmap

Target weeks are counted from the start of implementation.

| Target | Milestone | Status |
| --- | --- | --- |
| Design | Course requirements, protocol, log design, and demo plan | Documented |
| Week 1 | Implement TCP message framing and durable log framing and replay | Planned |
| Week 2 | Run commit and abort over TCP on one laptop | Planned |
| Week 3 | Recover after crashes, retry messages, and show 2PC blocking | Planned |
| Week 4 | Test concurrent requests, rehearse on two laptops, and prepare slides | Planned |

Present the project before the last official exam session of the academic year.

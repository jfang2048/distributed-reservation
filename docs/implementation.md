# Implementation Plan

Build one JAR with Java 17 and the standard library. Give each class one
job. Use Java records for messages and log entries. Load
[`config/cluster.properties`](deployment.md#shared-settings) once at startup.

## Code layout

| Class | Job |
| --- | --- |
| `Main` | Validate settings and dispatch `server` or `reserve`. |
| `Client` | Send a request and print the transaction ID and result. |
| `Server` | Accept connections and route messages and timer events. |
| `Transport` | Connect, send, and read TCP frames. |
| `Coordinator` | Collect votes and deliver the decision. |
| `Participant` | Vote, query the decision, and apply it. |
| `ResourceManager` | Claim items and apply reservations. |
| `TransactionLog` | Append, force, and replay records. |

## Build order

Count weeks from the start of implementation.

1. **Week 1 — TCP and log.** Use `ServerSocket.accept` and a bounded worker
   pool. Use connect and read timeouts. Read the length, check it, then use
   `readFully` for the frame body. Use the [message layout](protocol.md#tcp-messages)
   and [log layout](recovery.md#log-format). Test frame decoding and log replay.
2. **Week 2 — Commit and abort.** Implement the [2PC state machine](protocol.md#prepare-and-decide).
   Give `Coordinator` a `WAIT` state and `Participant` the `INIT` and `READY`
   states. Process one event at a time per transaction and role. Force each
   required record before publishing its transition. Deliver vote deadlines
   as events. Queue peer sends after releasing the transaction guard. Route a
   local participant through the same handlers. Use the
   [resource lock rules](architecture.md#resources).
3. **Week 3 — Recovery and retries.** Rebuild both roles and the resource table
   from the log before accepting connections. Schedule retries as events.
   Use the [restart rules](recovery.md#replay),
   [transaction key](protocol.md#transaction-key), and
   [decision requests](protocol.md#decision-requests). Run the
   [crash tests](testing.md#crash-points).
4. **Week 4 — Concurrency and demo.** Run the
   [reservation checks](testing.md#checks) and rehearse the
   [LAN demo](testing.md#lan-demo). Prepare the slides from the demo logs.

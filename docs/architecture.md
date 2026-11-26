# Architecture

This is the design for the first implementation. See the
[course requirements](requirements.md) for the required behavior.

## Components

| Planned file | Role |
| --- | --- |
| `Client.java` | Send a reservation request and show the result. |
| `Server.java` | Load node settings, accept requests, and route messages. |
| `Transport.java` | Send and receive messages over TCP. |
| `Coordinator.java` | Collect votes, save the decision, and send it to participants. |
| `Participant.java` | Check and lock local resources, vote, and apply the decision. |
| `ResourceManager.java` | Track local resources, reservations, and locks. |
| `TransactionLog.java` | Write and replay durable transaction records. |

Each node runs the same server program. A node that coordinates a transaction
also acts as a participant if it owns a requested resource.

Example settings in `config/` will list node IDs, addresses, ports, and resource
ownership. Keep each node's local transaction log in its own directory under
`run/` and reuse it on restart.

## Two-phase commit

1. **Prepare.** The coordinator records the transaction ID and participant
   list before sending `PREPARE`. Each participant checks its local resources
   together. It either locks them and records its yes vote before sending
   `VOTE_COMMIT`, or rejects the request with `VOTE_ABORT`.
2. **Decide.** The coordinator commits only after all participants vote yes.
   It aborts on a no vote or on a timeout while collecting votes. It saves the
   final decision before sending it. Each participant records the decision,
   applies it, releases its locks, and acknowledges it. The coordinator
   returns the final result to the client after all participants acknowledge.

## Recovery

- A coordinator replays its log before accepting new work. It resends any
  recorded decision. An interrupted transaction with no recorded decision
  can be aborted because a decision is always saved before it is sent.
- A participant restores committed reservations and locks for transactions
  with a saved yes vote and no final decision. It can ask the coordinator or
  another participant for a recorded final decision. It keeps its locks if
  the outcome is still unknown, even after a timeout.
- A repeat request uses the same transaction ID. Replaying or receiving the
  same vote or decision twice must leave the result unchanged.

See slides 4–11 of `07-agreement.pdf` for the course description of 2PC.

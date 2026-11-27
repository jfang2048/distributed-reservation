# Tests and Course Demo

## Automated tests

Run `python3 scripts/test.py` from the repository root. Use `py -3` or
`python` on Windows. The suite builds the JAR and checks networking,
conflicting requests, retries, log replay, and crash recovery.

## Fault injection

Set `fault.node` and `fault.point` in the active properties file. Set
`fault.txId` to target one transaction. Use `fault.action=halt` to exit or
`pause` to inspect peers before stopping the process. Remove the settings
and reuse the log directory on restart.

| Point | Expected behavior |
| --- | --- |
| `after-start` | Restart records abort. |
| `after-first-prepare` | A prepared peer can learn a durable no vote. |
| `after-all-yes` | All participants hold their locks until the decision arrives. |
| `after-decision` | Restart sends the saved decision. |
| `after-first-decision` | A waiting peer can learn the decision from the peer that ACKed. |
| `after-end` | The same transaction ID returns the saved result. |
| `before-vote` | The coordinator aborts if the vote deadline passes. |
| `after-vote` | Restart restores the prepared lock. |
| `before-decision-log` | Restart learns the decision through retry or query. |
| `after-apply` | Replay keeps the outcome and ACKs redelivery. |

## Course demo

Use two laptops on the same LAN and a team of two or three. Present before
the last official exam session. Use a few slides for the architecture and
where each process runs. See `Progetti.pdf`, pp. 1–3.

1. Use the [LAN settings](deployment.md#on-a-lan). Start participants `a` and
   `b` on different laptops. Add a third node `c` on either laptop with a
   separate port and use `--coordinator c`.
2. Show an all-or-nothing reservation across two servers and two requests
   competing for the same item.
3. Crash `c` at `after-all-yes`. Show the `READY` locks, a `PENDING` reply,
   and how restart releases the locks after abort. Then use
   `after-first-decision` to show a peer sharing the saved outcome.

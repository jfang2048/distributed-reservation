# Tests and LAN Demo

Start with the [laptop testbed](testbed.md).
Run `./scripts/test.sh` with a JDK 17 or newer and Python 3.
Run `./verification/check.sh` for the [2PC model and code checks](../.agents/skills/verify-atomic-reservation/SKILL.md).
Put codec and log tests in `tests/unit/` and multi-node cases in
`tests/integration/`.
Record the key, fault point, votes, decision, and item state in each test.
Restart with the same directories.

## Checks

| Case | Expected result |
| --- | --- |
| Reserve across two servers | Both apply commit, then the client sees `COMMITTED`. |
| Include an unavailable item | The request aborts and any prepared locks are released. |
| Run conflicting and disjoint requests | At most one conflicting request commits. Both disjoint requests can commit. |
| Prepare two requests in opposite order | A held item triggers a no vote. Both requests may abort. |
| Ask for an item owned by the coordinator | The local vote and ACK count. Restart restores both roles. |
| Retry a key, then change its items | The retry gets the original result. Changed items get `ID_CONFLICT`. |
| Query an unseen key, then send its first request | The coordinator records abort and rejects the late start. |
| Replay an incomplete tail or a complete frame with a bad CRC | Drop the incomplete tail. Stop on the bad CRC. |

## Crash points

Set `fault.node`, `fault.point`, `fault.txId`, and `fault.action` in the
active properties file. Use `halt` to exit or `pause` to inspect peers before
stopping the process. Remove these settings before restarting. The server
prints `FAULT` with the point and transaction ID.

| Stop here | Look for |
| --- | --- |
| `after-start` | Restart forces abort. |
| `after-first-prepare` | The first voter can ask an unprepared peer for a durable no vote. |
| `after-all-yes` | Peers hold their locks until the coordinator restarts and aborts. |
| `after-decision` | Restart sends the saved decision. |
| `after-first-decision` | A waiting peer can learn the decision from the first peer to ACK. |
| `after-end` | Retrying returns the saved result. |
| `before-vote` | Keep the participant down until the coordinator records abort. |
| `after-vote` | Restart to see the prepared lock restored. |
| `before-decision-log` | Restart asks for the decision or takes a retried message. |
| `after-apply` | Replay applies the result and ACKs the retry. |

`kill -9` tests process recovery. A power-loss test needs a separate setup.

## LAN demo

1. Use the [deployment settings](deployment.md). Start the coordinator and
   participant A on laptop A, and participant B on laptop B.
2. Show a cross-server commit and two requests for the same item.
3. Stop the coordinator after both participants reach `READY` and before it
   records a decision. Show `PENDING` replies and try a conflicting request.
   Restart to show the abort and lock release. Then show the case where a
   peer already knows commit.
4. Use a few slides to show the components, process placement, and the logs
   that explain why the transaction waited.

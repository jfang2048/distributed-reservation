# Tests and LAN Demo

Start on one laptop with separate server processes, ports, and log directories.
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

Add a switch that pauses or exits a chosen node at a named point for one
transaction. Use a pause to check what peers have received, then stop the
process. Turn the fault off before restarting.

| Stop here | Look for |
| --- | --- |
| Coordinator, after forced `START_2PC`, before prepare | Restart forces abort. |
| Coordinator, after sending some prepares | A `READY` peer gets a durable no vote from `INIT`. A late prepare still gets no. |
| Coordinator, after all yes votes, before forcing a decision | All peers stay `READY` until restart. Then abort releases their locks. |
| Coordinator, after forcing commit, before sending it | Peers wait. Restart delivers commit. |
| Coordinator, after one peer has recorded commit | A waiting peer learns commit from that peer. Restart delivers the decision and collects ACKs. |
| Coordinator, after `END`, before the client reply | Retrying returns the saved result. |
| Participant, before forcing its yes vote | Keep it down until the coordinator times out and records abort. |
| Participant, after forcing yes, before replying | Restart before the decision to see the lock restored. |
| Participant, after receiving the decision, before forcing it | Restart asks for the decision or takes the retried message. |
| Participant, after forcing the decision, before ACK | Replay applies the result and ACKs the retry. |

`kill -9` tests process recovery. A power-loss test needs a separate setup.

## LAN demo

1. Start the coordinator and participant A on laptop A, and participant B on
   laptop B. Check addresses, TCP ports, firewall access, and writable log paths.
2. Show a cross-server commit and two requests for the same item.
3. Stop the coordinator after both participants reach `READY` and before it
   records a decision. Show `PENDING` replies and try a conflicting request.
   Restart to show the abort and lock release. Then show the case where a
   peer already knows commit.
4. Use a few slides to show the components, process placement, and the logs
   that explain why the transaction waited.

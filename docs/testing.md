# Tests and LAN Demo

These tests are planned. Run them first with separate Java processes on one
laptop. Give each server its own port and log directory. Start each case with
known resource state and reuse the log directories for every restart within
that case.

## Reservation and retry tests

| Case | Expected result |
| --- | --- |
| Request items on two servers | Both apply commit and the client receives `COMMITTED`. |
| Request an unavailable item with other free items | The failed transaction reserves no items. Prepared locks are released after abort. |
| Submit conflicting and disjoint requests at the same time | At most one conflicting request commits. Disjoint requests can both commit. |
| Force two requests to lock different items before each asks for the other | A held item triggers a no vote with no lock wait. Both requests may abort. |
| Request an item owned by the coordinator and one owned by a peer | The coordinator counts the local vote and ACK. A restart restores both roles from the same log. |
| Retry after disconnect, then reuse the key with different items | The retry preserves the original outcome. Different items return `ID_CONFLICT`. |
| Query the coordinator for an unseen key, then deliver a delayed first request | The coordinator records abort before replying. The late request cannot start that key. |
| Replay a copy of a log with an incomplete last frame | Replay keeps earlier records and drops only the incomplete tail. |
| Replay a copy with a bad checksum in a complete frame | Replay stops and the node does not serve requests. |

For each test, check the client result, saved decision, item states, and any
remaining locks. Log events by transaction key and node ID.

## Crash tests

Add opt-in fault points that target a node and a transaction. Pause or exit at
a named point and log which point was reached. Use a pause when the harness
needs to observe a peer's state before ending the process. Restart with that
fault point disabled. Repeat each case with the same message order.

| Fault point | What to show |
| --- | --- |
| Coordinator after forcing `START_2PC`, before any `PREPARE` | Recovery records abort. Participants accept abort even if they did not see prepare. |
| Coordinator after sending some prepares | Let one participant reach `READY` and keep another in `INIT`. The latter can force a no vote in reply to a peer query. A delayed prepare still receives no. |
| Coordinator after collecting all yes votes, before forcing a decision | Both participants stay `READY` and keep their locks while it is down. Recovery forces abort and releases the locks after delivery. |
| Coordinator after forcing commit, before sending it | While it is down, peers with only yes votes stay blocked. Recovery resends commit. |
| Coordinator after notifying one participant of commit | Confirm one participant saved commit before stopping the coordinator. The other can learn the decision from that peer. The restarted coordinator retries delivery and collects ACKs. |
| Coordinator after forcing `END`, before replying to the client | A retry gets the original result. No reservation is applied twice. |
| Participant before forcing its yes vote | No yes vote was sent. Keep the participant down until the coordinator times out and saves abort. |
| Participant after forcing its yes vote, before sending it | Restart while the decision is still unknown. The participant restores its lock and later applies the final decision. |
| Participant after receiving the decision, before saving it | After restart, the participant asks for the outcome or handles the retried decision. |
| Participant after forcing the decision, before sending `ACK` | Replay restores the outcome. A repeated decision receives the same ACK. |

A process exit or `kill -9` tests process recovery. It leaves the operating
system and usually its disk cache running. These tests cannot establish
durability after a power loss.

## Two-laptop demo

1. Finish the single-laptop tests first. On the same wired or wireless LAN,
   run the coordinator and participant A as separate processes on laptop A,
   and participant B on laptop B. Use separate ports and log directories.
   Check that the configured addresses resolve from both laptops, that the
   firewall allows the chosen TCP ports, and that paths are writable.
2. Run a transaction that reserves one item on each participant. Show the
   final result and both item states. Run two requests for the same item and
   show that at most one commits.
3. Stop only the coordinator after both participants vote yes and before
   it records a decision. Show both `READY` states, peer replies of
   `PENDING`, and a conflicting request that cannot acquire a prepared item.
   Restart the coordinator with its original log and show abort and lock
   release. Show the partial-notification case to contrast peer recovery.
4. Use a few slides to show the software components, process placement on
   both laptops, and the logs that explain where and why 2PC blocked.

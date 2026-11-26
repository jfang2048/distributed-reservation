# Test and Demo Plan

These cases are planned for the implementation. For each test, record the
transaction ID, client result, votes, saved decision, and resource state.

| Case | Expected result |
| --- | --- |
| Resources on two servers are available | Both servers commit their part of the reservation. |
| A resource is unavailable | The transaction aborts and leaves no reservation on either server. |
| Two clients request the same resource | At most one reservation commits. |
| A participant crashes before voting | The coordinator times out and aborts. |
| A participant crashes after voting yes | After restart, it restores its lock if needed and applies the final decision. |
| The coordinator crashes before saving a decision, after all participants vote yes | Participants block while the coordinator is down. On restart, it records and sends an abort. |
| The coordinator crashes after saving commit but before sending it to everyone | On restart, it resends commit. All participants reach the same outcome. |
| A client retries with the same transaction ID | The retry causes no second reservation. |

## LAN demo

1. Run one server on each of two laptops on the same LAN. Give each server
   different resources and its own log directory.
2. Run a client request that reserves one resource from each server. Show the
   committed reservation on both servers.
3. Run two requests for the same resource and show that at most one commits.
4. Stop the coordinator after the participants vote yes and before they learn
   a decision. Send a conflicting request to the surviving server and show
   that its prepared resource is still locked. Restart the coordinator with
   the same log and show the prepared transaction finish and release its locks.
5. Use a few slides to show the components, where they run, and the protocol
   events that explain the block.

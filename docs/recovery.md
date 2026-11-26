# Transaction Log and Recovery

## Log records

Each node writes one append-only log on local stable storage under its own
`run/` directory. Payloads start with format version `1`, role, and record type,
then the transaction key and the record data below. Use the same field encoding
as the [message protocol](protocol.md#messages-and-transaction-ids).
Serialize appends so records from both roles cannot interleave.

| Role | Record | Data and timing |
| --- | --- | --- |
| Coordinator | `START_2PC` | Sorted resource IDs and complete participant list. Force before sending any prepare. |
| Coordinator | `GLOBAL_COMMIT` or `GLOBAL_ABORT` | Final outcome. Force before sending or sharing it. An abort without a start marks the key as closed. |
| Coordinator | `END` | All participants have acknowledged the saved decision. Force before returning a final client result. |
| Participant | `INIT` | Participant list and local item IDs from the first prepare. Force before trying to lock the items. |
| Participant | `VOTE_COMMIT` or `VOTE_ABORT` | Vote, participant list, and local item IDs when known. Force before sending the vote or sharing a no vote. |
| Participant | `GLOBAL_COMMIT` or `GLOBAL_ABORT` | Outcome and local item IDs for replay. Force before applying the outcome or sending an ACK. |

Each frame uses a four-byte big-endian payload length, a payload, and a
four-byte CRC32 over the length and payload. Cap the payload at 1 MiB. Write
the whole record through `FileChannel` and call `force(true)` after each record.
If writing or forcing fails, stop serving and recover from the log before
sending further protocol messages.

Replay checks lengths and checksums. If the file ends mid-frame, discard only
that incomplete tail and force the truncation before appending again. Stop
recovery on an invalid length, a checksum mismatch in a complete frame, or a
record inconsistent with earlier state. Never skip a damaged record and serve
from a partial history. The design assumes stable local storage as stated in
the [failure model](requirements.md#failure-assumptions).
See [Java 17 FileChannel.force](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/nio/channels/FileChannel.html#force(boolean)).

## Restart rules

- **Coordinator.** Replay before accepting requests. Force abort for each
  `START_2PC` with no saved decision. If a decision exists without `END`,
  resend it to the full participant list, including the local role if present.
  ACKs need no separate records because repeat decisions are safe.
- **Participant.** Restore committed items and locks from yes votes without
  a final outcome before processing new requests. Replay a final outcome
  even if the process crashed before applying it or sending an ACK. Ask for
  a decision for any transaction still in `READY`. An `INIT` record without
  a vote restores no locks and can still lead to a durable no vote.
- **Unknown key.** After replay, the named coordinator can answer a valid
  participant decision query for an unseen key with abort. It first forces an
  abort record under the same per-key guard used for `START_2PC`. That record
  permanently closes the key, including against a delayed first request.
  An active key without a decision returns `PENDING`. An ordinary first
  `RESERVE` for an unseen key still starts a transaction. A peer uses the
  [decision request rules](protocol.md#decision-requests).
- **Lost client reply.** If the coordinator crashed after saving commit, a
  repeated request with the same key and resource set returns the original
  outcome once all ACKs are in. It may return `PENDING` before then.
  The coordinator never starts another transaction for that key.

## Retention

After `END`, drop the coordinator's active timers, vote collection, and
delivery state. Keep the request, final decision, completion marker, and
participant outcome records for the life of the run. Those records let the
system rebuild reservations, answer a decision request, and handle old
retries. The log grows with transactions. To clean up demo data, stop all
nodes and reset all run directories together to start a fresh run. Restart
tests reuse the original log directories.

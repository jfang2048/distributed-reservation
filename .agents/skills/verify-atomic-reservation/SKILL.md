---
name: verify-atomic-reservation
description: Check 2PC atomicity after changes to vote collection, decision delivery, peer termination, or crash recovery in distributed-reservation.
---

# Verify atomic reservation

A commit requires a durable yes from every participant. Every applied
outcome must agree with any coordinator decision and with other participants.
A split could reserve only part of a client's request.

## Run from the repository root

Run `./verification/check.sh` with JDK 17+, Python 3, and curl. It checks the
TLA+ model with TLC 1.7.4, then runs four TCP tests against the Java sources.
Files and traces go under `build/verification/`.

Run `./verification/check.sh --negative-model` to allow commit after one
vote in a copy of the model configuration. Expect an `AtomicCommit` violation.
When checking a change to the commit gate, also try replacing
`tx.replied.size() == tx.endpoints.size()` with `tx.replied.size() == 1` in
`Coordinator.vote` in a scratch checkout. The Java test for a participant
crashing before its vote should reject that change.

Use `/statechart` to read every TLC result. For a violation, read the shortest
trace in order and map each step to the code below. For a pass, use it to show
the modeled transitions. The runner keeps the raw TLC log for use on its own.

## Coverage

| Model transition | Java |
| --- | --- |
| Prepare, vote delivery, timeout, durable decision | [Coordinator](../../../src/reservation/Coordinator.java) `reserve`, `sendPhase`, `receive`, `vote`, `tick`, `decide` |
| Durable vote and peer termination | [Participant](../../../src/reservation/Participant.java) `prepare`, `recordVote`, `query`, `learn`, `apply` |
| Crash and restart | `Coordinator.recover`, `Participant.replay`, [Server](../../../src/reservation/Server.java) `run`, `handle`, `persist` |
| Forced records | [TransactionLog](../../../src/reservation/TransactionLog.java) `append`, `replay` |

The model covers one transaction, two participant roles, one coordinator
role, arbitrary message delivery, and crash/restart with intact forced
records. A durable transition represents a forced write and its publication
under the server monitor. Duplicate sends share one delivery marker. A
coordinator query can deliver the same saved outcome as a decision message.
The model checks safety with trusted nodes. Separate requests, disk
corruption, time bounds, and eventual completion are outside this model.
Use the Java tests as a check on the implementation behavior.

The model was synced to Java commit `da9c719f05775f00ee8f2b710d800672dfbd1704`.
Compare the covered functions with that commit. For each changed transition,
check that its preconditions, forced writes, and visible effects still match
the model. Run both commands before updating the sync commit. Report a real
counterexample before changing the property. Use
`/maintain-formal-verification-skill` to update this check after later edits.

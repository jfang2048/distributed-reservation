-------------------------- MODULE AtomicReservation --------------------------

CONSTANT AllowEarlyCommit

Participants == {"a", "b"}
Coordinator == "coordinator"
Nodes == Participants \cup {Coordinator}
Decisions == {"COMMIT", "ABORT"}
Votes == {"UNVOTED", "YES", "NO"}

VARIABLES coordinator, votes, applied, online, seenYes, sent, acked, ended

vars == <<coordinator, votes, applied, online, seenYes, sent, acked, ended>>

Init ==
    /\ coordinator = "IDLE"
    /\ votes = [p \in Participants |-> "UNVOTED"]
    /\ applied = [p \in Participants |-> "OPEN"]
    /\ online = Nodes
    /\ seenYes = {}
    /\ sent = [prepare |-> {}, vote |-> {}, decision |-> {},
               peer |-> [p \in Participants |-> "OPEN"]]
    /\ acked = {}
    /\ ended = FALSE

TypeOK ==
    /\ AllowEarlyCommit \in BOOLEAN
    /\ coordinator \in {"IDLE", "WAIT"} \cup Decisions
    /\ votes \in [Participants -> Votes]
    /\ applied \in [Participants -> {"OPEN"} \cup Decisions]
    /\ online \subseteq Nodes
    /\ seenYes \subseteq Participants
    /\ sent \in [prepare : SUBSET Participants, vote : SUBSET Participants,
                 decision : SUBSET Participants,
                 peer : [Participants -> {"OPEN"} \cup Decisions]]
    /\ acked \subseteq Participants
    /\ ended \in BOOLEAN

Start ==
    /\ Coordinator \in online
    /\ coordinator = "IDLE"
    /\ coordinator' = "WAIT"
    /\ UNCHANGED <<votes, applied, online, seenYes, sent, acked, ended>>

\* Issued messages can be delivered independently, in any order, or delayed
\* forever. One marker covers any number of duplicate sends or retries.
SendPrepare(p) ==
    /\ Coordinator \in online
    /\ coordinator = "WAIT"
    /\ p \notin sent.prepare
    /\ sent' = [sent EXCEPT !.prepare = @ \cup {p}]
    /\ UNCHANGED <<coordinator, votes, applied, online, seenYes, acked, ended>>

DeliverPrepare(p, vote) ==
    /\ p \in online \cap sent.prepare
    /\ votes[p] = "UNVOTED"
    /\ applied[p] = "OPEN"
    /\ votes' = [votes EXCEPT ![p] = vote]
    /\ UNCHANGED <<coordinator, applied, online, seenYes, sent, acked, ended>>

SendVote(p) ==
    /\ p \in online
    /\ votes[p] \in {"YES", "NO"}
    /\ p \notin sent.vote
    /\ sent' = [sent EXCEPT !.vote = @ \cup {p}]
    /\ UNCHANGED <<coordinator, votes, applied, online, seenYes, acked, ended>>

DeliverVote(p) ==
    /\ Coordinator \in online
    /\ coordinator = "WAIT"
    /\ p \in sent.vote
    /\ IF votes[p] = "YES"
       THEN /\ p \notin seenYes
            /\ seenYes' = seenYes \cup {p}
            /\ UNCHANGED coordinator
       ELSE /\ votes[p] = "NO"
            /\ coordinator' = "ABORT"
            /\ UNCHANGED seenYes
    /\ UNCHANGED <<votes, applied, online, sent, acked, ended>>

Commit ==
    /\ Coordinator \in online
    /\ coordinator = "WAIT"
    /\ seenYes = Participants \/ (AllowEarlyCommit /\ seenYes # {})
    /\ coordinator' = "COMMIT"
    /\ UNCHANGED <<votes, applied, online, seenYes, sent, acked, ended>>

VoteTimeout ==
    /\ Coordinator \in online
    /\ coordinator = "WAIT"
    /\ seenYes # Participants
    /\ coordinator' = "ABORT"
    /\ UNCHANGED <<votes, applied, online, seenYes, sent, acked, ended>>

SendDecision(p) ==
    /\ Coordinator \in online
    /\ coordinator \in Decisions
    /\ p \notin sent.decision
    /\ sent' = [sent EXCEPT !.decision = @ \cup {p}]
    /\ UNCHANGED <<coordinator, votes, applied, online, seenYes, acked, ended>>

DeliverDecision(p) ==
    /\ p \in online \cap sent.decision
    /\ applied[p] = "OPEN"
    /\ coordinator = "ABORT" \/ votes[p] = "YES"
    /\ applied' = [applied EXCEPT ![p] = coordinator]
    /\ UNCHANGED <<coordinator, votes, online, seenYes, sent, acked, ended>>

Ready(p) == votes[p] = "YES" /\ applied[p] = "OPEN"

PromiseNo(p, q) ==
    /\ p # q
    /\ {p, q} \subseteq online
    /\ Ready(p)
    /\ votes[q] = "UNVOTED"
    /\ applied[q] = "OPEN"
    /\ votes' = [votes EXCEPT ![q] = "NO"]
    /\ UNCHANGED <<coordinator, applied, online, seenYes, sent, acked, ended>>

PeerProof(q) ==
    IF applied[q] \in Decisions THEN applied[q]
    ELSE IF votes[q] = "NO" THEN "ABORT" ELSE "OPEN"

SendPeerProof(p, q) ==
    /\ p # q
    /\ {p, q} \subseteq online
    /\ Ready(p)
    /\ PeerProof(q) \in Decisions
    /\ sent.peer[p] = "OPEN"
    /\ sent' = [sent EXCEPT !.peer[p] = PeerProof(q)]
    /\ UNCHANGED <<coordinator, votes, applied, online, seenYes, acked, ended>>

DeliverPeerProof(p) ==
    /\ p \in online
    /\ Ready(p)
    /\ sent.peer[p] \in Decisions
    /\ applied' = [applied EXCEPT ![p] = sent.peer[p]]
    /\ UNCHANGED <<coordinator, votes, online, seenYes, sent, acked, ended>>

\* One step covers a matching coordinator delivery and the ACK reaching it.
Acknowledge(p) ==
    /\ {Coordinator, p} \subseteq online
    /\ p \in sent.decision \ acked
    /\ applied[p] = coordinator
    /\ ~ended
    /\ acked' = acked \cup {p}
    /\ UNCHANGED <<coordinator, votes, applied, online, seenYes, sent, ended>>

End ==
    /\ Coordinator \in online
    /\ acked = Participants
    /\ ~ended
    /\ ended' = TRUE
    /\ UNCHANGED <<coordinator, votes, applied, online, seenYes, sent, acked>>

Crash(n) ==
    /\ n \in online
    /\ online' = online \ {n}
    /\ seenYes' = IF n = Coordinator THEN {} ELSE seenYes
    /\ acked' = IF n = Coordinator THEN {} ELSE acked
    /\ UNCHANGED <<coordinator, votes, applied, sent, ended>>

Restart(n) ==
    /\ n \notin online
    /\ online' = online \cup {n}
    /\ coordinator' = IF n = Coordinator /\ coordinator = "WAIT"
                      THEN "ABORT" ELSE coordinator
    /\ UNCHANGED <<votes, applied, seenYes, sent, acked, ended>>

Next ==
    \/ Start
    \/ Commit
    \/ VoteTimeout
    \/ End
    \/ \E n \in Nodes : Crash(n) \/ Restart(n)
    \/ \E p \in Participants :
          \/ SendPrepare(p)
          \/ \E vote \in {"YES", "NO"} : DeliverPrepare(p, vote)
          \/ SendVote(p)
          \/ DeliverVote(p)
          \/ SendDecision(p)
          \/ DeliverDecision(p)
          \/ \E q \in Participants : PromiseNo(p, q) \/ SendPeerProof(p, q)
          \/ DeliverPeerProof(p)
          \/ Acknowledge(p)

Spec == Init /\ [][Next]_vars

AtomicCommit ==
    /\ ~(\E p, q \in Participants : applied[p] = "COMMIT" /\ applied[q] = "ABORT")
    /\ (coordinator = "COMMIT" => \A p \in Participants : votes[p] = "YES")
    /\ \A p \in Participants :
          /\ (applied[p] = "COMMIT" => coordinator = "COMMIT")
          /\ (applied[p] = "ABORT" =>
                coordinator = "ABORT" \/ \E q \in Participants : votes[q] = "NO")
          /\ (ended => applied[p] = coordinator)

=============================================================================

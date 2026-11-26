# Requirements

Politecnico di Milano · Distributed Systems (090950) · 2026/2027

## System

- Servers manage separate resources. Clients can request resources from several
  servers in one transaction.
- Use Two-Phase Commit (2PC). One server coordinates each transaction. The
  servers that own the requested resources take part in it.
- Reserve either all requested resources or none. Concurrent requests must
  never commit conflicting reservations.
- Use stable storage for transaction logs and recover safely after a restart.
- Test crashes at different protocol stages. Show when a reservation blocks
  and how it resumes after recovery.

## Failure assumptions

- Servers can crash and eventually restart. They follow the protocol while
  running.
- Communication can be delayed or temporarily interrupted. Network partitions
  eventually heal.
- Each server has reliable stable storage.
- During prepare, a server can reject a request if a resource is unavailable
  or held by another pending reservation.

## Course delivery

- Work in a group of two or three students.
- Demonstrate the system on at least two laptops connected to the same LAN.
- Use a few slides to explain the software and how it runs across the laptops.
- Present before the last official exam session for the academic year.

## Project choices

The course allows sockets or RMI for Java networking. This project uses
Java 17, the standard library, and TCP sockets. TCP handles packet loss
within a live connection. On timeout or disconnection, an application reply
may be missing, so nodes can reconnect and retry a message. Restarts reuse
the same node identity and log. Disk loss and corruption of previously
forced records fall outside the stable-storage assumption.

The [architecture](architecture.md) defines the resource and lock model.
The [protocol](protocol.md) follows the 2PC state changes in slides 7–11 of
`07-agreement.pdf`. The [test and demo plan](testing.md) covers the course
requirements for concurrency, crash recovery, blocking, and the LAN demo.

See the general rules on p. 1 and “Fault-tolerant distributed reservation
system” on pp. 2–3 of the course handout `Progetti.pdf`.

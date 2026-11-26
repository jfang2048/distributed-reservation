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

The project uses Java 17, the standard library, and TCP sockets. The course
allows sockets or RMI for Java networking.

See the general rules on p. 1 and “Fault-tolerant distributed reservation
system” on pp. 2–3 of the course handout `Progetti.pdf`.

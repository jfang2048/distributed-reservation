# Course Requirements

## System

- Use Two-Phase Commit (2PC) for requests that span servers.
- Give each transaction a coordinator. Each server that owns a requested
  resource takes part as a participant.
- Commit the whole reservation or abort it. Allow concurrent requests while
  enforcing exclusive reservations for conflicting requests.
- Save transaction state on stable storage and recover after a server restarts.
- Crash the coordinator and participants at several protocol stages. Show
  when transactions block and what lets them finish.

## Failure assumptions

Servers follow the protocol and eventually restart after a crash. Each
server has reliable stable storage. Messages can be delayed, and connections
can drop and reconnect. Partitions eventually heal. A participant can vote
abort if a resource is unavailable or held by another transaction.

## Delivery

- Work in a team of two or three.
- Demo on at least two laptops on the same wired or wireless LAN.
- Use a few slides to show the software and where it runs.
- Present before the last official exam session of the academic year.

Java projects use sockets or RMI for networking. We chose Java 17 and TCP.

See `Progetti.pdf`, p. 1 for the course rules and pp. 2–3 for the assignment.

# Server Roles and Resource Locks

## Servers

Every node runs the same Java server. Configuration maps node IDs to
TCP addresses and items to their owners. On restart, reuse these mappings
and the node's log directory.

The client chooses a coordinator and retries with that same node. Item
owners are the participants. If the coordinator owns an item in the request,
it runs the participant steps locally and counts its own durable vote and ACK.
Its log tags records by role.

## Resources

A resource is one exclusive item, such as a piece of equipment. A request
names one or more distinct items. Each item has a single owner and stays
reserved once committed, including after a restart.

During `PREPARE`, a participant checks and claims its items together in one
short in-memory operation. It either claims the full set or votes abort if
an item is reserved or held. Locks belong to transactions. A `READY`
participant holds them until it records and applies the outcome. Each role
handles state changes for a given transaction one at a time.

Trying locks once prevents a cycle of transactions waiting on each other.
Two competing requests can both abort. Repeated clashes can cause livelock or
starvation. After a final abort, a client can try a fresh transaction after
a short random delay.

See the [Java class layout](implementation.md#code-layout).

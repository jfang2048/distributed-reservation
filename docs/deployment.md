# Deployment

## Shared settings

Copy [`config/cluster.lan.properties.example`](../config/cluster.lan.properties.example)
to `config/cluster.properties`. Edit that file and use the same copy on each
machine. Use Java `Properties.load(Reader)` with a UTF-8 reader.

Set `node.<id>.host` to the server IP or DNS name that the client and the
other server can reach. Set `node.<id>.port` to its reachable TCP port. Map
each item to its server with `resource.<id>.owner`. Use `client.coordinator`
to select the client's default server.

Set optional values in the same file.

| Key | Default | Use |
| --- | --- | --- |
| `node.<id>.bind` | `0.0.0.0` | Listen on a local IPv4 address. |
| `node.<id>.listenPort` | `node.<id>.port` | Set the local port separately for NAT. |
| `node.<id>.logDir` | `run/<id>` | Keep each server's log on local storage and reuse it on restart. Relative paths start at the working directory. |
| `tcp.connectTimeoutMs`, `tcp.readTimeoutMs` | `2000` each | Bound TCP connect and read calls. |
| `tx.voteTimeoutMs` | `5000` | Give the coordinator time to collect votes. |
| `tx.retryDelayMs` | `500` | Space out decision delivery and queries. |

## Where to run it

| Setup | Addresses to enter |
| --- | --- |
| Client on a third machine, servers on two LAN machines | Enter both servers' LAN IPs. For bridged VMs, use the guest IPs. For NAT VMs, use the reachable host IPs and forwarded ports. |
| Client shares a machine with server `a` | Use the same two LAN addresses. Server `b` uses `a`'s LAN address for peer messages. |
| Servers on public hosts | Connect the machines through a private VPN and enter the VPN addresses. Allow TCP only from the trusted client and peer servers. |

With NAT, forward each advertised TCP port to its server's listening port.
Check that the addresses also work from any client or peer inside the LAN.
TCP messages rely on the network for access control and encryption. Use a
trusted LAN or private VPN for the client and both servers.

## Linux and Windows

Use Java 17 or newer on each machine. Build the JAR with `./scripts/build.sh`
on the development machine. Copy `build/reservation.jar` and
`config/cluster.properties` to the same relative paths on each destination.
Run from their parent directory in a shell or PowerShell. Start one server
per node and run `reserve` on the client machine.

```sh
java -jar build/reservation.jar server --node a
java -jar build/reservation.jar server --node b
java -jar build/reservation.jar reserve projector camera
```

The client connects to `client.coordinator`. Use `--coordinator b` to choose
another node for a request. The client prints its UUID before sending. To
retry, pass `--tx-id <uuid>` with the original coordinator and items.

## Check the connection

After startup, check both endpoints from the client and each server's
endpoint from its peer. Allow the configured TCP ports in each server's
firewall. Use `nc -vz 192.168.1.22 7002` on Linux or
`Test-NetConnection 192.168.1.22 -Port 7002` in PowerShell. Then reserve
`projector camera` and check the decision on both servers.

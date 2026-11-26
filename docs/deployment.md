# Deployment

## Shared settings

Save the same `config/cluster.properties` on each machine. Use Java
`Properties.load(Reader)` with a UTF-8 reader.

```properties
node.a.host=192.168.1.21
node.a.port=7001
node.b.host=192.168.1.22
node.b.port=7002
resource.projector.owner=a
resource.camera.owner=b
client.coordinator=a
```

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
| All three processes on one machine | Set both hosts and bind addresses to `127.0.0.1`. Keep ports `7001` and `7002` distinct. |
| Servers on public hosts | Use reachable public DNS names or IPs and allow TCP from the client and peer server at each firewall. For encrypted links, connect through a VPN and enter the VPN addresses. |

With NAT, forward each advertised TCP port to its server's listening port.
Check that the addresses also work from any client or peer inside the LAN.

## Command interface

Run from the directory with `build/` and `config/`. Start one `server` per
node and run `reserve` on the client machine.

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
endpoint from its peer. For example, run `nc -vz 192.168.1.22 7002` from
the client and from server `a`. Then reserve `projector camera` through
coordinator `a` and check that both servers record the same final decision.

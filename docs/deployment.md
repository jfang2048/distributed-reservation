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
```

Set `node.<id>.host` to the server IP or DNS name that the client and the
other server can reach. Set `node.<id>.port` to its reachable TCP port. Map
each item to its server with `resource.<id>.owner`.

## Where to run it

| Setup | Addresses to enter |
| --- | --- |
| Client on a third machine, servers on two LAN machines | Enter both servers' LAN IPs. For bridged VMs, use the guest IPs. For NAT VMs, use the reachable host IPs and forwarded ports. |
| Client shares a machine with server `a` | Use the same two LAN addresses. Server `b` uses `a`'s LAN address for peer messages. |
| All three processes on one machine | Set both hosts to `127.0.0.1` and keep ports `7001` and `7002` distinct. |
| Servers on public hosts | Use reachable public DNS names or IPs and allow TCP from the client and peer server at each firewall. For encrypted links, connect through a VPN and enter the VPN addresses. |

With NAT, forward each advertised TCP port to its server's listening port.
Check that the addresses also work from any client or peer inside the LAN.

## Command interface

Use these commands as the implementation target. Run one `server` command
for each node and run `reserve` on the client machine.

```sh
java -jar build/reservation.jar server --config config/cluster.properties --node a
java -jar build/reservation.jar server --config config/cluster.properties --node b
java -jar build/reservation.jar reserve --config config/cluster.properties --coordinator a projector camera
```

- `--bind` selects the local interface. It defaults to `0.0.0.0` for all IPv4
  interfaces. Use `--bind 127.0.0.1` for the single-machine setup.
- `--listen-port` defaults to `node.<id>.port`. Set it separately when NAT
  maps the advertised port to a different local port.
- `--log-dir` defaults to `run/<id>` under the working directory. Use a
  separate local directory for each server and reuse it across restarts.
- `--coordinator a` tells the client to connect to `node.a.host` and
  `node.a.port`. The client prints its UUID before sending the request.
  On retry, pass `--tx-id <uuid>` with the same coordinator and items.

## Check the connection

After startup, check both endpoints from the client and each server's
endpoint from its peer. For example, run `nc -vz 192.168.1.22 7002` from
the client and from server `a`. Then reserve `projector camera` through
coordinator `a` and check that both servers record the same final decision.

# Deployment

Clone the repository or download its ZIP. Build with a JDK 17 or newer and
Python 3.10 or newer. Use `py -3` or `python` in place of `python3` on Windows.

```sh
python3 scripts/build.py
```

## On one machine

Run these commands in three terminals. Wait for both servers to print
`LISTENING` before running the client.

```sh
python3 scripts/local.py server --node a
python3 scripts/local.py server --node b
python3 scripts/local.py reserve projector camera
```

Edit [the loopback profile](../config/cluster.local.properties) for local ports.
The launcher keeps the active settings and logs under `run/local/`.

## On a LAN

Copy [the LAN example](../config/cluster.lan.properties.example) to
`config/cluster.properties`. Fill in the server addresses. Copy this file
and `build/reservation.jar` to the same relative paths on each machine.
Use Java 17 or newer. Run from the directory containing `build/` and `config/`.

```sh
java -jar build/reservation.jar server --node a
java -jar build/reservation.jar server --node b
java -jar build/reservation.jar reserve projector camera
```

Use a trusted LAN or private VPN. Allow both TCP ports from the client and
between the servers. Check reachability with `nc -vz HOST PORT` on Linux or
macOS or `Test-NetConnection HOST -Port PORT` on Windows. For NAT, forward the
advertised port to the node's listening port.

## Settings

| Key | Value |
| --- | --- |
| `node.<id>.host`, `node.<id>.port` | Reachable IP or DNS name and TCP port |
| `resource.<id>.owner` | Server that owns the item |
| `client.coordinator` | Default coordinator |
| `node.<id>.bind`, `node.<id>.listenPort` | Default to `0.0.0.0` and the advertised port |
| `node.<id>.logDir` | Default `run/<id>` relative to the working directory. Reuse it on restart. |
| `tcp.connectTimeoutMs`, `tcp.readTimeoutMs` | Default `2000` each |
| `tx.voteTimeoutMs`, `tx.retryDelayMs` | Default `5000` and `500` |

The client prints the transaction UUID before sending. Use
`--coordinator <id>` to choose a server and `--tx-id <uuid>` to retry.
Reuse the coordinator and item IDs on retry.

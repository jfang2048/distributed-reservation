# Test on One Machine

Build the JAR, then open three terminals in the repository. Run one command in
each terminal and wait for both servers to print `LISTENING` before running the
client. On Windows, use `py -3` or `python` in place of `python3`.

```sh
python3 scripts/build.py
```

```sh
python3 scripts/local.py server --node a
python3 scripts/local.py server --node b
python3 scripts/local.py reserve projector camera
```

The client prints `COMMITTED` for a fresh reservation. Edit
`config/cluster.local.properties` to change the loopback ports. The launcher
uses `run/local/` for the active configuration and logs.

See the [test plan](testing.md) for crash cases and the
[deployment guide](deployment.md) for running on separate machines.

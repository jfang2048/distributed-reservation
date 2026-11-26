# Laptop Testbed

## Three local processes

Use a JDK 17 or newer. Set `JAVA_HOME` if the JDK lives outside `PATH`. Run
the two `server` commands in separate terminals.

```sh
./scripts/build.sh
./scripts/local.sh server --node a
./scripts/local.sh server --node b
./scripts/local.sh reserve projector camera
```

Edit `config/cluster.local.properties` for loopback ports and local settings.
The launcher uses `run/local/` for the active settings and transaction logs.

## Two local VMs

Use Linux with KVM/libvirt, `virt-install`, `genisoimage`, and `ubuntu-keyring`.
Use the active `default` storage pool on `qemu:///system`.
The laptop runs the client. Ubuntu VMs `reservation-a` and `reservation-b`
run the servers on a dedicated libvirt NAT network. Their addresses are in
`config/cluster.vm.properties`. The VM scripts use the assigned addresses
and the default log directories `run/a` and `run/b`.

```sh
./infra/provision.sh
./scripts/build.sh
./infra/deploy.sh
(cd run/vm && java -jar ../../build/reservation.jar reserve projector camera)
```

Provisioning seeds both guests with cloud-init. Deployment starts one
systemd service per node and keeps its transaction log across restarts.
Check ports `7001` and `7002` after log replay before running the client.

## VM experiments

| Command | Action |
| --- | --- |
| `./infra/network.sh wan` | Add delay and loss between the VMs. |
| `./infra/network.sh clear` | Clear link shaping. |
| `./infra/fault.sh partition a b` | Drop traffic between nodes A and B. |
| `./infra/fault.sh heal` | Remove the partition. |
| `./infra/fault.sh crash b` | Kill B's Java process. |
| `./infra/fault.sh restart b` | Start B again with its saved log. |
| `./infra/fault.sh poweroff b` / `./infra/fault.sh boot b` | Cut VM power or boot it again. |
| `./infra/collect-logs.sh` | Save both nodes' logs under `run/`. |
| `./infra/reset.sh --yes` | Clear both lab nodes' transaction data and restore the testbed. |

For tests with a separate coordinator, add `node.c.host=192.168.151.1`
and `node.c.port=7003` to the VM profile. Deploy the profile again, run
`server --node c` from `run/vm` on the laptop, and reserve with
`--coordinator c`.

Use the [test plan](testing.md) for expected outcomes. Use the
[deployment guide](deployment.md) when moving to other machines.

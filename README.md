# Distributed Reservation System

A Java 17 reservation system. Servers use Two-Phase Commit (2PC) over TCP
to reserve items in one transaction and recover them after a crash.

Politecnico di Milano · Distributed Systems (090950) · [Project 2](docs/testing.md#course-demo) · 2026/2027

## Architecture

See [server roles and locks](docs/protocol.md#roles-and-keys).

```mermaid
flowchart LR
    C[Client] -->|TCP| S[Coordinator]
    S <-->|2PC| A[Participant A]
    S <-->|2PC| B[Participant B]
    A <-->|Decision request| B
    S --- L0[(Transaction log)]
    A --- L1[(Transaction log)]
    B --- L2[(Transaction log)]
```

## Commit flow

```mermaid
sequenceDiagram
    participant C as Client
    participant S as Coordinator
    participant A as Participant A
    participant B as Participant B
    C->>S: RESERVE
    Note over S: Force START_2PC
    S-->>C: PENDING
    S->>A: PREPARE
    S->>B: PREPARE
    Note over A,B: Force INIT, lock items, force vote
    A-->>S: VOTE_COMMIT
    B-->>S: VOTE_COMMIT
    Note over S: Force GLOBAL_COMMIT
    S->>A: GLOBAL_COMMIT
    S->>B: GLOBAL_COMMIT
    Note over A,B: Force decision, apply it, release locks
    A-->>S: ACK
    B-->>S: ACK
    Note over S: Force END
    C->>S: RESERVE with the same transaction ID
    S-->>C: COMMITTED
```

See [decision rules](docs/protocol.md#prepare-and-decide) and
[restart behavior](docs/protocol.md#replay).

## Project structure

```text
distributed-reservation/
├── .github/
│   └── workflows/
│       └── test.yml                   # Linux, macOS, and Windows CI
├── .gitignore
├── README.md
├── config/                            # Cluster profiles
│   ├── cluster.lan.properties.example # Physical machines
│   └── cluster.local.properties       # Loopback
├── docs/
│   ├── deployment.md                  # Build, run, and configure
│   ├── protocol.md                    # 2PC and recovery
│   └── testing.md                     # Tests and course demo
├── scripts/                           # Local development commands
│   ├── build.py                       # Build the JAR
│   ├── local.py                       # Run on loopback
│   └── test.py                        # Run the test suite
├── src/                               # Java application
│   └── reservation/
│       ├── Client.java
│       ├── ClusterConfig.java
│       ├── Coordinator.java
│       ├── Endpoint.java
│       ├── Faults.java
│       ├── FrameFields.java
│       ├── Main.java
│       ├── Message.java
│       ├── Participant.java
│       ├── ResourceManager.java
│       ├── Server.java
│       ├── TransactionLog.java
│       ├── Transport.java
│       ├── TxId.java
│       ├── Type.java
│       └── Wire.java
└── tests/
    ├── integration/                   # TCP and crash tests
    │   └── test_cluster.py
    └── unit/                          # Codec, log, and locks
        └── UnitTests.java
```

## Run

Use the [deployment guide](docs/deployment.md) to run on one machine or across
a LAN. See [tests and the course demo](docs/testing.md) to check the behavior.

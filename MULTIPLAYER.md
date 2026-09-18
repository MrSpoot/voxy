# Multiplayer

Voxy uses a server-authoritative multiplayer model (protocol version 3). Normal solo games also run through the same in-process protocol, which keeps solo and multiplayer gameplay behavior aligned.

## Launch modes

Build the runnable game JAR and the dedicated server JAR:

```powershell
.\mvnw.cmd package
```

Start a solo game (local in-process server):

```powershell
java -jar target/voxy-0.0.1.jar --name=Alice
```

Host a LAN game and play in it:

```powershell
java -jar target/voxy-0.0.1.jar --host --port=25565 --name=Alice --max-players=16 --seed=1052002 --view-distance=12
```

Join a host by direct IP:

```powershell
java -jar target/voxy-0.0.1.jar --connect=192.168.1.20:25565 --name=Bob --view-distance=12
```

Start the lightweight headless dedicated server:

```powershell
java -jar target/voxy-0.0.1-server.jar --port=25565 --max-players=16 --seed=1052002 --view-distance=12
```

The server artifact contains no windowing, rendering, ImGui, textures, shaders or native graphics libraries. It only needs Java 25 and does not require `--dedicated`. The full game JAR remains compatible with the previous command:

```powershell
java -jar target/voxy-0.0.1.jar --dedicated --port=25565 --max-players=16 --seed=1052002 --view-distance=12
```

The host must allow inbound TCP traffic on the selected port. Internet play requires manual port forwarding or a VPN/LAN overlay; NAT traversal and matchmaking are not part of this version.
The default network view distance is 12 chunks and can be configured from 2 to 32.

## Current behavior

- The server simulates players, collision, block placement/destruction, hotbars, chunk streaming and lighting at 30 ticks per second.
- Player snapshots are sent at 15 Hz. The local player uses prediction/reconciliation; remote players use interpolation.
- Chunk data is compressed and streamed according to each player's view distance. Mesh generation happens on clients only.
- Block edits remain authoritative and survive chunk unload/reload for the lifetime of the server process.
- Connections are rejected when the protocol version or ordered block catalogue differs.

This first version intentionally has no account authentication, encryption, matchmaking, chat, NAT traversal, disk persistence or reconnect-to-the-same-player state. A reconnect is treated as a new arrival, and the world is reset when the dedicated server process stops.

# Multiplayer

Voxy uses a server-authoritative multiplayer model (protocol version 5). Normal solo games also run through the same in-process protocol, which keeps solo and multiplayer gameplay behavior aligned.

## Launch modes

Build the runnable game JAR and the dedicated server JAR:

```powershell
.\mvnw.cmd package
```

Start a solo game (local in-process server):

```powershell
java -jar target/voxy-0.0.1.jar --profile=default --name=Alice --world=default
```

Host a LAN game and play in it:

```powershell
java -jar target/voxy-0.0.1.jar --host --port=25565 --profile=default --name=Alice --world=shared --max-players=16 --seed=1052002 --view-distance=12
```

Join a host by direct IP:

```powershell
java -jar target/voxy-0.0.1.jar --connect=192.168.1.20:25565 --name=Bob --view-distance=12
```

Start the lightweight headless dedicated server:

```powershell
java -jar target/voxy-0.0.1-server.jar --world=shared --port=25565 --max-players=16 --seed=1052002 --simulation-distance=12 --default-render-distance=12
```

The server artifact contains no windowing, rendering, ImGui, textures, shaders or native graphics libraries. It only needs Java 25 and does not require `--dedicated`. The full game JAR remains compatible with the previous command:

```powershell
java -jar target/voxy-0.0.1.jar --dedicated --port=25565 --max-players=16 --seed=1052002 --view-distance=12
```

The host must allow inbound TCP traffic on the selected port. Internet play requires manual port forwarding or a VPN/LAN overlay; NAT traversal and matchmaking are not part of this version.
The default network view distance is 12 chunks and can be configured from 2 to 32.

## Current behavior

- The server simulates players, collision, block placement/destruction, hotbars, chunk streaming and lighting at 30 ticks per second.
- Player snapshots are sent at 15 Hz. The local player uses movement and block-action prediction with authoritative reconciliation; remote players use interpolation.
- Chunk data is compressed and streamed according to each player's view distance. Mesh generation happens on clients only.
- Block placement and destruction are applied immediately by the client, then confirmed or rolled back from the server's authoritative result. Confirmed edits survive chunk unloads and server restarts.
- Connections are rejected when the protocol version or ordered block catalogue differs.
- The client sends a stable local profile UUID during the handshake. The server restores that profile's pose, movement state and creative hotbar from the selected world.
- Worlds autosave every 60 seconds by default and always commit once during a clean shutdown. Use `--autosave-seconds=0` to disable only periodic saves.

Account authentication, encryption, matchmaking, chat and NAT traversal remain outside this version. A profile UUID identifies saved state but is not an online identity or authentication credential.

## Persistent data

`--data-dir=<path>` overrides the platform data directory. `--world=<key>` and `--profile=<key>` select isolated saves; both default to `default`. Seed and height options create a missing world and are rejected when explicitly incompatible with an existing world. See `SAVE_FORMAT.md` for the transactional format.

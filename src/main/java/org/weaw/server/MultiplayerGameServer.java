package org.weaw.server;

import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.weaw.game.Chunk;
import org.weaw.game.ChunkManager;
import org.weaw.game.ChunkManager.ChunkPosition;
import org.weaw.game.World;
import org.weaw.game.World.WorldBlockChange;
import org.weaw.game.utils.BlockDefinition;
import org.weaw.gameplay.GameplaySession;
import org.weaw.gameplay.GameplaySettings;
import org.weaw.gameplay.PlayerHotbar;
import org.weaw.gameplay.PlayerInput;
import org.weaw.network.protocol.CatalogFingerprint;
import org.weaw.network.protocol.ClientMessage;
import org.weaw.network.protocol.NetworkPlayerState;
import org.weaw.network.protocol.Protocol;
import org.weaw.network.protocol.ServerMessage;
import org.weaw.network.transport.ServerEvent;
import org.weaw.network.transport.ServerTransport;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

public final class MultiplayerGameServer implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(MultiplayerGameServer.class);
    private static final Vector3f DEFAULT_SPAWN = new Vector3f(16.0f, 12.0f, 48.0f);
    private static final int SNAPSHOT_INTERVAL_TICKS = 2;
    private static final int MAX_CHUNKS_SENT_PER_TICK = 1;
    private static final int MAX_LIGHT_UPDATES_SENT_PER_TICK = 1;
    private static final int MAX_BLOCK_UPDATES_SENT_PER_TICK = 4;
    private static final int OUTBOUND_STREAMING_WATERMARK = 4;
    private static final int OUTBOUND_SNAPSHOT_WATERMARK = 8;
    private static final int OUTBOUND_STALL_WATERMARK = OUTBOUND_SNAPSHOT_WATERMARK;
    private static final long OUTBOUND_STALL_TICKS = GameServer.DEFAULT_TICKS_PER_SECOND * 15L;
    private static final long CONGESTION_LOG_INTERVAL_TICKS = GameServer.DEFAULT_TICKS_PER_SECOND * 5L;
    private static final int MAX_QUEUED_COMMANDS = 64;

    private final World world;
    private final long worldSeed;
    private final ServerTransport transport;
    private final int maxPlayers;
    private final long catalogFingerprint;
    private final Map<Long, ServerPlayerSession> playersByConnection = new LinkedHashMap<>();
    private final Map<ChunkPosition, Long> chunkRevisions = new HashMap<>();
    private final AtomicBoolean running = new AtomicBoolean();
    private Thread serverThread;
    private long nextPlayerId = 1L;
    private long tickIndex;
    private long synchronizedLightVersion = Long.MIN_VALUE;
    private long skippedSnapshots;
    private long deferredWorldMessages;
    private volatile ServerNetworkStats networkStats = ServerNetworkStats.empty();

    public MultiplayerGameServer(World world, long worldSeed, ServerTransport transport) {
        this(world, worldSeed, transport, Protocol.DEFAULT_MAX_PLAYERS);
    }

    public MultiplayerGameServer(World world, long worldSeed, ServerTransport transport, int maxPlayers) {
        this.world = Objects.requireNonNull(world, "world");
        this.worldSeed = worldSeed;
        this.transport = Objects.requireNonNull(transport, "transport");
        this.maxPlayers = Math.clamp(maxPlayers, 1, Protocol.MAX_PLAYERS);
        this.catalogFingerprint = CatalogFingerprint.compute(world.getBlockCatalog());
        world.setMeshGenerationEnabled(false);
    }

    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        serverThread = Thread.ofPlatform().name("voxy-authoritative-server").start(this::runLoop);
    }

    public void tickOnce() {
        drainNetworkEvents();
        for (ServerPlayerSession player : playersByConnection.values()) {
            PlayerInput input = player.consumeInput();
            player.gameplay.beginSimulationTick();
            player.gameplay.update(1.0f / GameServer.DEFAULT_TICKS_PER_SECOND, input);
        }

        List<Vector3f> positions = playersByConnection.values().stream()
                .map(player -> player.gameplay.getPlayer().getPosition())
                .toList();
        world.update(positions);
        world.processLightingFrame();
        publishBlockChanges();
        publishLightChanges();
        updateChunkSubscriptions();
        if (tickIndex % SNAPSHOT_INTERVAL_TICKS == 0L) {
            publishPlayerSnapshots();
        }
        updateCongestionState();
        updateNetworkStats();
        tickIndex++;
    }

    public int getPlayerCount() {
        return playersByConnection.size();
    }

    public long getTickIndex() {
        return tickIndex;
    }

    public World getWorld() {
        return world;
    }

    public ServerNetworkStats getNetworkStats() {
        return networkStats;
    }

    private void runLoop() {
        long tickNanos = 1_000_000_000L / GameServer.DEFAULT_TICKS_PER_SECOND;
        long nextTick = System.nanoTime();
        while (running.get() && transport.isOpen()) {
            long now = System.nanoTime();
            if (now < nextTick) {
                LockSupport.parkNanos(nextTick - now);
                continue;
            }
            try {
                tickOnce();
            } catch (RuntimeException exception) {
                LOGGER.error("Authoritative server tick failed", exception);
            }
            nextTick += tickNanos;
            if (System.nanoTime() - nextTick > tickNanos * 5L) {
                nextTick = System.nanoTime() + tickNanos;
            }
        }
    }

    private void drainNetworkEvents() {
        ServerEvent event;
        while ((event = transport.poll()) != null) {
            switch (event) {
                case ServerEvent.Connected connected -> LOGGER.info("Network connection {} opened", connected.connectionId());
                case ServerEvent.Message message -> handleMessage(message.connectionId(), message.message());
                case ServerEvent.Disconnected disconnected -> removePlayer(disconnected.connectionId(), disconnected.reason());
            }
        }
    }

    private void handleMessage(long connectionId, ClientMessage message) {
        ServerPlayerSession player = playersByConnection.get(connectionId);
        if (player == null) {
            if (message instanceof ClientMessage.Hello hello) {
                acceptHello(connectionId, hello);
            } else {
                transport.disconnect(connectionId, "Handshake required");
            }
            return;
        }

        switch (message) {
            case ClientMessage.Hello ignored -> transport.disconnect(connectionId, "Handshake already completed");
            case ClientMessage.PlayerCommand command -> player.enqueue(command);
            case ClientMessage.SetHotbarSlot set -> applyHotbarSet(player, set);
            case ClientMessage.SwapHotbarSlots swap -> applyHotbarSwap(player, swap);
            case ClientMessage.Disconnect ignored -> transport.disconnect(connectionId, "client disconnected");
        }
    }

    private void acceptHello(long connectionId, ClientMessage.Hello hello) {
        if (hello.protocolVersion() != Protocol.VERSION) {
            transport.disconnect(connectionId, "Protocol version mismatch");
            return;
        }
        if (hello.catalogFingerprint() != catalogFingerprint) {
            transport.disconnect(connectionId, "Block catalog mismatch");
            return;
        }
        String name = normalizeName(hello.playerName());
        if (name == null) {
            transport.disconnect(connectionId, "Invalid player name");
            return;
        }
        if (playersByConnection.size() >= maxPlayers) {
            transport.disconnect(connectionId, "Server is full");
            return;
        }

        long playerId = nextPlayerId++;
        GameplaySession gameplay = new GameplaySession(world, new GameplaySettings());
        gameplay.setPlayerPosition(DEFAULT_SPAWN);
        ServerPlayerSession session = new ServerPlayerSession(
                connectionId,
                playerId,
                name,
                Math.clamp(hello.viewDistance(), 2, world.getSettings().getRenderDistanceChunks()),
                gameplay
        );
        playersByConnection.put(connectionId, session);
        transport.send(connectionId, new ServerMessage.Welcome(
                playerId,
                tickIndex,
                worldSeed,
                world.getSettings().getHeightRange().minChunkY(),
                world.getSettings().getHeightRange().maxChunkY(),
                session.viewDistance
        ));
        LOGGER.info("Player {} ({}) joined on connection {}", name, playerId, connectionId);
    }

    private void applyHotbarSet(ServerPlayerSession player, ClientMessage.SetHotbarSlot message) {
        if (message.sequence() <= player.lastProcessedSequence || !validSlot(message.slot())) {
            return;
        }
        BlockDefinition block = world.getBlockCatalog().getBlock(message.stableBlockId());
        if (block == null || block.isAir() || block.getTexturePath() == null) {
            transport.send(player.connectionId, new ServerMessage.Rejected("Invalid creative block"));
            return;
        }
        player.gameplay.getHotbar().setSlot(message.slot(), block);
        player.lastProcessedSequence = message.sequence();
    }

    private void applyHotbarSwap(ServerPlayerSession player, ClientMessage.SwapHotbarSlots message) {
        if (message.sequence() <= player.lastProcessedSequence
                || !validSlot(message.firstSlot())
                || !validSlot(message.secondSlot())) {
            return;
        }
        player.gameplay.getHotbar().swap(message.firstSlot(), message.secondSlot());
        player.lastProcessedSequence = message.sequence();
    }

    private void publishPlayerSnapshots() {
        List<NetworkPlayerState> playerStates = playersByConnection.values().stream()
                .map(player -> player.gameplay.getPlayer().snapshot(player.playerId, player.name))
                .toList();
        for (ServerPlayerSession receiver : playersByConnection.values()) {
            if (transport.outboundBacklog(receiver.connectionId) >= OUTBOUND_SNAPSHOT_WATERMARK) {
                skippedSnapshots++;
                continue;
            }
            PlayerHotbar hotbar = receiver.gameplay.getHotbar();
            String[] hotbarIds = new String[PlayerHotbar.SLOT_COUNT];
            for (int index = 0; index < hotbarIds.length; index++) {
                BlockDefinition block = hotbar.getSlot(index);
                hotbarIds[index] = block == null ? null : block.getStableId();
            }
            transport.send(receiver.connectionId, new ServerMessage.StateSnapshot(
                    tickIndex,
                    receiver.lastProcessedSequence,
                    playerStates,
                    hotbarIds,
                    hotbar.getSelectedIndex()
            ));
        }
    }

    private void publishBlockChanges() {
        for (WorldBlockChange change : world.drainBlockChanges()) {
            ChunkPosition position = change.chunkPosition();
            long revision = chunkRevisions.merge(position, 1L, Long::sum);
            ServerMessage.BlockUpdate message = new ServerMessage.BlockUpdate(
                    change.x(), change.y(), change.z(), change.blockId(), revision
            );
            for (ServerPlayerSession player : playersByConnection.values()) {
                if (player.subscribedChunks.contains(position)) {
                    player.pendingBlockUpdates.put(
                            new BlockPosition(change.x(), change.y(), change.z()),
                            message
                    );
                }
            }
        }
    }

    private void publishLightChanges() {
        ChunkManager.ChunkLightSync sync = world.getChunkManager().snapshotChunkLightSync(synchronizedLightVersion);
        synchronizedLightVersion = sync.version();
        Set<ChunkPosition> changed = new HashSet<>(sync.fullSnapshot());
        for (ChunkManager.ChunkLightDelta delta : sync.deltas()) {
            if (delta.changeType() != ChunkManager.ChunkUploadChangeType.REMOVED) {
                changed.add(delta.position());
            }
        }
        for (ChunkPosition position : changed) {
            for (ServerPlayerSession player : playersByConnection.values()) {
                if (player.subscribedChunks.contains(position)) {
                    player.pendingLightChunks.add(position);
                }
            }
        }
    }

    private void updateChunkSubscriptions() {
        Map<ChunkPosition, ChunkManager.ChunkUpload> loaded = world.getChunkManager().snapshotChunkUploads();
        for (ServerPlayerSession player : playersByConnection.values()) {
            flushPendingBlockUpdates(player);
            Vector3f location = player.gameplay.getPlayer().getPosition();
            int centerX = Math.floorDiv((int) Math.floor(location.x), Chunk.SIZE);
            int centerZ = Math.floorDiv((int) Math.floor(location.z), Chunk.SIZE);
            int radiusSquared = player.viewDistance * player.viewDistance;
            Set<ChunkPosition> desired = new HashSet<>();
            for (ChunkPosition position : loaded.keySet()) {
                int dx = position.x() - centerX;
                int dz = position.z() - centerZ;
                if (dx * dx + dz * dz <= radiusSquared) {
                    desired.add(position);
                }
            }

            for (ChunkPosition subscribed : List.copyOf(player.subscribedChunks)) {
                if (!desired.contains(subscribed)) {
                    if (!canStream(player)) {
                        deferredWorldMessages++;
                        break;
                    }
                    if (transport.send(player.connectionId, new ServerMessage.ChunkUnload(subscribed))) {
                        player.subscribedChunks.remove(subscribed);
                        player.pendingLightChunks.remove(subscribed);
                        player.pendingBlockUpdates.entrySet().removeIf(
                                entry -> entry.getValue().chunkPosition().equals(subscribed)
                        );
                    } else {
                        deferredWorldMessages++;
                        break;
                    }
                }
            }

            flushPendingLightUpdates(player);

            int sent = 0;
            List<ChunkPosition> nearest = desired.stream()
                    .filter(position -> !player.subscribedChunks.contains(position))
                    .sorted(Comparator.comparingInt(position -> {
                        int dx = position.x() - centerX;
                        int dz = position.z() - centerZ;
                        return dx * dx + dz * dz;
                    }))
                    .toList();
            for (ChunkPosition position : nearest) {
                if (sent >= MAX_CHUNKS_SENT_PER_TICK) {
                    break;
                }
                if (!canStream(player)) {
                    deferredWorldMessages++;
                    break;
                }
                Chunk chunk = world.getChunkManager().copyChunk(position);
                if (chunk == null) {
                    continue;
                }
                long revision = chunkRevisions.getOrDefault(position, 1L);
                ServerMessage.ChunkSnapshot snapshot = new ServerMessage.ChunkSnapshot(
                        position,
                        revision,
                        chunk.snapshotBlocks(),
                        chunk.getLighting().packToIntArray(),
                        chunk.snapshotPackedDirectSkyLight()
                );
                if (transport.send(player.connectionId, snapshot)) {
                    player.subscribedChunks.add(position);
                    sent++;
                } else {
                    deferredWorldMessages++;
                    break;
                }
            }
        }
    }

    private void flushPendingBlockUpdates(ServerPlayerSession player) {
        int sent = 0;
        var iterator = player.pendingBlockUpdates.entrySet().iterator();
        while (iterator.hasNext() && sent < MAX_BLOCK_UPDATES_SENT_PER_TICK && canStream(player)) {
            ServerMessage.BlockUpdate update = iterator.next().getValue();
            if (!player.subscribedChunks.contains(update.chunkPosition())) {
                iterator.remove();
                continue;
            }
            if (!transport.send(player.connectionId, update)) {
                deferredWorldMessages++;
                return;
            }
            iterator.remove();
            sent++;
        }
    }

    private void flushPendingLightUpdates(ServerPlayerSession player) {
        int sent = 0;
        var iterator = player.pendingLightChunks.iterator();
        while (iterator.hasNext() && sent < MAX_LIGHT_UPDATES_SENT_PER_TICK && canStream(player)) {
            ChunkPosition position = iterator.next();
            if (!player.subscribedChunks.contains(position)) {
                iterator.remove();
                continue;
            }
            Chunk chunk = world.getChunkManager().copyChunk(position);
            if (chunk == null) {
                iterator.remove();
                continue;
            }
            ServerMessage.ChunkLightUpdate message = new ServerMessage.ChunkLightUpdate(
                    position,
                    chunkRevisions.getOrDefault(position, 1L),
                    chunk.getLighting().packToIntArray(),
                    chunk.snapshotPackedDirectSkyLight()
            );
            if (!transport.send(player.connectionId, message)) {
                deferredWorldMessages++;
                return;
            }
            iterator.remove();
            sent++;
        }
    }

    private boolean canStream(ServerPlayerSession player) {
        return transport.outboundBacklog(player.connectionId) < OUTBOUND_STREAMING_WATERMARK;
    }

    private void updateCongestionState() {
        for (ServerPlayerSession player : playersByConnection.values()) {
            int backlog = transport.outboundBacklog(player.connectionId);
            if (backlog >= OUTBOUND_STALL_WATERMARK) {
                if (player.backpressuredSinceTick < 0L) {
                    player.backpressuredSinceTick = tickIndex;
                }
                if (!player.disconnectRequested
                        && tickIndex - player.backpressuredSinceTick >= OUTBOUND_STALL_TICKS) {
                    player.disconnectRequested = true;
                    transport.disconnect(player.connectionId, "outgoing connection stalled");
                } else if (tickIndex - player.lastCongestionLogTick >= CONGESTION_LOG_INTERVAL_TICKS) {
                    player.lastCongestionLogTick = tickIndex;
                    LOGGER.warn(
                            "Player {} network backlog remains high: {} messages",
                            player.name,
                            backlog
                    );
                }
            } else {
                player.backpressuredSinceTick = -1L;
            }
        }
    }

    private void updateNetworkStats() {
        int maxBacklog = 0;
        int pendingBlocks = 0;
        int pendingLights = 0;
        for (ServerPlayerSession player : playersByConnection.values()) {
            maxBacklog = Math.max(maxBacklog, transport.outboundBacklog(player.connectionId));
            pendingBlocks += player.pendingBlockUpdates.size();
            pendingLights += player.pendingLightChunks.size();
        }
        networkStats = new ServerNetworkStats(
                playersByConnection.size(),
                maxBacklog,
                pendingBlocks,
                pendingLights,
                skippedSnapshots,
                deferredWorldMessages
        );
    }

    private void removePlayer(long connectionId, String reason) {
        ServerPlayerSession removed = playersByConnection.remove(connectionId);
        if (removed == null) {
            return;
        }
        ServerMessage.PlayerLeft left = new ServerMessage.PlayerLeft(removed.playerId);
        for (ServerPlayerSession player : playersByConnection.values()) {
            transport.send(player.connectionId, left);
        }
        LOGGER.info("Player {} ({}) left: {}", removed.name, removed.playerId, reason);
    }

    private static boolean validSlot(int slot) {
        return slot >= 0 && slot < PlayerHotbar.SLOT_COUNT;
    }

    private static String normalizeName(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.isEmpty() || normalized.length() > 24) {
            return null;
        }
        for (int index = 0; index < normalized.length(); index++) {
            char character = normalized.charAt(index);
            if (!Character.isLetterOrDigit(character) && character != '_' && character != '-') {
                return null;
            }
        }
        return normalized;
    }

    @Override
    public void close() {
        running.set(false);
        if (serverThread != null) {
            serverThread.interrupt();
            try {
                serverThread.join(2_000L);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            serverThread = null;
        }
        transport.close();
        world.close();
    }

    private static final class ServerPlayerSession {
        private final long connectionId;
        private final long playerId;
        private final String name;
        private final int viewDistance;
        private final GameplaySession gameplay;
        private final ArrayDeque<ClientMessage.PlayerCommand> commands = new ArrayDeque<>();
        private final Set<ChunkPosition> subscribedChunks = new HashSet<>();
        private final Map<BlockPosition, ServerMessage.BlockUpdate> pendingBlockUpdates = new LinkedHashMap<>();
        private final Set<ChunkPosition> pendingLightChunks = new LinkedHashSet<>();
        private long lastReceivedSequence = -1L;
        private long lastProcessedSequence = -1L;
        private long backpressuredSinceTick = -1L;
        private long lastCongestionLogTick = -CONGESTION_LOG_INTERVAL_TICKS;
        private boolean disconnectRequested;

        private ServerPlayerSession(
                long connectionId,
                long playerId,
                String name,
                int viewDistance,
                GameplaySession gameplay
        ) {
            this.connectionId = connectionId;
            this.playerId = playerId;
            this.name = name;
            this.viewDistance = viewDistance;
            this.gameplay = gameplay;
        }

        private void enqueue(ClientMessage.PlayerCommand command) {
            if (command.sequence() < 0L
                    || command.clientTick() < 0L
                    || !Float.isFinite(command.input().mouseDeltaX())
                    || !Float.isFinite(command.input().mouseDeltaY())
                    || command.sequence() <= Math.max(lastReceivedSequence, lastProcessedSequence)
                    || commands.size() >= MAX_QUEUED_COMMANDS) {
                return;
            }
            lastReceivedSequence = command.sequence();
            commands.addLast(command);
        }

        private PlayerInput consumeInput() {
            if (commands.isEmpty()) {
                return PlayerInput.disabled();
            }
            ClientMessage.PlayerCommand command = commands.removeFirst();
            gameplay.getHotbar().select(Math.clamp(
                    command.selectedHotbarSlot(), 0, PlayerHotbar.SLOT_COUNT - 1
            ));
            PlayerInput input = command.input();
            PlayerInput sanitized = new PlayerInput(
                    input.controlsEnabled(), input.moveForward(), input.moveBackward(),
                    input.moveLeft(), input.moveRight(), input.moveUp(), input.moveDown(),
                    input.jump(), input.sprint(), input.toggleNoclip(), input.breakBlock(), input.placeBlock(),
                    Math.clamp(input.mouseDeltaX(), -720.0f, 720.0f),
                    Math.clamp(input.mouseDeltaY(), -720.0f, 720.0f),
                    Math.clamp(input.scrollDelta(), -PlayerHotbar.SLOT_COUNT, PlayerHotbar.SLOT_COUNT)
            );
            lastProcessedSequence = command.sequence();
            return sanitized;
        }
    }

    private record BlockPosition(int x, int y, int z) {
    }

    public record ServerNetworkStats(
            int connectedPlayers,
            int maxOutboundBacklog,
            int pendingBlockUpdates,
            int pendingLightUpdates,
            long skippedSnapshots,
            long deferredWorldMessages
    ) {
        private static ServerNetworkStats empty() {
            return new ServerNetworkStats(0, 0, 0, 0, 0L, 0L);
        }
    }
}

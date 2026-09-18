package org.weaw.network.protocol;

import org.weaw.game.Chunk;
import org.weaw.game.ChunkLighting;
import org.weaw.game.ChunkManager.ChunkPosition;

import java.util.List;

public sealed interface ServerMessage permits
        ServerMessage.Welcome,
        ServerMessage.StateSnapshot,
        ServerMessage.ChunkSnapshot,
        ServerMessage.ChunkUnload,
        ServerMessage.BlockUpdate,
        ServerMessage.ChunkLightUpdate,
        ServerMessage.PlayerLeft,
        ServerMessage.Rejected {

    record Welcome(
            long playerId,
            long serverTick,
            long worldSeed,
            int minChunkY,
            int maxChunkY,
            int renderDistance
    ) implements ServerMessage {
    }

    record StateSnapshot(
            long serverTick,
            long acknowledgedSequence,
            List<NetworkPlayerState> players,
            String[] hotbarStableIds,
            int selectedHotbarSlot
    ) implements ServerMessage {
        public StateSnapshot {
            players = List.copyOf(players);
            hotbarStableIds = hotbarStableIds.clone();
        }

        @Override
        public String[] hotbarStableIds() {
            return hotbarStableIds.clone();
        }
    }

    record ChunkSnapshot(
            ChunkPosition position,
            long revision,
            short[] blocks,
            int[] packedLight,
            byte[] packedDirectSkyLight
    ) implements ServerMessage {
        public ChunkSnapshot {
            if (blocks.length != Chunk.TOTAL_BLOCKS) {
                throw new IllegalArgumentException("Chunk snapshot must contain " + Chunk.TOTAL_BLOCKS + " blocks");
            }
            if (packedLight.length != ChunkLighting.packedIntCount()) {
                throw new IllegalArgumentException(
                        "Chunk snapshot must contain " + ChunkLighting.packedIntCount() + " packed light values"
                );
            }
            if (packedDirectSkyLight.length != Chunk.packedDirectSkyByteCount()) {
                throw new IllegalArgumentException(
                        "Chunk snapshot must contain " + Chunk.packedDirectSkyByteCount()
                                + " packed direct skylight values"
                );
            }
            blocks = blocks.clone();
            packedLight = packedLight.clone();
            packedDirectSkyLight = packedDirectSkyLight.clone();
        }

        @Override
        public short[] blocks() {
            return blocks.clone();
        }

        @Override
        public int[] packedLight() {
            return packedLight.clone();
        }

        @Override
        public byte[] packedDirectSkyLight() {
            return packedDirectSkyLight.clone();
        }
    }

    record ChunkUnload(ChunkPosition position) implements ServerMessage {
    }

    record BlockUpdate(int x, int y, int z, short blockId, long revision) implements ServerMessage {
        public ChunkPosition chunkPosition() {
            return new ChunkPosition(
                    Math.floorDiv(x, Chunk.SIZE),
                    Math.floorDiv(y, Chunk.SIZE),
                    Math.floorDiv(z, Chunk.SIZE)
            );
        }
    }

    record ChunkLightUpdate(
            ChunkPosition position,
            long revision,
            int[] packedLight,
            byte[] packedDirectSkyLight
    ) implements ServerMessage {
        public ChunkLightUpdate {
            if (packedLight.length != ChunkLighting.packedIntCount()) {
                throw new IllegalArgumentException(
                        "Chunk light update must contain " + ChunkLighting.packedIntCount()
                                + " packed light values"
                );
            }
            if (packedDirectSkyLight.length != Chunk.packedDirectSkyByteCount()) {
                throw new IllegalArgumentException(
                        "Chunk light update must contain " + Chunk.packedDirectSkyByteCount()
                                + " packed direct skylight values"
                );
            }
            packedLight = packedLight.clone();
            packedDirectSkyLight = packedDirectSkyLight.clone();
        }

        @Override
        public int[] packedLight() {
            return packedLight.clone();
        }

        @Override
        public byte[] packedDirectSkyLight() {
            return packedDirectSkyLight.clone();
        }
    }

    record PlayerLeft(long playerId) implements ServerMessage {
    }

    record Rejected(String reason) implements ServerMessage {
    }
}

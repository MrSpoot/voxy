package org.weaw.server;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.weaw.game.ChunkMesher;
import org.weaw.game.World;
import org.weaw.game.WorldSettings;
import org.weaw.game.generation.GenerationConfig;
import org.weaw.game.generation.NoiseWorldGenerator;
import org.weaw.game.utils.BlockCatalog;
import org.weaw.game.utils.BlockRegistry;
import org.weaw.network.transport.TcpServerTransport;
import org.weaw.runtime.LaunchOptions;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;

public final class DedicatedServerApplication {
    private static final Logger LOGGER = LoggerFactory.getLogger(DedicatedServerApplication.class);

    private DedicatedServerApplication() {
    }

    public static void main(String[] args) {
        run(LaunchOptions.from(args));
    }

    public static void run(LaunchOptions options) {
        BlockRegistry.initialize();
        BlockCatalog catalog = BlockRegistry.getDefaultCatalog();
        GenerationConfig generation = GenerationConfig.defaults().withSeed(options.network().worldSeed());
        WorldSettings settings = new WorldSettings(
                options.network().viewDistance(),
                options.worldHeightRange(),
                options.worldMemoryBudget(),
                options.sparseChunkStreamingEnabled()
        );
        World world = new World(new NoiseWorldGenerator(generation), settings, catalog);
        world.setDynamicLightingEnabled(options.dynamicLightingEnabled());
        world.setRemeshEnabled(options.remeshEnabled());
        world.setUnloadsEnabled(options.unloadsEnabled());
        ChunkMesher.setAmbientOcclusionEnabled(options.ambientOcclusionEnabled());
        ChunkMesher.setTransparentChunksEnabled(options.transparentChunksEnabled());

        try {
            TcpServerTransport transport = new TcpServerTransport(options.network().port());
            MultiplayerGameServer server = new MultiplayerGameServer(
                    world,
                    options.network().worldSeed(),
                    transport,
                    options.network().maxPlayers()
            );
            CountDownLatch stopped = new CountDownLatch(1);
            Runtime.getRuntime().addShutdownHook(Thread.ofPlatform().unstarted(() -> {
                server.close();
                stopped.countDown();
            }));
            server.start();
            LOGGER.info(
                    "Voxy dedicated server listening on 0.0.0.0:{} (maxPlayers={}, seed={}, viewDistance={})",
                    options.network().port(),
                    options.network().maxPlayers(),
                    options.network().worldSeed(),
                    options.network().viewDistance()
            );
            stopped.await();
        } catch (IOException exception) {
            world.close();
            throw new IllegalStateException("Unable to bind dedicated server port " + options.network().port(), exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            world.close();
        }
    }
}

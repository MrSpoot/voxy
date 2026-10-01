package org.weaw.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.weaw.Game;
import org.weaw.client.ui.ClientFrontend;
import org.weaw.engine.window.Window;
import org.weaw.persistence.ClientSettings;
import org.weaw.persistence.ClientSettingsRepository;
import org.weaw.runtime.LaunchOptions;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Owns the single graphical window for the complete lifetime of the client. */
public final class ClientApplication {
    private static final Logger LOGGER = LoggerFactory.getLogger(ClientApplication.class);

    private final LaunchOptions baseOptions;

    public ClientApplication(LaunchOptions baseOptions) {
        this.baseOptions = baseOptions;
    }

    public void run() {
        ClientSettings settings = loadSettings();
        Window window = new Window("Voxy", settings.windowWidth(), settings.windowHeight(), settings.fullscreen());
        ExecutorService loader = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "voxy-session-loader");
            thread.setDaemon(true);
            return thread;
        });
        try {
            window.create();
            window.setVsync(settings.vsync());
            String launchError = null;
            while (!window.shouldClose()) {
                ClientFrontend.FrontendResult result;
                try (ClientFrontend frontend = new ClientFrontend(baseOptions, launchError)) {
                    result = frontend.run(window, loader);
                }
                launchError = null;
                if (result.quit() || window.shouldClose()) {
                    return;
                }

                Game game = result.game();
                try {
                    Game.SessionOutcome outcome = game.run(window);
                    if (outcome == Game.SessionOutcome.EXIT_APPLICATION || window.shouldClose()) {
                        return;
                    }
                } catch (RuntimeException exception) {
                    LOGGER.error("Unable to launch game session", exception);
                    launchError = exception.getMessage() == null
                            ? exception.getClass().getSimpleName()
                            : exception.getMessage();
                }
            }
        } finally {
            loader.shutdownNow();
            try {
                if (!loader.awaitTermination(2, TimeUnit.SECONDS)) {
                    LOGGER.warn("Session loader did not stop before the client window was closed");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            window.cleanup();
        }
    }

    private ClientSettings loadSettings() {
        try {
            return new ClientSettingsRepository(baseOptions.storage().dataDirectory()).load();
        } catch (RuntimeException exception) {
            LOGGER.warn("Unable to load client settings, using defaults: {}", exception.getMessage());
            return ClientSettings.defaults();
        }
    }
}

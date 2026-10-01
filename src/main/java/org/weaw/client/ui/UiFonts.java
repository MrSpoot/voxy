package org.weaw.client.ui;

import imgui.ImGuiIO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Loads the bundled OFL pixel font used by title and in-game menus. */
public final class UiFonts {
    private static final Logger LOGGER = LoggerFactory.getLogger(UiFonts.class);
    private static final String PIXEL_FONT = "/ui/fonts/PixelifySans.ttf";

    private UiFonts() {
    }

    public static InstalledFont installPixelFont(ImGuiIO io, float sizePixels) {
        Path temporaryFont = null;
        try (InputStream input = UiFonts.class.getResourceAsStream(PIXEL_FONT)) {
            if (input == null) {
                LOGGER.warn("Bundled UI font is missing; using ImGui's default font");
                return InstalledFont.empty();
            }
            temporaryFont = Files.createTempFile("voxy-ui-font-", ".ttf");
            Files.copy(input, temporaryFont, StandardCopyOption.REPLACE_EXISTING);

            // The memory overload hands a pinned Java byte[] to native ImGui, which then
            // attempts to free it when the atlas is destroyed. Loading from a file lets
            // ImGui allocate and own the font bytes with its matching native allocator.
            io.getFonts().addFontFromFileTTF(temporaryFont.toString(), sizePixels);
            return new InstalledFont(temporaryFont);
        } catch (IOException | RuntimeException exception) {
            LOGGER.warn("Unable to load bundled UI font; using ImGui's default font", exception);
            if (temporaryFont != null) {
                try {
                    Files.deleteIfExists(temporaryFont);
                } catch (IOException cleanupException) {
                    LOGGER.debug("Unable to remove temporary UI font {}", temporaryFont, cleanupException);
                    temporaryFont.toFile().deleteOnExit();
                }
            }
            return InstalledFont.empty();
        }
    }

    /** Keeps an extracted font alive until its owning ImGui context has been destroyed. */
    public static final class InstalledFont implements AutoCloseable {
        private Path path;

        private InstalledFont(Path path) {
            this.path = path;
        }

        private static InstalledFont empty() {
            return new InstalledFont(null);
        }

        @Override
        public void close() {
            Path current = path;
            path = null;
            if (current == null) {
                return;
            }
            try {
                Files.deleteIfExists(current);
            } catch (IOException exception) {
                LOGGER.debug("Unable to remove temporary UI font {}", current, exception);
                current.toFile().deleteOnExit();
            }
        }
    }
}

package org.weaw.persistence;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

public final class UserDataDirectories {
    private UserDataDirectories() {
    }

    public static Path defaultVoxyDataDirectory() {
        return resolve(System.getProperty("os.name", ""), System.getenv(), System.getProperty("user.home", "."));
    }

    static Path resolve(String osName, Map<String, String> environment, String userHome) {
        String normalized = osName.toLowerCase(Locale.ROOT);
        if (normalized.contains("win")) {
            String appData = environment.get("APPDATA");
            return Path.of(appData == null || appData.isBlank() ? userHome : appData, "Voxy");
        }
        if (normalized.contains("mac")) {
            return Path.of(userHome, "Library", "Application Support", "Voxy");
        }
        String xdg = environment.get("XDG_DATA_HOME");
        return Path.of(xdg == null || xdg.isBlank() ? Path.of(userHome, ".local", "share").toString() : xdg, "voxy");
    }
}

package org.weaw.client.ui;

import imgui.ImGui;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class UiFontsTest {
    @AfterEach
    void destroyRemainingContext() {
        if (ImGui.getCurrentContext().isValidPtr()) {
            ImGui.destroyContext();
        }
    }

    @Test
    void repeatedlyBuildsAndDestroysTheNativeFontAtlas() {
        for (int iteration = 0; iteration < 8; iteration++) {
            ImGui.createContext();
            UiFonts.InstalledFont installedFont = UiFonts.installPixelFont(ImGui.getIO(), 22.0f);

            assertTrue(ImGui.getIO().getFonts().build());

            ImGui.destroyContext();
            installedFont.close();
        }
    }
}

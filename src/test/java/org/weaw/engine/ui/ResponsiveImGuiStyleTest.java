package org.weaw.engine.ui;

import imgui.ImGui;
import imgui.ImGuiIO;
import imgui.ImGuiStyle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResponsiveImGuiStyleTest {
    @AfterEach
    void destroyRemainingContext() {
        if (ImGui.getCurrentContext().isValidPtr()) {
            ImGui.destroyContext();
        }
    }

    @Test
    void separatorStaysValidAndStyleDoesNotDriftAcrossResizeCycles() {
        ImGui.createContext();
        ImGuiIO io = ImGui.getIO();
        io.setDisplaySize(320.0f, 180.0f);
        io.setDeltaTime(1.0f / 60.0f);
        io.getFonts().addFontDefault();
        assertTrue(io.getFonts().build());

        ImGuiStyle style = ImGui.getStyle();
        style.setWindowPadding(18.0f, 18.0f);
        style.setItemSpacing(10.0f, 10.0f);
        ResponsiveImGuiStyle responsive = ResponsiveImGuiStyle.capture(style);
        float originalSeparator = style.getSeparatorSize();
        float originalWindowPaddingX = style.getWindowPaddingX();
        float originalItemSpacingY = style.getItemSpacingY();

        for (float scale : new float[]{1.0f, 0.75f, 0.25f, 0.01f, 1.0f, 0.01f, 1.0f}) {
            ImGui.newFrame();
            responsive.pushScaled(scale);
            try {
                assertTrue(style.getSeparatorSize() > 0.0f);
                assertTrue(style.getWindowPaddingX() > 0.0f);
                ImGui.begin("responsive-style-test");
                ImGui.text("Before separator");
                ImGui.separator();
                ImGui.text("After separator");
                ImGui.end();
            } finally {
                responsive.popScaled();
            }
            ImGui.render();

            assertEquals(originalSeparator, style.getSeparatorSize());
            assertEquals(originalWindowPaddingX, style.getWindowPaddingX());
            assertEquals(originalItemSpacingY, style.getItemSpacingY());
        }
    }
}

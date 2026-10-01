package org.weaw.engine.ui;

import imgui.ImGui;
import imgui.ImGuiStyle;
import imgui.flag.ImGuiStyleVar;

/**
 * Applies a captured ImGui style at an absolute scale for one render scope.
 * This avoids the rounding drift caused by repeatedly calling ImGuiStyle.scaleAllSizes().
 */
public final class ResponsiveImGuiStyle {
    private static final float MIN_SCALE = 0.01f;

    private final int[] scalarVariables;
    private final float[] scalarValues;
    private final int[] vectorVariables;
    private final float[] vectorValues;
    private boolean pushed;

    private ResponsiveImGuiStyle(
            int[] scalarVariables,
            float[] scalarValues,
            int[] vectorVariables,
            float[] vectorValues
    ) {
        this.scalarVariables = scalarVariables;
        this.scalarValues = scalarValues;
        this.vectorVariables = vectorVariables;
        this.vectorValues = vectorValues;
    }

    public static ResponsiveImGuiStyle capture(ImGuiStyle style) {
        return new ResponsiveImGuiStyle(
                new int[]{
                        ImGuiStyleVar.WindowRounding,
                        ImGuiStyleVar.WindowBorderSize,
                        ImGuiStyleVar.ChildRounding,
                        ImGuiStyleVar.ChildBorderSize,
                        ImGuiStyleVar.PopupRounding,
                        ImGuiStyleVar.PopupBorderSize,
                        ImGuiStyleVar.FrameRounding,
                        ImGuiStyleVar.FrameBorderSize,
                        ImGuiStyleVar.IndentSpacing,
                        ImGuiStyleVar.ScrollbarSize,
                        ImGuiStyleVar.ScrollbarRounding,
                        ImGuiStyleVar.GrabMinSize,
                        ImGuiStyleVar.GrabRounding,
                        ImGuiStyleVar.ImageRounding,
                        ImGuiStyleVar.ImageBorderSize,
                        ImGuiStyleVar.TabRounding,
                        ImGuiStyleVar.TabBorderSize,
                        ImGuiStyleVar.TreeLinesSize,
                        ImGuiStyleVar.TreeLinesRounding,
                        ImGuiStyleVar.SeparatorSize,
                        ImGuiStyleVar.SeparatorTextBorderSize
                },
                new float[]{
                        style.getWindowRounding(),
                        style.getWindowBorderSize(),
                        style.getChildRounding(),
                        style.getChildBorderSize(),
                        style.getPopupRounding(),
                        style.getPopupBorderSize(),
                        style.getFrameRounding(),
                        style.getFrameBorderSize(),
                        style.getIndentSpacing(),
                        style.getScrollbarSize(),
                        style.getScrollbarRounding(),
                        style.getGrabMinSize(),
                        style.getGrabRounding(),
                        style.getImageRounding(),
                        style.getImageBorderSize(),
                        style.getTabRounding(),
                        style.getTabBorderSize(),
                        style.getTreeLinesSize(),
                        style.getTreeLinesRounding(),
                        style.getSeparatorSize(),
                        style.getSeparatorTextBorderSize()
                },
                new int[]{
                        ImGuiStyleVar.WindowPadding,
                        ImGuiStyleVar.WindowMinSize,
                        ImGuiStyleVar.FramePadding,
                        ImGuiStyleVar.ItemSpacing,
                        ImGuiStyleVar.ItemInnerSpacing,
                        ImGuiStyleVar.CellPadding,
                        ImGuiStyleVar.SeparatorTextPadding
                },
                new float[]{
                        style.getWindowPaddingX(), style.getWindowPaddingY(),
                        style.getWindowMinSizeX(), style.getWindowMinSizeY(),
                        style.getFramePaddingX(), style.getFramePaddingY(),
                        style.getItemSpacingX(), style.getItemSpacingY(),
                        style.getItemInnerSpacingX(), style.getItemInnerSpacingY(),
                        style.getCellPaddingX(), style.getCellPaddingY(),
                        style.getSeparatorTextPaddingX(), style.getSeparatorTextPaddingY()
                }
        );
    }

    public void pushScaled(float requestedScale) {
        if (pushed) {
            throw new IllegalStateException("Responsive ImGui style is already pushed");
        }
        float scale = Float.isFinite(requestedScale)
                ? Math.max(MIN_SCALE, requestedScale)
                : 1.0f;
        for (int index = 0; index < scalarVariables.length; index++) {
            float value = scaled(scalarValues[index], scale);
            if (scalarVariables[index] == ImGuiStyleVar.SeparatorSize) {
                value = Math.max(1.0f, value);
            }
            ImGui.pushStyleVar(scalarVariables[index], value);
        }
        for (int index = 0; index < vectorVariables.length; index++) {
            ImGui.pushStyleVar(
                    vectorVariables[index],
                    scaled(vectorValues[index * 2], scale),
                    scaled(vectorValues[index * 2 + 1], scale)
            );
        }
        pushed = true;
    }

    public void popScaled() {
        if (!pushed) {
            throw new IllegalStateException("Responsive ImGui style is not pushed");
        }
        ImGui.popStyleVar(scalarVariables.length + vectorVariables.length);
        pushed = false;
    }

    private static float scaled(float value, float scale) {
        return value == 0.0f ? 0.0f : value * scale;
    }
}

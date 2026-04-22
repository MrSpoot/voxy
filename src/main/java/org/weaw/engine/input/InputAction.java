package org.weaw.engine.input;

import java.util.Arrays;

import static org.lwjgl.glfw.GLFW.GLFW_KEY_A;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_D;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_F1;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_CONTROL;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_SHIFT;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_O;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_P;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_S;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_SPACE;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_W;
import static org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT;
import static org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_RIGHT;

public enum InputAction {
    MOVE_FORWARD("forward", "Move Forward", InputBinding.key(GLFW_KEY_W)),
    MOVE_BACKWARD("backward", "Move Backward", InputBinding.key(GLFW_KEY_S)),
    MOVE_LEFT("left", "Move Left", InputBinding.key(GLFW_KEY_A)),
    MOVE_RIGHT("right", "Move Right", InputBinding.key(GLFW_KEY_D)),
    MOVE_UP("up", "Move Up", InputBinding.key(GLFW_KEY_SPACE)),
    MOVE_DOWN("down", "Move Down", InputBinding.key(GLFW_KEY_LEFT_CONTROL)),
    SPRINT("sprint", "Sprint", InputBinding.key(GLFW_KEY_LEFT_SHIFT)),
    TOGGLE_MOUSE_LOCK("toggle_mouse_lock", "Toggle Mouse Lock", InputBinding.key(GLFW_KEY_F1)),
    TOGGLE_NOCLIP("toggle_noclip", "Toggle Noclip", InputBinding.key(GLFW_KEY_O)),
    BREAK_BLOCK("break_block", "Break Block", InputBinding.mouseButton(GLFW_MOUSE_BUTTON_LEFT)),
    PLACE_BLOCK("place_block", "Place Block", InputBinding.mouseButton(GLFW_MOUSE_BUTTON_RIGHT)),
    QUIT("quit", "Quit Game", InputBinding.key(GLFW_KEY_ESCAPE)),
    TOGGLE_WIREFRAME("toggle_wireframe", "Toggle Wireframe", InputBinding.key(GLFW_KEY_P));

    private final String id;
    private final String displayName;
    private final InputBinding defaultBinding;

    InputAction(String id, String displayName, InputBinding defaultBinding) {
        this.id = id;
        this.displayName = displayName;
        this.defaultBinding = defaultBinding;
    }

    public String getId() {
        return id;
    }

    public String getDisplayName() {
        return displayName;
    }

    public InputBinding getDefaultBinding() {
        return defaultBinding;
    }

    public static InputAction fromId(String id) {
        return Arrays.stream(values())
                .filter(action -> action.id.equalsIgnoreCase(id))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown input action: " + id));
    }
}

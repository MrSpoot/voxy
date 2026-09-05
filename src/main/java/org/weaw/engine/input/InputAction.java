package org.weaw.engine.input;

import java.util.Arrays;

import static org.lwjgl.glfw.GLFW.GLFW_KEY_A;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_D;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_E;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_F1;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_CONTROL;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_SHIFT;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_O;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_P;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_S;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_SPACE;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_W;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_1;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_2;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_3;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_4;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_5;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_6;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_7;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_8;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_9;
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
    TOGGLE_INVENTORY("toggle_inventory", "Toggle Inventory", InputBinding.key(GLFW_KEY_E)),
    HOTBAR_SLOT_1("hotbar_slot_1", "Hotbar Slot 1", InputBinding.key(GLFW_KEY_1)),
    HOTBAR_SLOT_2("hotbar_slot_2", "Hotbar Slot 2", InputBinding.key(GLFW_KEY_2)),
    HOTBAR_SLOT_3("hotbar_slot_3", "Hotbar Slot 3", InputBinding.key(GLFW_KEY_3)),
    HOTBAR_SLOT_4("hotbar_slot_4", "Hotbar Slot 4", InputBinding.key(GLFW_KEY_4)),
    HOTBAR_SLOT_5("hotbar_slot_5", "Hotbar Slot 5", InputBinding.key(GLFW_KEY_5)),
    HOTBAR_SLOT_6("hotbar_slot_6", "Hotbar Slot 6", InputBinding.key(GLFW_KEY_6)),
    HOTBAR_SLOT_7("hotbar_slot_7", "Hotbar Slot 7", InputBinding.key(GLFW_KEY_7)),
    HOTBAR_SLOT_8("hotbar_slot_8", "Hotbar Slot 8", InputBinding.key(GLFW_KEY_8)),
    HOTBAR_SLOT_9("hotbar_slot_9", "Hotbar Slot 9", InputBinding.key(GLFW_KEY_9)),
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

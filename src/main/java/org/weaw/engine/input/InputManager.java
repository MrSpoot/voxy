package org.weaw.engine.input;

import lombok.Getter;
import org.lwjgl.glfw.GLFWScrollCallback;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

import static org.lwjgl.glfw.GLFW.*;

public class InputManager {

    private final long windowId;
    private final boolean[] keyStates = new boolean[GLFW_KEY_LAST + 1];
    private final boolean[] previousKeyStates = new boolean[GLFW_KEY_LAST + 1];
    private final boolean[] mouseButtonStates = new boolean[GLFW_MOUSE_BUTTON_LAST + 1];
    private final boolean[] previousMouseButtonStates = new boolean[GLFW_MOUSE_BUTTON_LAST + 1];
    private final boolean[] trackedKeys = new boolean[GLFW_KEY_LAST + 1];
    private final boolean[] trackedMouseButtons = new boolean[GLFW_MOUSE_BUTTON_LAST + 1];
    private final int[] activeKeyCodes = new int[InputAction.values().length];
    private final int[] activeMouseButtons = new int[InputAction.values().length];
    private final double[] cursorPosX = new double[1];
    private final double[] cursorPosY = new double[1];

    private double mouseX, mouseY;
    private double lastMouseX, lastMouseY;
    private boolean firstMouse = true;
    private float mouseDeltaX, mouseDeltaY;
    private GLFWScrollCallback scrollCallback;
    private double scrollOffsetY = 0;
    private int activeKeyCodeCount = 0;
    private int activeMouseButtonCount = 0;
    private final EnumMap<InputAction, InputBinding> bindings = new EnumMap<>(InputAction.class);
    private final EnumMap<InputAction, String> bindingLabels = new EnumMap<>(InputAction.class);

    @Getter
    private MousePosition mousePosition;

    public InputManager(long windowId) {
        this.windowId = windowId;
        resetAllBindings();
        mousePosition = new MousePosition(0.0f, 0.0f);
    }

    public void create() {
        scrollCallback = glfwSetScrollCallback(windowId, (window, xoffset, yoffset) -> {
            scrollOffsetY += yoffset;
        });
    }

    public void update() {
        for (int index = 0; index < activeKeyCodeCount; index++) {
            int keyCode = activeKeyCodes[index];
            previousKeyStates[keyCode] = keyStates[keyCode];
            keyStates[keyCode] = glfwGetKey(windowId, keyCode) == GLFW_PRESS;
        }

        for (int index = 0; index < activeMouseButtonCount; index++) {
            int button = activeMouseButtons[index];
            previousMouseButtonStates[button] = mouseButtonStates[button];
            mouseButtonStates[button] = glfwGetMouseButton(windowId, button) == GLFW_PRESS;
        }

        glfwGetCursorPos(windowId, cursorPosX, cursorPosY);

        mouseX = cursorPosX[0];
        mouseY = cursorPosY[0];

        if (firstMouse) {
            lastMouseX = mouseX;
            lastMouseY = mouseY;
            firstMouse = false;
        }

        mouseDeltaX = (float) (mouseX - lastMouseX);
        mouseDeltaY = (float) (lastMouseY - mouseY);

        lastMouseX = mouseX;
        lastMouseY = mouseY;

        mousePosition.set(mouseDeltaX, mouseDeltaY);
    }

    public boolean isKeyDown(int key) {
        if (!isValidKey(key)) {
            return false;
        }
        if (trackedKeys[key]) {
            return keyStates[key];
        }
        return glfwGetKey(windowId, key) == GLFW_PRESS;
    }

    /**
     * Returns true only on the frame when key is first pressed.
     * Use this to detect key presses (vs isKeyDown which returns true every frame while held).
     */
    public boolean isKeyPressed(int key) {
        return isValidKey(key) && trackedKeys[key] && keyStates[key] && !previousKeyStates[key];
    }

    public boolean isMouseKeyDown(int key) {
        if (!isValidMouseButton(key)) {
            return false;
        }
        if (trackedMouseButtons[key]) {
            return mouseButtonStates[key];
        }
        return glfwGetMouseButton(windowId, key) == GLFW_PRESS;
    }

    /**
     * Returns true only on the frame when mouse button is first pressed.
     * Use this to detect clicks (vs isMouseKeyDown which returns true every frame while held).
     */
    public boolean isMouseKeyPressed(int button) {
        return isValidMouseButton(button) && trackedMouseButtons[button] && mouseButtonStates[button] && !previousMouseButtonStates[button];
    }

    public boolean isMouseKeyReleased(int button) {
        return isValidMouseButton(button)
                && trackedMouseButtons[button]
                && !mouseButtonStates[button]
                && previousMouseButtonStates[button];
    }

    public double getMouseX() {
        return mouseX;
    }

    public double getMouseY() {
        return mouseY;
    }

    public boolean isActionDown(InputAction action) {
        InputBinding binding = getBinding(action);
        return binding != null && isBindingDown(binding);
    }

    public boolean isActionPressed(InputAction action) {
        InputBinding binding = getBinding(action);
        return binding != null && isBindingPressed(binding);
    }

    public void bindAction(InputAction action, InputBinding binding) {
        bindings.put(Objects.requireNonNull(action, "action"), Objects.requireNonNull(binding, "binding"));
        rebuildBindingCaches();
    }

    public void bindKey(InputAction action, int keyCode) {
        bindAction(action, InputBinding.key(keyCode));
    }

    public void bindMouseButton(InputAction action, int button) {
        bindAction(action, InputBinding.mouseButton(button));
    }

    public void resetBinding(InputAction action) {
        bindings.put(action, action.getDefaultBinding());
        rebuildBindingCaches();
    }

    public void resetAllBindings() {
        bindings.clear();
        for (InputAction action : InputAction.values()) {
            bindings.put(action, action.getDefaultBinding());
        }
        rebuildBindingCaches();
    }

    public InputBinding getBinding(InputAction action) {
        return bindings.get(action);
    }

    public Map<InputAction, InputBinding> getBindings() {
        return Collections.unmodifiableMap(bindings);
    }

    public String getBindingLabel(InputAction action) {
        return bindingLabels.getOrDefault(action, "Unbound");
    }

    public int getMouseScroll() {
        int scroll = (int) scrollOffsetY;
        scrollOffsetY -= scroll;
        return scroll;
    }

    public void resetMouseDelta() {
        firstMouse = true;
        mouseDeltaX = 0.0f;
        mouseDeltaY = 0.0f;
        mousePosition.set(0.0f, 0.0f);
    }

    public void cleanup(){
        if (scrollCallback != null) scrollCallback.close();
    }

    private boolean isBindingDown(InputBinding binding) {
        return switch (binding.type()) {
            case KEYBOARD_KEY -> isKeyDown(binding.code());
            case MOUSE_BUTTON -> isMouseKeyDown(binding.code());
        };
    }

    private boolean isBindingPressed(InputBinding binding) {
        return switch (binding.type()) {
            case KEYBOARD_KEY -> isKeyPressed(binding.code());
            case MOUSE_BUTTON -> isMouseKeyPressed(binding.code());
        };
    }

    private String formatBinding(InputBinding binding) {
        if (binding == null) {
            return "Unbound";
        }

        return switch (binding.type()) {
            case KEYBOARD_KEY -> formatKeyCode(binding.code());
            case MOUSE_BUTTON -> formatMouseButton(binding.code());
        };
    }

    private boolean isValidKey(int key) {
        return key >= 0 && key < keyStates.length;
    }

    private boolean isValidMouseButton(int button) {
        return button >= 0 && button < mouseButtonStates.length;
    }

    private String formatKeyCode(int key) {
        return switch (key) {
            case GLFW_KEY_SPACE -> "Space";
            case GLFW_KEY_ESCAPE -> "Escape";
            case GLFW_KEY_ENTER -> "Enter";
            case GLFW_KEY_TAB -> "Tab";
            case GLFW_KEY_BACKSPACE -> "Backspace";
            case GLFW_KEY_INSERT -> "Insert";
            case GLFW_KEY_DELETE -> "Delete";
            case GLFW_KEY_RIGHT -> "Right";
            case GLFW_KEY_LEFT -> "Left";
            case GLFW_KEY_DOWN -> "Down";
            case GLFW_KEY_UP -> "Up";
            case GLFW_KEY_PAGE_UP -> "Page Up";
            case GLFW_KEY_PAGE_DOWN -> "Page Down";
            case GLFW_KEY_HOME -> "Home";
            case GLFW_KEY_END -> "End";
            case GLFW_KEY_CAPS_LOCK -> "Caps Lock";
            case GLFW_KEY_SCROLL_LOCK -> "Scroll Lock";
            case GLFW_KEY_NUM_LOCK -> "Num Lock";
            case GLFW_KEY_PRINT_SCREEN -> "Print Screen";
            case GLFW_KEY_PAUSE -> "Pause";
            case GLFW_KEY_LEFT_SHIFT -> "Left Shift";
            case GLFW_KEY_LEFT_CONTROL -> "Left Ctrl";
            case GLFW_KEY_LEFT_ALT -> "Left Alt";
            case GLFW_KEY_LEFT_SUPER -> "Left Super";
            case GLFW_KEY_RIGHT_SHIFT -> "Right Shift";
            case GLFW_KEY_RIGHT_CONTROL -> "Right Ctrl";
            case GLFW_KEY_RIGHT_ALT -> "Right Alt";
            case GLFW_KEY_RIGHT_SUPER -> "Right Super";
            case GLFW_KEY_MENU -> "Menu";
            default -> {
                String glfwName = glfwGetKeyName(key, 0);
                if (glfwName != null && !glfwName.isBlank()) {
                    yield glfwName.toUpperCase();
                }
                if (key >= GLFW_KEY_F1 && key <= GLFW_KEY_F25) {
                    yield "F" + (key - GLFW_KEY_F1 + 1);
                }
                if (key >= GLFW_KEY_KP_0 && key <= GLFW_KEY_KP_9) {
                    yield "Numpad " + (key - GLFW_KEY_KP_0);
                }
                yield "Key " + key;
            }
        };
    }

    private String formatMouseButton(int button) {
        return switch (button) {
            case GLFW_MOUSE_BUTTON_LEFT -> "Mouse Left";
            case GLFW_MOUSE_BUTTON_RIGHT -> "Mouse Right";
            case GLFW_MOUSE_BUTTON_MIDDLE -> "Mouse Middle";
            default -> "Mouse " + button;
        };
    }

    private void rebuildBindingCaches() {
        Arrays.fill(trackedKeys, false);
        Arrays.fill(trackedMouseButtons, false);
        Arrays.fill(keyStates, false);
        Arrays.fill(previousKeyStates, false);
        Arrays.fill(mouseButtonStates, false);
        Arrays.fill(previousMouseButtonStates, false);

        activeKeyCodeCount = 0;
        activeMouseButtonCount = 0;
        bindingLabels.clear();

        // The creative inventory always needs the primary button, even when
        // gameplay actions are rebound away from it.
        trackMouseButton(GLFW_MOUSE_BUTTON_LEFT);

        for (InputAction action : InputAction.values()) {
            InputBinding binding = bindings.get(action);
            bindingLabels.put(action, formatBinding(binding));

            if (binding == null) {
                continue;
            }

            switch (binding.type()) {
                case KEYBOARD_KEY -> trackKey(binding.code());
                case MOUSE_BUTTON -> trackMouseButton(binding.code());
            }
        }
    }

    private void trackKey(int keyCode) {
        if (!isValidKey(keyCode) || trackedKeys[keyCode]) {
            return;
        }

        trackedKeys[keyCode] = true;
        activeKeyCodes[activeKeyCodeCount++] = keyCode;
    }

    private void trackMouseButton(int button) {
        if (!isValidMouseButton(button) || trackedMouseButtons[button]) {
            return;
        }

        trackedMouseButtons[button] = true;
        activeMouseButtons[activeMouseButtonCount++] = button;
    }

}

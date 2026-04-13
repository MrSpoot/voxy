package org.weaw.engine.input;

public record InputBinding(InputBindingType type, int code) {

    public static InputBinding key(int keyCode) {
        return new InputBinding(InputBindingType.KEYBOARD_KEY, keyCode);
    }

    public static InputBinding mouseButton(int button) {
        return new InputBinding(InputBindingType.MOUSE_BUTTON, button);
    }
}

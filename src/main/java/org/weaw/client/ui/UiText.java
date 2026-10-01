package org.weaw.client.ui;

import java.util.Locale;
import java.util.MissingResourceException;
import java.util.ResourceBundle;

public final class UiText {
    private ResourceBundle bundle;

    public UiText(String language) {
        setLanguage(language);
    }

    public void setLanguage(String language) {
        Locale locale = "en".equalsIgnoreCase(language) ? Locale.ENGLISH : Locale.FRENCH;
        bundle = ResourceBundle.getBundle("i18n.messages", locale);
    }

    public String get(String key) {
        try {
            return bundle.getString(key);
        } catch (MissingResourceException exception) {
            return key;
        }
    }
}

package org.weaw.client.ui;

import org.junit.jupiter.api.Test;
import org.weaw.persistence.ClientSettings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GameUiStateTest {
    @Test
    void previewIsConsumableOnceAndCancelRestoresTheOpeningSnapshot() {
        ClientSettings initial = ClientSettings.defaults();
        ClientSettings preview = initial.withRuntimePreferences(
                initial.locale(), 1.35f, false, 144, 92.0f, 18,
                initial.graphicsPreferences(), 0.42f, initial.bindings()
        );
        GameUiState state = new GameUiState();
        state.initializeSettings(initial);
        state.setSettingsOpen(true);

        state.previewSettings(preview);
        assertEquals(preview, state.consumeSettingsPreview());
        assertNull(state.consumeSettingsPreview());

        state.requestSettingsCancel();
        assertEquals(initial, state.consumeSettingsCancel());
        assertEquals(initial, state.getSettingsDraft());
        assertNull(state.consumeSettingsCancel());
    }

    @Test
    void applyPromotesTheDraftAndClosesSettings() {
        ClientSettings initial = ClientSettings.defaults();
        ClientSettings applied = initial.withRuntimePreferences(
                "en", 0.8f, true, 60, 105.0f, 10,
                initial.graphicsPreferences(), 0.15f, initial.bindings()
        );
        GameUiState state = new GameUiState();
        state.initializeSettings(initial);
        state.setSettingsOpen(true);

        state.requestSettingsApply(applied);
        assertEquals(applied, state.consumeSettingsApply());
        assertNull(state.consumeSettingsApply());
        state.settingsApplied(applied);

        assertFalse(state.isSettingsOpen());
        state.setSettingsOpen(true);
        assertTrue(state.isSettingsOpen());
        assertEquals(applied, state.getSettingsSnapshot());
        assertEquals(applied, state.getSettingsDraft());
    }
}

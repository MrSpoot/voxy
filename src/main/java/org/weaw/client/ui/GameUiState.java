package org.weaw.client.ui;

import org.weaw.persistence.ClientSettings;

/** Shared commands and visibility state for the in-game UI. */
public final class GameUiState {
    private boolean paused;
    private boolean settingsOpen;
    private boolean debugVisible;
    private boolean resumeRequested;
    private boolean saveRequested;
    private boolean returnToTitleRequested;
    private String statusMessage;
    private ClientSettings activeSettings;
    private ClientSettings settingsSnapshot;
    private ClientSettings settingsDraft;
    private boolean settingsPreviewRequested;
    private boolean settingsApplyRequested;
    private boolean settingsCancelRequested;

    public boolean isPaused() {
        return paused;
    }

    public void setPaused(boolean paused) {
        this.paused = paused;
        if (!paused) {
            settingsOpen = false;
        }
    }

    public boolean isSettingsOpen() {
        return settingsOpen;
    }

    public void setSettingsOpen(boolean settingsOpen) {
        this.settingsOpen = settingsOpen;
        if (settingsOpen && activeSettings != null) {
            settingsSnapshot = activeSettings;
            settingsDraft = activeSettings;
        }
    }

    public void initializeSettings(ClientSettings settings) {
        activeSettings = settings;
        settingsSnapshot = settings;
        settingsDraft = settings;
    }

    public ClientSettings getSettingsDraft() {
        return settingsDraft;
    }

    public ClientSettings getSettingsSnapshot() {
        return settingsSnapshot;
    }

    public void previewSettings(ClientSettings draft) {
        settingsDraft = draft;
        settingsPreviewRequested = true;
    }

    public ClientSettings consumeSettingsPreview() {
        if (!settingsPreviewRequested) return null;
        settingsPreviewRequested = false;
        return settingsDraft;
    }

    public void requestSettingsApply(ClientSettings draft) {
        settingsDraft = draft;
        settingsApplyRequested = true;
    }

    public ClientSettings consumeSettingsApply() {
        if (!settingsApplyRequested) return null;
        settingsApplyRequested = false;
        return settingsDraft;
    }

    public void requestSettingsCancel() {
        settingsCancelRequested = true;
    }

    public ClientSettings consumeSettingsCancel() {
        if (!settingsCancelRequested) return null;
        settingsCancelRequested = false;
        settingsDraft = settingsSnapshot;
        return settingsSnapshot;
    }

    public void settingsApplied(ClientSettings settings) {
        activeSettings = settings;
        settingsSnapshot = settings;
        settingsDraft = settings;
        settingsOpen = false;
    }

    public boolean isDebugVisible() {
        return debugVisible;
    }

    public void toggleDebugVisible() {
        debugVisible = !debugVisible;
    }

    public void requestResume() {
        resumeRequested = true;
    }

    public boolean consumeResumeRequest() {
        boolean requested = resumeRequested;
        resumeRequested = false;
        return requested;
    }

    public void requestSave() {
        saveRequested = true;
        statusMessage = "Sauvegarde demandée…";
    }

    public boolean consumeSaveRequest() {
        boolean requested = saveRequested;
        saveRequested = false;
        return requested;
    }

    public void requestReturnToTitle() {
        returnToTitleRequested = true;
    }

    public boolean consumeReturnToTitleRequest() {
        boolean requested = returnToTitleRequested;
        returnToTitleRequested = false;
        return requested;
    }

    public String getStatusMessage() {
        return statusMessage;
    }

    public void setStatusMessage(String statusMessage) {
        this.statusMessage = statusMessage;
    }
}

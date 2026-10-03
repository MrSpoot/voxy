package org.weaw.client.ui;

import imgui.ImGui;
import imgui.ImGuiIO;
import imgui.ImGuiStyle;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiCond;
import imgui.flag.ImGuiWindowFlags;
import imgui.gl3.ImGuiImplGl3;
import imgui.glfw.ImGuiImplGlfw;
import imgui.type.ImBoolean;
import imgui.type.ImInt;
import imgui.type.ImString;
import org.weaw.Game;
import org.weaw.engine.ui.ResponsiveImGuiStyle;
import org.weaw.engine.window.DisplayResolution;
import org.weaw.engine.window.DisplayResolutionCatalog;
import org.weaw.engine.window.Window;
import org.weaw.engine.input.InputAction;
import org.weaw.engine.input.InputBinding;
import org.weaw.game.WorldHeightRange;
import org.weaw.game.WorldTimeState;
import org.weaw.game.generation.GenerationConfig;
import org.weaw.network.protocol.Protocol;
import org.weaw.persistence.ClientSettings;
import org.weaw.persistence.ClientSettingsRepository;
import org.weaw.persistence.AntiAliasingMode;
import org.weaw.persistence.GraphicsPreferences;
import org.weaw.persistence.GraphicsPreset;
import org.weaw.persistence.PlayerProfile;
import org.weaw.persistence.PlayerProfileRepository;
import org.weaw.persistence.StorageOptions;
import org.weaw.persistence.WorldRepository;
import org.weaw.persistence.WorldSummary;
import org.weaw.runtime.LaunchOptions;
import org.weaw.runtime.NetworkMode;
import org.weaw.runtime.NetworkOptions;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;

import static org.lwjgl.opengl.GL11.GL_COLOR_BUFFER_BIT;
import static org.lwjgl.opengl.GL11.GL_CULL_FACE;
import static org.lwjgl.opengl.GL11.GL_DEPTH_TEST;
import static org.lwjgl.opengl.GL11.GL_FILL;
import static org.lwjgl.opengl.GL11.GL_FRONT_AND_BACK;
import static org.lwjgl.opengl.GL11.glClear;
import static org.lwjgl.opengl.GL11.glClearColor;
import static org.lwjgl.opengl.GL11.glDisable;
import static org.lwjgl.opengl.GL11.glPolygonMode;

/** Interactive title screen and all pre-game flows. */
public final class ClientFrontend implements AutoCloseable {
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
    private static final int ROOT_FLAGS = ImGuiWindowFlags.NoDecoration
            | ImGuiWindowFlags.NoMove
            | ImGuiWindowFlags.NoResize
            | ImGuiWindowFlags.NoSavedSettings;

    private final LaunchOptions baseOptions;
    private final ClientSettingsRepository settingsRepository;
    private final PlayerProfileRepository profileRepository;
    private final WorldRepository worldRepository;
    private final SecureRandom random = new SecureRandom();

    private ClientSettings settings;
    private UiText text;
    private Window window;
    private ImGuiImplGlfw imGuiGlfw;
    private ImGuiImplGl3 imGuiGl3;
    private UiFonts.InstalledFont installedFont;
    private float activeUiScale = 1.0f;
    private ResponsiveImGuiStyle responsiveStyle;
    private boolean responsiveStylePushed;
    private Screen screen = Screen.TITLE;
    private List<WorldSummary> worlds = List.of();
    private int selectedWorld = -1;
    private String lastTrashToken;
    private String errorMessage;
    private boolean renaming;
    private final ImString renameValue = new ImString(128);

    private final ImString worldName = new ImString(128);
    private final ImString worldSeed = new ImString(64);
    private final ImInt preset = new ImInt(0);
    private final int[] minChunkY = {-4};
    private final int[] maxChunkY = {3};
    private final int[] simulationDistance = {12};
    private final int[] renderDistance = {12};
    private final int[] autosaveSeconds = {60};
    private final int[] dayLengthMinutes = {20};
    private final float[] amplitude = {25.0f};
    private final int[] baseHeight = {0};
    private final int[] waterLevel = {-10};
    private final float[] terrainFrequency = {0.3f};
    private final int[] terrainOctaves = {4};
    private final float[] terrainLacunarity = {2.0f};
    private final float[] terrainGain = {0.5f};
    private final float[] treeRarity = {0.5f};
    private final float[] treeSteepness = {2.0f};
    private boolean createForHost;

    private final ImString serverAddress = new ImString(256);
    private final int[] networkViewDistance = {Protocol.DEFAULT_VIEW_DISTANCE};
    private final int[] hostPort = {Protocol.DEFAULT_PORT};
    private final int[] hostMaxPlayers = {Protocol.DEFAULT_MAX_PLAYERS};
    private final ImBoolean hostLanVisible = new ImBoolean(true);

    private List<DisplayResolution> displayResolutions = List.of(new DisplayResolution(1280, 720));
    private String[] displayResolutionLabels = {"1280 × 720"};
    private final ImInt settingResolution = new ImInt(0);
    private final ImBoolean settingFullscreen = new ImBoolean(false);
    private final ImBoolean settingVsync = new ImBoolean(true);
    private final int[] settingFrameRateLimit = {0};
    private final float[] settingUiScale = {1.0f};
    private final float[] settingFov = {90.0f};
    private final int[] settingRenderDistance = {Protocol.DEFAULT_VIEW_DISTANCE};
    private final float[] settingSensitivity = {0.15f};
    private final ImInt settingLanguage = new ImInt(0);
    private final ImInt settingGraphicsPreset = new ImInt(GraphicsPreset.AUTO.ordinal());
    private final ImInt settingAntiAliasing = new ImInt(AntiAliasingMode.FXAA.ordinal());
    private final ImBoolean settingClouds = new ImBoolean(true);
    private final ImBoolean settingWaterWaves = new ImBoolean(true);
    private final ImBoolean settingLighting = new ImBoolean(true);
    private final ImBoolean settingBlockLighting = new ImBoolean(true);
    private final ImBoolean settingToneMapping = new ImBoolean(true);
    private final ImBoolean settingAutoExposure = new ImBoolean(true);
    private final float[] settingExposure = {0.045f};
    private final float[] settingContrast = {1.0f};
    private final float[] settingSaturation = {1.0f};
    private final float[] settingGamma = {2.2f};
    private List<PlayerProfile> profiles = List.of();
    private String[] profileLabels = {};
    private final ImInt selectedProfile = new ImInt(0);
    private final ImString profileDisplayName = new ImString(32);
    private final ImString newProfileName = new ImString(32);
    private final Map<String, ClientSettings.BindingSetting> editedBindings = new LinkedHashMap<>();
    private InputAction bindingCaptureAction;
    private int bindingCaptureFrame;

    private Game loadingGame;
    private CompletableFuture<Game> loadingFuture;
    private Executor loadingExecutor;

    public ClientFrontend(LaunchOptions baseOptions) {
        this(baseOptions, null);
    }

    public ClientFrontend(LaunchOptions baseOptions, String initialError) {
        this.baseOptions = baseOptions;
        settingsRepository = new ClientSettingsRepository(baseOptions.storage().dataDirectory());
        ClientSettings loaded;
        try {
            loaded = settingsRepository.load();
        } catch (RuntimeException exception) {
            loaded = ClientSettings.defaults();
            errorMessage = exception.getMessage();
        }
        settings = loaded;
        text = new UiText(settings.locale());
        profileRepository = new PlayerProfileRepository(baseOptions.storage().dataDirectory());
        initializeProfiles();
        worldRepository = new WorldRepository(baseOptions.storage().dataDirectory());
        syncFieldsFromSettings();
        resetWorldForm();
        refreshWorlds();
        if (initialError != null && !initialError.isBlank()) {
            errorMessage = initialError;
        }
    }

    public FrontendResult run(Window sharedWindow, Executor executor) {
        window = sharedWindow;
        loadingExecutor = executor;
        create();
        while (!window.shouldClose()) {
            renderFrame();
            window.update();
            FrontendResult loadingResult = pollLoadingResult();
            if (loadingResult != null) {
                return loadingResult;
            }
        }
        return FrontendResult.quitting();
    }

    private void create() {
        window.setCursorLocked(false);
        window.setVsync(settings.vsync());
        refreshDisplayResolutions();
        syncResolutionFromSettings();
        glPolygonMode(GL_FRONT_AND_BACK, GL_FILL);
        glDisable(GL_CULL_FACE);
        glDisable(GL_DEPTH_TEST);
        ImGui.createContext();
        ImGuiIO io = ImGui.getIO();
        io.setIniFilename(null);
        installedFont = UiFonts.installPixelFont(io, 22.0f);
        applyTheme();
        responsiveStyle = ResponsiveImGuiStyle.capture(ImGui.getStyle());
        imGuiGlfw = new ImGuiImplGlfw();
        imGuiGlfw.init(window.getId(), true);
        imGuiGl3 = new ImGuiImplGl3();
        imGuiGl3.init("#version 460 core");
    }

    private void renderFrame() {
        glClearColor(0.03f, 0.04f, 0.06f, 1.0f);
        glClear(GL_COLOR_BUFFER_BIT);
        imGuiGlfw.newFrame();
        imGuiGl3.newFrame();
        ImGui.newFrame();
        responsiveStylePushed = false;
        try {
            switch (screen) {
                case TITLE -> renderTitle();
                case WORLDS -> renderWorlds();
                case CREATE_WORLD -> renderCreateWorld();
                case MULTIPLAYER -> renderMultiplayer();
                case HOST -> renderHost();
                case SETTINGS -> renderSettings();
                case LOADING -> renderLoading();
            }
            renderErrorPopup();
        } finally {
            if (responsiveStylePushed) {
                responsiveStyle.popScaled();
                responsiveStylePushed = false;
            }
        }
        ImGui.render();
        imGuiGl3.renderDrawData(ImGui.getDrawData());
    }

    private void renderTitle() {
        beginCentered("##title", 470, 590);
        ImGui.setWindowFontScale(3.0f);
        centeredText("VOXY");
        ImGui.setWindowFontScale(1.0f);
        centeredText("A voxel world of your own");
        ImGui.dummy(1, 20);
        if (ImGui.combo(text.get("profile.active"), selectedProfile, profileLabels)) {
            activateSelectedProfile();
        }
        ImGui.dummy(1, 12);
        if (wideButton(text.get("title.singleplayer"))) {
            refreshWorlds();
            screen = Screen.WORLDS;
        }
        if (wideButton(text.get("title.multiplayer"))) {
            screen = Screen.MULTIPLAYER;
        }
        if (wideButton(text.get("title.host"))) {
            refreshWorlds();
            screen = Screen.HOST;
        }
        ImGui.dummy(1, 12);
        if (wideButton(text.get("title.settings"))) {
            refreshDisplayResolutions();
            syncFieldsFromSettings();
            screen = Screen.SETTINGS;
        }
        if (wideButton(text.get("title.quit"))) {
            window.requestClose();
        }
        ImGui.end();
    }

    private void renderWorlds() {
        beginCentered("##worlds", 760, 650);
        heading(text.get("worlds.title"));
        if (worlds.isEmpty()) {
            ImGui.textWrapped(text.get("worlds.empty"));
        } else if (ImGui.beginChild("world-list", 0, ui(445), true)) {
            for (int index = 0; index < worlds.size(); index++) {
                WorldSummary summary = worlds.get(index);
                String status = summary.playable() ? "" : "  [" + text.get("worlds.invalid") + "]";
                if (ImGui.selectable(summary.displayName() + status + "##" + summary.worldKey(), selectedWorld == index)) {
                    selectedWorld = index;
                    renaming = false;
                }
                ImGui.textDisabled(worldDetails(summary));
                ImGui.separator();
            }
            ImGui.endChild();
        }

        WorldSummary selected = selectedWorld();
        ImGui.beginDisabled(selected == null || !selected.playable());
        if (ImGui.button(text.get("common.play"), ui(150), ui(40))) {
            launchExistingWorld(selected, NetworkMode.SOLO, settings.defaultPort(), true);
        }
        ImGui.endDisabled();
        ImGui.sameLine();
        if (ImGui.button(text.get("common.create"), ui(150), ui(40))) {
            createForHost = false;
            resetWorldForm();
            screen = Screen.CREATE_WORLD;
        }
        ImGui.sameLine();
        ImGui.beginDisabled(selected == null);
        if (ImGui.button(text.get("common.rename"), ui(150), ui(40))) {
            renaming = true;
            renameValue.set(selected.displayName());
        }
        ImGui.sameLine();
        if (ImGui.button(text.get("common.delete"), ui(150), ui(40))) {
            ImGui.openPopup("confirm-delete");
        }
        ImGui.endDisabled();

        if (renaming) {
            ImGui.inputText("##rename", renameValue);
            ImGui.sameLine();
            if (ImGui.button(text.get("common.apply"))) {
                try {
                    worldRepository.rename(selected.worldKey(), renameValue.get());
                    renaming = false;
                    refreshWorlds();
                } catch (RuntimeException exception) {
                    errorMessage = exception.getMessage();
                }
            }
        }
        renderDeletePopup(selected);
        if (lastTrashToken != null) {
            ImGui.text("Monde déplacé dans la corbeille.");
            ImGui.sameLine();
            if (ImGui.smallButton("Annuler la suppression")) {
                try {
                    worldRepository.restoreFromTrash(lastTrashToken);
                    lastTrashToken = null;
                    refreshWorlds();
                } catch (RuntimeException exception) {
                    errorMessage = exception.getMessage();
                }
            }
        }
        if (ImGui.button(text.get("common.back"), ui(150), ui(38))) {
            screen = Screen.TITLE;
        }
        ImGui.end();
    }

    private void renderCreateWorld() {
        beginCentered("##create-world", 720, 760);
        heading(text.get("create.title"));
        ImGui.inputText(text.get("create.name"), worldName);
        ImGui.inputText(text.get("create.seed"), worldSeed);
        if (ImGui.combo(text.get("create.preset"), preset, new String[]{"Standard", "Plaines", "Montagnes", "Personnalisé"})) {
            applyPreset(preset.get());
        }
        ImGui.sliderInt("Hauteur minimale (chunk)", minChunkY, -16, 0);
        ImGui.sliderInt("Hauteur maximale (chunk)", maxChunkY, 1, 16);
        ImGui.sliderInt("Distance de simulation", simulationDistance, 2, 64);
        ImGui.sliderInt("Distance de rendu initiale", renderDistance, 2, 64);
        ImGui.sliderInt("Autosave (secondes)", autosaveSeconds, 0, 600);
        ImGui.sliderInt("Durée d'un jour (minutes)", dayLengthMinutes, 1, 120);
        if (ImGui.collapsingHeader(text.get("create.advanced"))) {
            ImGui.sliderFloat("Amplitude du relief", amplitude, 0.0f, 64.0f);
            ImGui.sliderInt("Hauteur de base", baseHeight, -48, 48);
            ImGui.sliderInt("Niveau de l'eau", waterLevel, -64, 48);
            ImGui.sliderFloat("Fréquence du terrain", terrainFrequency, 0.05f, 1.0f);
            ImGui.sliderInt("Octaves", terrainOctaves, 1, 8);
            ImGui.sliderFloat("Lacunarité", terrainLacunarity, 1.0f, 4.0f);
            ImGui.sliderFloat("Gain", terrainGain, 0.1f, 0.9f);
            ImGui.sliderFloat("Rareté des arbres", treeRarity, 0.1f, 4.0f);
            ImGui.sliderFloat("Répartition des arbres", treeSteepness, 0.25f, 5.0f);
        }
        String validation = validateWorldForm();
        if (validation != null) {
            ImGui.textColored(1.0f, 0.45f, 0.35f, 1.0f, validation);
        }
        ImGui.beginDisabled(validation != null);
        if (ImGui.button(text.get("create.summary"), ui(230), ui(42))) {
            ImGui.openPopup("confirm-create");
        }
        ImGui.endDisabled();
        ImGui.sameLine();
        if (ImGui.button(text.get("common.back"), ui(150), ui(42))) {
            screen = createForHost ? Screen.HOST : Screen.WORLDS;
        }
        renderCreateSummaryPopup();
        ImGui.end();
    }

    private void renderCreateSummaryPopup() {
        if (!ImGui.beginPopupModal("confirm-create", ROOT_FLAGS)) {
            return;
        }
        ImGui.text("Nom / Name: " + worldName.get().trim());
        ImGui.text("Seed: " + (worldSeed.get().isBlank() ? "aléatoire / random" : worldSeed.get().trim()));
        ImGui.text("Hauteur / Height: " + minChunkY[0] + " → " + maxChunkY[0] + " chunks");
        ImGui.text("Simulation: " + simulationDistance[0] + " chunks");
        ImGui.text("Rendu / Render: " + renderDistance[0] + " chunks");
        ImGui.text("Cycle jour/nuit: " + dayLengthMinutes[0] + " min");
        ImGui.text("Mode: " + (createForHost ? "Hôte / Host" : "Solo"));
        ImGui.separator();
        if (ImGui.button(text.get("common.create"), ui(160), ui(40))) {
            ImGui.closeCurrentPopup();
            launchCreatedWorld();
        }
        ImGui.sameLine();
        if (ImGui.button(text.get("common.cancel"), ui(160), ui(40))) {
            ImGui.closeCurrentPopup();
        }
        ImGui.endPopup();
    }

    private void renderMultiplayer() {
        beginCentered("##multiplayer", 650, 610);
        heading(text.get("multiplayer.title"));
        ImGui.text(text.get("profile.active") + ": " + activeProfile().displayName());
        ImGui.inputText(text.get("multiplayer.address"), serverAddress);
        ImGui.sliderInt(text.get("multiplayer.view"), networkViewDistance, 2, 64);
        if (wideButton(text.get("multiplayer.connect"))) {
            try {
                ServerAddress endpoint = ServerAddress.parse(serverAddress.get(), settings.defaultPort());
                savePlayerAndRecent(endpoint);
                NetworkOptions network = new NetworkOptions(
                        NetworkMode.CONNECT, endpoint.host(), endpoint.port(), activeProfile().displayName(),
                        settings.defaultMaxPlayers(), baseOptions.network().worldSeed(), networkViewDistance[0], true
                );
                startLoading(baseOptions.forSession(network, storageForActiveProfile(baseOptions.storage())));
            } catch (RuntimeException exception) {
                errorMessage = exception.getMessage();
            }
        }
        ImGui.separator();
        ImGui.text(text.get("multiplayer.recent"));
        for (ClientSettings.RecentServer recent : settings.recentServers()) {
            if (ImGui.selectable(recent.label())) {
                serverAddress.set(recent.label());
            }
        }
        if (ImGui.button(text.get("common.back"), ui(150), ui(40))) {
            screen = Screen.TITLE;
        }
        ImGui.end();
    }

    private void renderHost() {
        beginCentered("##host", 720, 690);
        heading(text.get("host.title"));
        if (worlds.isEmpty()) {
            ImGui.textWrapped(text.get("worlds.empty"));
        } else if (ImGui.beginChild("host-worlds", 0, ui(350), true)) {
            for (int index = 0; index < worlds.size(); index++) {
                WorldSummary summary = worlds.get(index);
                if (ImGui.selectable(summary.displayName() + "##host-" + summary.worldKey(), selectedWorld == index)) {
                    selectedWorld = index;
                }
                ImGui.textDisabled(worldDetails(summary));
            }
            ImGui.endChild();
        }
        ImGui.sliderInt(text.get("host.port"), hostPort, 1, 65_535);
        ImGui.sliderInt(text.get("host.players"), hostMaxPlayers, 1, Protocol.MAX_PLAYERS);
        ImGui.checkbox(text.get("host.lan"), hostLanVisible);
        WorldSummary selected = selectedWorld();
        ImGui.beginDisabled(selected == null || !selected.playable());
        if (ImGui.button(text.get("common.play"), ui(180), ui(42))) {
            launchExistingWorld(selected, NetworkMode.HOST, hostPort[0], hostLanVisible.get());
        }
        ImGui.endDisabled();
        ImGui.sameLine();
        if (ImGui.button(text.get("common.create"), ui(180), ui(42))) {
            createForHost = true;
            resetWorldForm();
            screen = Screen.CREATE_WORLD;
        }
        ImGui.sameLine();
        if (ImGui.button(text.get("common.back"), ui(150), ui(42))) {
            screen = Screen.TITLE;
        }
        ImGui.end();
    }

    private void renderSettings() {
        beginCentered("##settings", 780, 690);
        heading(text.get("title.settings"));
        if (ImGui.beginTabBar("settings-tabs")) {
            if (ImGui.beginTabItem(text.get("settings.graphics"))) {
                ImGui.combo(text.get("settings.resolution"), settingResolution, displayResolutionLabels);
                ImGui.checkbox("Plein écran", settingFullscreen);
                if (ImGui.checkbox(text.get("settings.vsync"), settingVsync)) {
                    window.setVsync(settingVsync.get());
                }
                ImGui.sliderInt("Limite FPS (0 = illimitée)", settingFrameRateLimit, 0, 360);
                ImGui.sliderFloat(text.get("settings.uiScale"), settingUiScale, 0.75f, 2.0f);
                ImGui.sliderFloat(text.get("settings.fov"), settingFov, 60.0f, 120.0f);
                ImGui.sliderInt(text.get("settings.renderDistance"), settingRenderDistance,
                        Protocol.MIN_VIEW_DISTANCE, Protocol.MAX_VIEW_DISTANCE);
                if (ImGui.combo("Qualité", settingGraphicsPreset,
                        new String[]{"Auto", "Bas", "Moyen", "Élevé", "Personnalisé"})) {
                    applyGraphicsPresetToFields(GraphicsPreset.values()[settingGraphicsPreset.get()]);
                }
                if (ImGui.collapsingHeader("Détails graphiques")) {
                    boolean customized = ImGui.combo("Anti-aliasing", settingAntiAliasing,
                            new String[]{"Désactivé", "FXAA", "MSAA 2×", "MSAA 4×"});
                    customized |= ImGui.checkbox("Nuages", settingClouds);
                    customized |= ImGui.checkbox("Vagues", settingWaterWaves);
                    customized |= ImGui.checkbox("Éclairage", settingLighting);
                    customized |= ImGui.checkbox("Lumières de blocs", settingBlockLighting);
                    customized |= ImGui.checkbox("Tone mapping", settingToneMapping);
                    customized |= ImGui.checkbox("Exposition automatique", settingAutoExposure);
                    customized |= ImGui.sliderFloat("Exposition", settingExposure, -4.0f, 4.0f);
                    customized |= ImGui.sliderFloat("Contraste", settingContrast, 0.5f, 2.0f);
                    customized |= ImGui.sliderFloat("Saturation", settingSaturation, 0.0f, 2.0f);
                    customized |= ImGui.sliderFloat("Gamma", settingGamma, 0.5f, 3.0f);
                    if (customized) {
                        settingGraphicsPreset.set(GraphicsPreset.CUSTOM.ordinal());
                    }
                }
                ImGui.endTabItem();
            }
            if (ImGui.beginTabItem(text.get("settings.controls"))) {
                ImGui.sliderFloat(text.get("settings.sensitivity"), settingSensitivity, 0.01f, 1.0f);
                ImGui.textWrapped("Cliquez sur une commande puis appuyez sur une touche ou un bouton de souris.");
                if (ImGui.beginChild("binding-list", 0, ui(300), true)) {
                    for (InputAction action : InputAction.values()) {
                        ImGui.text(action.getDisplayName());
                        ImGui.sameLine(ui(255.0f));
                        String label = bindingCaptureAction == action
                                ? "...##bind-" + action.getId()
                                : bindingLabel(effectiveBinding(action)) + "##bind-" + action.getId();
                        if (ImGui.button(label, ui(150.0f), ui(28.0f))) {
                            bindingCaptureAction = action;
                            bindingCaptureFrame = ImGui.getFrameCount();
                        }
                        if (hasBindingConflict(action)) {
                            ImGui.sameLine();
                            ImGui.textColored(1.0f, 0.35f, 0.25f, 1.0f, "Conflit");
                        }
                    }
                    ImGui.endChild();
                }
                if (ImGui.button("Réinitialiser les touches")) {
                    editedBindings.clear();
                    bindingCaptureAction = null;
                }
                ImGui.endTabItem();
            }
            if (ImGui.beginTabItem(text.get("settings.gameplay"))) {
                if (ImGui.combo(text.get("settings.language"), settingLanguage, new String[]{"Français", "English"})) {
                    text.setLanguage(settingLanguage.get() == 0 ? "fr" : "en");
                }
                ImGui.textWrapped("Le HUD créatif affiche uniquement les informations de gameplay disponibles.");
                ImGui.endTabItem();
            }
            if (ImGui.beginTabItem(text.get("settings.profile"))) {
                renderProfileSettings();
                ImGui.endTabItem();
            }
            if (ImGui.beginTabItem(text.get("settings.network"))) {
                ImGui.text(text.get("profile.active") + ": " + activeProfile().displayName());
                ImGui.sliderInt(text.get("host.port"), hostPort, 1, 65_535);
                ImGui.sliderInt(text.get("multiplayer.view"), networkViewDistance, 2, 64);
                ImGui.sliderInt(text.get("host.players"), hostMaxPlayers, 1, Protocol.MAX_PLAYERS);
                ImGui.endTabItem();
            }
            ImGui.endTabBar();
        }
        captureBindingInput();
        if (ImGui.button(text.get("common.apply"), ui(160), ui(42))) {
            applySettings();
        }
        ImGui.sameLine();
        if (ImGui.button(text.get("common.defaults"), ui(180), ui(42))) {
            settings = ClientSettings.defaults().withActiveProfile(activeProfile());
            syncFieldsFromSettings();
            text.setLanguage(settings.locale());
        }
        ImGui.sameLine();
        if (ImGui.button(text.get("common.cancel"), ui(160), ui(42))) {
            syncFieldsFromSettings();
            text.setLanguage(settings.locale());
            window.setVsync(settings.vsync());
            screen = Screen.TITLE;
        }
        ImGui.end();
    }

    private void renderProfileSettings() {
        if (ImGui.combo(text.get("profile.active"), selectedProfile, profileLabels)) {
            activateSelectedProfile();
        }
        PlayerProfile profile = activeProfile();
        ImGui.inputText(text.get("profile.name"), profileDisplayName);
        ImGui.textDisabled(text.get("profile.uuid") + ": " + profile.id());
        if (ImGui.button(text.get("profile.rename"), ui(190), ui(38))) {
            try {
                PlayerProfile renamed = profileRepository.rename(profile.key(), profileDisplayName.get());
                refreshProfiles(renamed.key());
                persistActiveProfile();
            } catch (RuntimeException exception) {
                errorMessage = messageFor(exception);
            }
        }
        ImGui.separator();
        ImGui.textWrapped(text.get("profile.createHelp"));
        if (ImGui.button(text.get("profile.create"), ui(190), ui(38))) {
            newProfileName.set("");
            ImGui.openPopup("create-profile");
        }
        renderCreateProfilePopup();
    }

    private void renderCreateProfilePopup() {
        if (!ImGui.beginPopupModal("create-profile", ROOT_FLAGS)) {
            return;
        }
        ImGui.text(text.get("profile.create"));
        ImGui.inputText(text.get("profile.name"), newProfileName);
        ImGui.beginDisabled(newProfileName.get().isBlank());
        if (ImGui.button(text.get("common.create"), ui(150), ui(38))) {
            try {
                PlayerProfile created = profileRepository.create(newProfileName.get());
                refreshProfiles(created.key());
                persistActiveProfile();
                ImGui.closeCurrentPopup();
            } catch (RuntimeException exception) {
                errorMessage = messageFor(exception);
            }
        }
        ImGui.endDisabled();
        ImGui.sameLine();
        if (ImGui.button(text.get("common.cancel"), ui(150), ui(38))) {
            ImGui.closeCurrentPopup();
        }
        ImGui.endPopup();
    }

    private void renderLoading() {
        beginCentered("##loading", 520, 220);
        heading(text.get("loading.title"));
        Game.LoadProgress progress = loadingGame == null
                ? new Game.LoadProgress(Game.LoadStage.STARTING, 0.0f)
                : loadingGame.loadProgress();
        ImGui.textWrapped(loadStageLabel(progress.stage()));
        ImGui.progressBar(Math.min(0.99f, progress.fraction()), -1, ui(28), "");
        if (wideButton(text.get("loading.cancel"))) {
            if (loadingGame != null) {
                loadingGame.cancelPreparation();
            }
            loadingGame = null;
            loadingFuture = null;
            screen = Screen.TITLE;
        }
        ImGui.end();
    }

    private void renderDeletePopup(WorldSummary selected) {
        if (!ImGui.beginPopupModal("confirm-delete", ROOT_FLAGS)) {
            return;
        }
        ImGui.textWrapped("Déplacer ce monde dans la corbeille récupérable ?");
        if (ImGui.button(text.get("common.delete"), ui(140), ui(38)) && selected != null) {
            try {
                lastTrashToken = worldRepository.moveToTrash(selected.worldKey());
                ImGui.closeCurrentPopup();
                refreshWorlds();
            } catch (RuntimeException exception) {
                errorMessage = exception.getMessage();
            }
        }
        ImGui.sameLine();
        if (ImGui.button(text.get("common.cancel"), ui(140), ui(38))) {
            ImGui.closeCurrentPopup();
        }
        ImGui.endPopup();
    }

    private void renderErrorPopup() {
        if (errorMessage == null) {
            return;
        }
        ImGui.openPopup("error-popup");
        if (ImGui.beginPopupModal("error-popup", ROOT_FLAGS)) {
            ImGui.textWrapped(errorMessage);
            if (ImGui.button("OK", ui(120), ui(36))) {
                errorMessage = null;
                ImGui.closeCurrentPopup();
            }
            ImGui.endPopup();
        }
    }

    private void launchExistingWorld(WorldSummary world, NetworkMode mode, int port, boolean lanVisible) {
        StorageOptions storage = storageFor(world.worldKey(), world.displayName(), false, false);
        NetworkOptions network = new NetworkOptions(
                mode, "127.0.0.1", port, activeProfile().displayName(), hostMaxPlayers[0],
                world.seed(), settings.defaultViewDistance(), lanVisible
        );
        startLoading(baseOptions.forSession(network, storage));
    }

    private void launchCreatedWorld() {
        long seed;
        try {
            seed = worldSeed.get().isBlank() ? random.nextLong() : Long.parseLong(worldSeed.get().trim());
        } catch (NumberFormatException exception) {
            errorMessage = "Seed invalide";
            return;
        }
        String key = worldRepository.uniqueWorldKey(worldName.get());
        StorageOptions storage = storageFor(key, worldName.get(), true, true);
        NetworkMode mode = createForHost ? NetworkMode.HOST : NetworkMode.SOLO;
        NetworkOptions network = new NetworkOptions(
                mode, "127.0.0.1", hostPort[0], activeProfile().displayName(), hostMaxPlayers[0],
                seed, renderDistance[0], hostLanVisible.get()
        );
        GenerationConfig generation = new GenerationConfig(
                seed, amplitude[0], baseHeight[0], waterLevel[0], terrainFrequency[0], terrainOctaves[0],
                terrainLacunarity[0], terrainGain[0], 999, treeRarity[0], treeSteepness[0],
                GenerationConfig.CURRENT_GENERATOR_VERSION
        );
        LaunchOptions options = baseOptions.forSession(network, storage)
                .withWorldHeightRange(new WorldHeightRange(minChunkY[0], maxChunkY[0]))
                .withGenerationConfig(generation);
        startLoading(options);
    }

    private StorageOptions storageFor(String key, String name, boolean seedExplicit, boolean heightExplicit) {
        return new StorageOptions(
                baseOptions.storage().dataDirectory(), key, name, activeProfile().key(),
                activeProfile().displayName(), autosaveSeconds[0], simulationDistance[0], renderDistance[0],
                dayLengthMinutes[0] * 60,
                seedExplicit, heightExplicit
        );
    }

    private StorageOptions storageForActiveProfile(StorageOptions source) {
        return new StorageOptions(
                source.dataDirectory(), source.worldKey(), source.worldName(), activeProfile().key(),
                activeProfile().displayName(), source.autosaveSeconds(), source.simulationDistanceChunks(),
                source.defaultRenderDistanceChunks(), source.dayLengthSeconds(), source.seedExplicit(), source.heightExplicit()
        );
    }

    private void startLoading(LaunchOptions options) {
        loadingGame = new Game(options);
        Game game = loadingGame;
        loadingFuture = CompletableFuture.supplyAsync(() -> {
            game.prepare();
            return game;
        }, loadingExecutor);
        screen = Screen.LOADING;
    }

    private FrontendResult pollLoadingResult() {
        if (screen != Screen.LOADING || loadingFuture == null || !loadingFuture.isDone()) {
            return null;
        }
        try {
            Game game = loadingFuture.join();
            loadingFuture = null;
            loadingGame = null;
            return FrontendResult.launch(game);
        } catch (CompletionException exception) {
            errorMessage = messageFor(exception);
            loadingFuture = null;
            loadingGame = null;
            screen = Screen.TITLE;
            return null;
        }
    }

    private String loadStageLabel(Game.LoadStage stage) {
        return switch (stage) {
            case STARTING, REGISTRY -> "Initialisation / Initializing…";
            case SETTINGS -> "Lecture des paramètres / Reading settings…";
            case PROFILE -> "Préparation du profil / Preparing profile…";
            case CONNECTING -> "Connexion au serveur / Connecting…";
            case WORLD -> "Chargement du monde / Loading world…";
            case SERVER -> "Démarrage du serveur local / Starting local server…";
            case HANDSHAKE -> "Négociation du protocole / Protocol handshake…";
            case READY -> "Finalisation / Finalizing…";
        };
    }

    private void savePlayerAndRecent(ServerAddress address) {
        settings = new ClientSettings(
                ClientSettings.CURRENT_FORMAT_VERSION, settings.locale(), settings.uiScale(),
                settings.windowWidth(), settings.windowHeight(), settings.fullscreen(), settings.vsync(),
                settings.frameRateLimit(), settings.fieldOfView(), settings.renderDistanceChunks(),
                settings.graphicsPreferences(), settings.mouseSensitivity(),
                activeProfile().displayName(), activeProfile().key(), settings.defaultPort(),
                networkViewDistance[0], settings.defaultMaxPlayers(),
                Map.copyOf(editedBindings), settings.recentServers()
        ).withRecentServer(address.host(), address.port());
        settingsRepository.save(settings);
    }

    private void applySettings() {
        DisplayResolution selectedResolution = selectedDisplayResolution();
        settings = new ClientSettings(
                ClientSettings.CURRENT_FORMAT_VERSION,
                settingLanguage.get() == 0 ? "fr" : "en",
                settingUiScale[0], selectedResolution.width(), selectedResolution.height(), settingFullscreen.get(),
                settingVsync.get(), settingFrameRateLimit[0], settingFov[0], settingRenderDistance[0],
                graphicsPreferencesFromFields(), settingSensitivity[0],
                activeProfile().displayName(), activeProfile().key(), hostPort[0],
                networkViewDistance[0], hostMaxPlayers[0],
                Map.copyOf(editedBindings), settings.recentServers()
        );
        try {
            settingsRepository.save(settings);
            text.setLanguage(settings.locale());
            window.applyDisplayMode(settings.windowWidth(), settings.windowHeight(), settings.fullscreen());
            window.setVsync(settings.vsync());
            screen = Screen.TITLE;
        } catch (RuntimeException exception) {
            errorMessage = exception.getMessage();
        }
    }

    private GraphicsPreferences graphicsPreferencesFromFields() {
        return new GraphicsPreferences(
                GraphicsPreset.values()[Math.clamp(settingGraphicsPreset.get(), 0, GraphicsPreset.values().length - 1)],
                AntiAliasingMode.values()[Math.clamp(settingAntiAliasing.get(), 0, AntiAliasingMode.values().length - 1)],
                settingClouds.get(), settingWaterWaves.get(), settingLighting.get(), settingBlockLighting.get(),
                settingToneMapping.get(), settingAutoExposure.get(), settingExposure[0], settingContrast[0],
                settingSaturation[0], settingGamma[0]
        );
    }

    private void applyGraphicsPresetToFields(GraphicsPreset preset) {
        syncGraphicsFields(GraphicsPreferences.forPreset(preset, graphicsPreferencesFromFields()));
    }

    private void syncGraphicsFields(GraphicsPreferences graphics) {
        settingGraphicsPreset.set(graphics.preset().ordinal());
        settingAntiAliasing.set(graphics.antiAliasing().ordinal());
        settingClouds.set(graphics.cloudsEnabled());
        settingWaterWaves.set(graphics.waterWavesEnabled());
        settingLighting.set(graphics.lightingEnabled());
        settingBlockLighting.set(graphics.blockLightingEnabled());
        settingToneMapping.set(graphics.toneMappingEnabled());
        settingAutoExposure.set(graphics.autoExposureEnabled());
        settingExposure[0] = graphics.exposure();
        settingContrast[0] = graphics.contrast();
        settingSaturation[0] = graphics.saturation();
        settingGamma[0] = graphics.gamma();
    }

    private void syncFieldsFromSettings() {
        syncResolutionFromSettings();
        settingFullscreen.set(settings.fullscreen());
        settingVsync.set(settings.vsync());
        settingFrameRateLimit[0] = settings.frameRateLimit();
        settingUiScale[0] = settings.uiScale();
        settingFov[0] = settings.fieldOfView();
        settingRenderDistance[0] = settings.renderDistanceChunks();
        settingSensitivity[0] = settings.mouseSensitivity();
        settingLanguage.set("en".equals(settings.locale()) ? 1 : 0);
        syncGraphicsFields(settings.graphicsPreferences());
        editedBindings.clear();
        editedBindings.putAll(settings.bindings());
        bindingCaptureAction = null;
        networkViewDistance[0] = settings.defaultViewDistance();
        hostPort[0] = settings.defaultPort();
        hostMaxPlayers[0] = settings.defaultMaxPlayers();
        if (serverAddress.get().isBlank()) {
            serverAddress.set("127.0.0.1:" + settings.defaultPort());
        }
    }

    private void refreshDisplayResolutions() {
        if (window == null) {
            return;
        }
        DisplayResolution nativeResolution = window.nativeDisplayResolution();
        displayResolutions = DisplayResolutionCatalog.standardsFor(
                nativeResolution.width(),
                nativeResolution.height()
        );
        displayResolutionLabels = displayResolutions.stream()
                .map(DisplayResolution::label)
                .toArray(String[]::new);
    }

    private void syncResolutionFromSettings() {
        settingResolution.set(DisplayResolutionCatalog.nearestIndex(
                displayResolutions,
                settings.windowWidth(),
                settings.windowHeight()
        ));
    }

    private DisplayResolution selectedDisplayResolution() {
        int index = Math.clamp(settingResolution.get(), 0, displayResolutions.size() - 1);
        return displayResolutions.get(index);
    }

    private void captureBindingInput() {
        if (bindingCaptureAction == null || ImGui.getFrameCount() <= bindingCaptureFrame + 1) {
            return;
        }
        for (int key = org.lwjgl.glfw.GLFW.GLFW_KEY_SPACE; key <= org.lwjgl.glfw.GLFW.GLFW_KEY_LAST; key++) {
            if (org.lwjgl.glfw.GLFW.glfwGetKey(window.getId(), key) == org.lwjgl.glfw.GLFW.GLFW_PRESS) {
                editedBindings.put(
                        bindingCaptureAction.getId(),
                        new ClientSettings.BindingSetting("key", key)
                );
                bindingCaptureAction = null;
                return;
            }
        }
        for (int button = 0; button <= org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LAST; button++) {
            if (org.lwjgl.glfw.GLFW.glfwGetMouseButton(window.getId(), button)
                    == org.lwjgl.glfw.GLFW.GLFW_PRESS) {
                editedBindings.put(
                        bindingCaptureAction.getId(),
                        new ClientSettings.BindingSetting("mouse", button)
                );
                bindingCaptureAction = null;
                return;
            }
        }
    }

    private ClientSettings.BindingSetting effectiveBinding(InputAction action) {
        ClientSettings.BindingSetting custom = editedBindings.get(action.getId());
        if (custom != null) {
            return custom;
        }
        InputBinding defaults = action.getDefaultBinding();
        String type = defaults.type() == org.weaw.engine.input.InputBindingType.MOUSE_BUTTON ? "mouse" : "key";
        return new ClientSettings.BindingSetting(type, defaults.code());
    }

    private boolean hasBindingConflict(InputAction action) {
        ClientSettings.BindingSetting binding = effectiveBinding(action);
        for (InputAction candidate : InputAction.values()) {
            if (candidate != action && binding.equals(effectiveBinding(candidate))) {
                return true;
            }
        }
        return false;
    }

    private void initializeProfiles() {
        String requestedKey = baseOptions.storage().profileKey();
        String requestedName = baseOptions.storage().requestedPlayerName();
        boolean commandLineProfile = !"default".equals(requestedKey) || requestedName != null;
        String preferredKey = commandLineProfile ? requestedKey : settings.activeProfileKey();

        PlayerProfile profile = profileRepository.find(preferredKey).orElse(null);
        if (profile == null) {
            String initialName = requestedName == null ? settings.playerName() : requestedName;
            profile = profileRepository.openOrCreate(preferredKey, initialName);
        } else if (requestedName != null && !requestedName.isBlank()
                && !requestedName.trim().equals(profile.displayName())) {
            profile = profileRepository.rename(profile.key(), requestedName);
        }
        refreshProfiles(profile.key());
        settings = settings.withActiveProfile(profile);
    }

    private void refreshProfiles(String selectedKey) {
        profiles = profileRepository.listProfiles();
        if (profiles.isEmpty()) {
            PlayerProfile created = profileRepository.openOrCreate("default", settings.playerName());
            profiles = List.of(created);
        }
        profileLabels = profiles.stream()
                .map(profile -> profile.displayName() + "  [" + profile.id().toString().substring(0, 8) + "]")
                .toArray(String[]::new);
        int index = 0;
        for (int candidate = 0; candidate < profiles.size(); candidate++) {
            if (profiles.get(candidate).key().equals(selectedKey)) {
                index = candidate;
                break;
            }
        }
        selectedProfile.set(index);
        profileDisplayName.set(profiles.get(index).displayName());
    }

    private PlayerProfile activeProfile() {
        if (profiles.isEmpty()) {
            throw new IllegalStateException("No player profile is available");
        }
        int index = Math.clamp(selectedProfile.get(), 0, profiles.size() - 1);
        return profiles.get(index);
    }

    private void activateSelectedProfile() {
        profileDisplayName.set(activeProfile().displayName());
        try {
            persistActiveProfile();
        } catch (RuntimeException exception) {
            errorMessage = messageFor(exception);
        }
    }

    private void persistActiveProfile() {
        settings = settings.withActiveProfile(activeProfile());
        settingsRepository.save(settings);
    }

    private String messageFor(Throwable throwable) {
        Throwable current = throwable;
        Throwable best = throwable;
        while (current != null) {
            if (current.getMessage() != null && !current.getMessage().isBlank()) {
                best = current;
            }
            Throwable cause = current.getCause();
            if (cause == current) {
                break;
            }
            current = cause;
        }
        String message = best.getMessage();
        if (message != null && message.contains("Player profile is already connected")) {
            return text.get("error.profileAlreadyConnected");
        }
        return message == null || message.isBlank() ? best.getClass().getSimpleName() : message;
    }

    private static String bindingLabel(ClientSettings.BindingSetting binding) {
        if ("mouse".equalsIgnoreCase(binding.type())) {
            return "Souris " + (binding.code() + 1);
        }
        return switch (binding.code()) {
            case org.lwjgl.glfw.GLFW.GLFW_KEY_SPACE -> "Espace";
            case org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE -> "Échap";
            case org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_SHIFT -> "Maj gauche";
            case org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_CONTROL -> "Ctrl gauche";
            case org.lwjgl.glfw.GLFW.GLFW_KEY_F1 -> "F1";
            case org.lwjgl.glfw.GLFW.GLFW_KEY_F3 -> "F3";
            default -> {
                String name = org.lwjgl.glfw.GLFW.glfwGetKeyName(binding.code(), 0);
                yield name == null ? "Touche " + binding.code() : name.toUpperCase(Locale.ROOT);
            }
        };
    }

    private void resetWorldForm() {
        worldName.set("Nouveau monde");
        worldSeed.set("");
        preset.set(0);
        minChunkY[0] = -4;
        maxChunkY[0] = 3;
        simulationDistance[0] = settings.defaultViewDistance();
        renderDistance[0] = settings.renderDistanceChunks();
        autosaveSeconds[0] = StorageOptions.DEFAULT_AUTOSAVE_SECONDS;
        dayLengthMinutes[0] = WorldTimeState.DEFAULT_DAY_LENGTH_SECONDS / 60;
        applyPreset(0);
    }

    private void applyPreset(int value) {
        switch (value) {
            case 1 -> {
                amplitude[0] = 8.0f;
                baseHeight[0] = 0;
                waterLevel[0] = -6;
                terrainFrequency[0] = 0.2f;
                terrainOctaves[0] = 3;
                terrainGain[0] = 0.42f;
                treeRarity[0] = 0.7f;
            }
            case 2 -> {
                amplitude[0] = 48.0f;
                baseHeight[0] = 4;
                waterLevel[0] = -12;
                terrainFrequency[0] = 0.24f;
                terrainOctaves[0] = 5;
                terrainGain[0] = 0.55f;
                treeRarity[0] = 0.8f;
            }
            default -> {
                GenerationConfig defaults = GenerationConfig.defaults();
                amplitude[0] = defaults.amplitude();
                baseHeight[0] = defaults.baseHeight();
                waterLevel[0] = defaults.waterLevel();
                terrainFrequency[0] = defaults.terrainFrequency();
                terrainOctaves[0] = defaults.terrainOctaves();
                terrainLacunarity[0] = defaults.terrainLacunarity();
                terrainGain[0] = defaults.terrainGain();
                treeRarity[0] = defaults.treeRarity();
                treeSteepness[0] = defaults.treeSteepness();
            }
        }
    }

    private String validateWorldForm() {
        if (worldName.get().isBlank() || worldName.get().trim().length() > 128) {
            return "Le nom doit contenir 1 à 128 caractères.";
        }
        if (!worldSeed.get().isBlank()) {
            try {
                Long.parseLong(worldSeed.get().trim());
            } catch (NumberFormatException exception) {
                return "La seed doit être un entier signé sur 64 bits.";
            }
        }
        if (minChunkY[0] > maxChunkY[0]) {
            return "La hauteur minimale doit précéder la hauteur maximale.";
        }
        int minContent = (int) Math.floor(baseHeight[0] - Math.abs(amplitude[0]));
        int maxContent = (int) Math.ceil(baseHeight[0] + Math.abs(amplitude[0])) + 6;
        if (minContent < minChunkY[0] * 16 || maxContent >= (maxChunkY[0] + 1) * 16) {
            return "Les limites verticales ne contiennent pas tout le relief et les arbres.";
        }
        return null;
    }

    private void refreshWorlds() {
        try {
            worlds = worldRepository.listWorlds();
            if (worlds.isEmpty()) {
                selectedWorld = -1;
            } else {
                selectedWorld = Math.clamp(selectedWorld < 0 ? 0 : selectedWorld, 0, worlds.size() - 1);
            }
        } catch (RuntimeException exception) {
            worlds = List.of();
            selectedWorld = -1;
            errorMessage = exception.getMessage();
        }
    }

    private WorldSummary selectedWorld() {
        return selectedWorld >= 0 && selectedWorld < worlds.size() ? worlds.get(selectedWorld) : null;
    }

    private String worldDetails(WorldSummary world) {
        if (!world.playable()) {
            return world.problem() == null ? text.get("worlds.invalid") : world.problem();
        }
        String date = DATE_FORMAT.format(
                Instant.ofEpochMilli(world.lastOpenedAtEpochMillis()).atZone(ZoneId.systemDefault())
        );
        return date + " · " + text.get("worlds.seed") + " " + world.seed()
                + " · " + text.get("worlds.version") + " " + world.formatVersion();
    }

    private void beginCentered(String id, float desiredWidth, float desiredHeight) {
        ResponsiveUiLayout layout = ResponsiveUiLayout.fit(
                window.getLogicalWidth(), window.getLogicalHeight(),
                desiredWidth, desiredHeight,
                screen == Screen.SETTINGS ? settingUiScale[0] : settings.uiScale(),
                16.0f
        );
        activeUiScale = layout.scale();
        ImGui.getStyle().setFontScaleMain(activeUiScale);
        if (responsiveStylePushed) {
            throw new IllegalStateException("Responsive ImGui style pushed more than once in a frame");
        }
        responsiveStyle.pushScaled(activeUiScale);
        responsiveStylePushed = true;
        ImGui.setNextWindowPos(layout.x(), layout.y(), ImGuiCond.Always);
        ImGui.setNextWindowSize(layout.width(), layout.height(), ImGuiCond.Always);
        ImGui.setNextWindowBgAlpha(0.91f);
        ImGui.begin(id, ROOT_FLAGS);
    }

    private void heading(String value) {
        ImGui.setWindowFontScale(1.55f);
        centeredText(value);
        ImGui.setWindowFontScale(1.0f);
        ImGui.separator();
        ImGui.dummy(1, 8);
    }

    private void centeredText(String value) {
        ImGui.setCursorPosX(Math.max(0, (ImGui.getWindowWidth() - ImGui.calcTextSizeX(value)) * 0.5f));
        ImGui.text(value);
    }

    private boolean wideButton(String label) {
        return ImGui.button(label, -1, ui(48));
    }

    private float ui(float value) {
        return value * activeUiScale;
    }

    private static void applyTheme() {
        ImGuiStyle style = ImGui.getStyle();
        style.setWindowRounding(2.0f);
        style.setFrameRounding(1.0f);
        style.setGrabRounding(1.0f);
        style.setWindowPadding(18.0f, 18.0f);
        style.setFramePadding(12.0f, 9.0f);
        style.setItemSpacing(10.0f, 10.0f);
        style.setColor(ImGuiCol.WindowBg, 0.08f, 0.09f, 0.11f, 0.95f);
        style.setColor(ImGuiCol.PopupBg, 0.08f, 0.09f, 0.11f, 0.98f);
        style.setColor(ImGuiCol.FrameBg, 0.19f, 0.20f, 0.20f, 1.0f);
        style.setColor(ImGuiCol.FrameBgHovered, 0.31f, 0.33f, 0.31f, 1.0f);
        style.setColor(ImGuiCol.FrameBgActive, 0.24f, 0.42f, 0.20f, 1.0f);
        style.setColor(ImGuiCol.Button, 0.28f, 0.29f, 0.27f, 1.0f);
        style.setColor(ImGuiCol.ButtonHovered, 0.38f, 0.50f, 0.28f, 1.0f);
        style.setColor(ImGuiCol.ButtonActive, 0.22f, 0.36f, 0.18f, 1.0f);
        style.setColor(ImGuiCol.Header, 0.28f, 0.42f, 0.22f, 1.0f);
        style.setColor(ImGuiCol.HeaderHovered, 0.38f, 0.54f, 0.29f, 1.0f);
        style.setColor(ImGuiCol.CheckMark, 0.63f, 0.84f, 0.38f, 1.0f);
        style.setColor(ImGuiCol.SliderGrab, 0.58f, 0.77f, 0.34f, 1.0f);
        style.setColor(ImGuiCol.Border, 0.68f, 0.70f, 0.65f, 0.72f);
    }

    @Override
    public void close() {
        if (imGuiGl3 != null) {
            imGuiGl3.shutdown();
            imGuiGl3 = null;
        }
        if (imGuiGlfw != null) {
            imGuiGlfw.shutdown();
            imGuiGlfw = null;
        }
        if (ImGui.getCurrentContext().isValidPtr()) {
            ImGui.destroyContext();
        }
        if (installedFont != null) {
            installedFont.close();
            installedFont = null;
        }
        if (loadingGame != null) {
            loadingGame.cancelPreparation();
        }
        loadingFuture = null;
        loadingGame = null;
        window = null;
    }

    private enum Screen {
        TITLE,
        WORLDS,
        CREATE_WORLD,
        MULTIPLAYER,
        HOST,
        SETTINGS,
        LOADING
    }

    public record FrontendResult(boolean quit, Game game) {
        public static FrontendResult quitting() {
            return new FrontendResult(true, null);
        }

        public static FrontendResult launch(Game game) {
            return new FrontendResult(false, game);
        }
    }
}

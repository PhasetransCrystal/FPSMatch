package net.ptcrys.fpsmatch.editor.common.client.workbench;

import net.ptcrys.fpsmatch.core.RuntimeMapLeaseException;
import net.ptcrys.fpsmatch.editor.api.*;
import net.ptcrys.fpsmatch.editor.common.ui.component.*;
import net.ptcrys.fpsmatch.editor.common.ui.component.StudioComponent.*;
import net.ptcrys.fpsmatch.editor.common.ui.control.ControlTree;
import net.ptcrys.fpsmatch.editor.common.ui.control.ControlTree.Role;
import net.ptcrys.fpsmatch.editor.common.ui.layout.*;
import net.ptcrys.fpsmatch.editor.common.ui.layout.FlowLayout.Insets;
import net.ptcrys.fpsmatch.editor.common.ui.layout.FlowLayout.Length;
import net.ptcrys.fpsmatch.editor.common.ui.layout.FlowLayout.Style;
import net.ptcrys.fpsmatch.editor.common.ui.model.*;
import net.ptcrys.fpsmatch.editor.common.ui.persistence.ProjectWorldStore;
import net.ptcrys.fpsmatch.editor.common.ui.persistence.WorkspaceLayoutSession;
import net.ptcrys.fpsmatch.editor.common.ui.workbench.InspectorPresentation;
import net.ptcrys.fpsmatch.editor.common.ui.workbench.ObjectHierarchy;
import net.ptcrys.fpsmatch.editor.common.ui.workbench.ProjectWorkbench;
import net.ptcrys.fpsmatch.editor.common.ui.workbench.ProjectWorldWorkbench;
import net.ptcrys.fpsmatch.editor.common.ui.workbench.RuntimeInstallationProfile;
import net.ptcrys.fpsmatch.editor.common.ui.workbench.RuntimeInstallationWorkbench;
import net.ptcrys.fpsmatch.editor.common.ui.workbench.RuntimeTestWorkbench;
import net.ptcrys.fpsmatch.editor.common.ui.workbench.StudioDocumentTabs;
import net.ptcrys.fpsmatch.editor.common.ui.workbench.StudioFormDocument;
import net.ptcrys.fpsmatch.editor.common.ui.workbench.StudioPanelViewState;
import net.ptcrys.fpsmatch.editor.common.ui.workbench.WorkbenchContentProjection;
import net.ptcrys.fpsmatch.editor.common.ui.workbench.WorkbenchInspectorProjection;
import net.ptcrys.fpsmatch.editor.common.ui.workbench.WorkbenchListProjection;
import net.ptcrys.fpsmatch.editor.common.ui.workbench.WorldTargetWorkbench;
import net.ptcrys.fpsmatch.editor.core.editing.*;
import net.ptcrys.fpsmatch.editor.core.installation.InstallationPlan;
import net.ptcrys.fpsmatch.editor.core.session.DocumentHandle;
import net.ptcrys.fpsmatch.editor.core.session.SessionRejection;
import net.ptcrys.fpsmatch.editor.core.types.DocumentCodecException;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.storage.LevelResource;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** One native window hosts the editor's own semantic control tree. */
public final class WorkbenchScreen extends Screen {

    private enum Page {
        EDITOR,
        COMMANDS,
        ALL_MENU,
        PROJECT_MENU,
        EDIT_MENU,
        ADD_MENU,
        VIEW_MENU,
        WINDOW_MENU,
        TOOLS_MENU,
        HELP_MENU,
        PROJECT,
        PROJECT_WORLD,
        NEW_DOCUMENT,
        IMPORT,
        IMPORT_REPORT,
        RESOURCES,
        RESOURCE_IMPORT,
        RESOURCE_REPORT,
        WORLD_TARGET,
        WORLD_REGISTER,
        INSTALL,
        INSTALL_RECORDS,
        TEST,
        CLOSE,
        DOCUMENT_CLOSE,
        PROBLEMS,
        DOCUMENTS,
        DOCUMENT_FORMS,
        WORKSPACES,
        WORKSPACE_NEW,
        OBJECT_CREATE,
        OBJECT_MENU,
        OBJECT_INFO,
        FIELD_CHOICES,
        REFERENCES,
        OBJECT_REMOVE,
        PANEL_MENU,
        PANEL_ADD,
        CREATION_SWITCH
    }

    private static final float DENSITY = .75f;

    // The approved Studio prototype uses 13px body text. BODY=9 defines the same design
    // grid at 2/3 scale; the host applies DENSITY and the user's GUI scale together.
    private static int studioUnits(int pixels) {
        return Math.round(pixels * 2f / 3f);
    }

    private static final int MENU_BOTTOM = studioUnits(44), WORKSPACE_BOTTOM = MENU_BOTTOM, DOCUMENT_BOTTOM = MENU_BOTTOM + studioUnits(35), BODY_TOP = MENU_BOTTOM, STATUS_HEIGHT = studioUnits(27), TOOL_RAIL = studioUnits(35);
    private WorkbenchWorldViewport viewport;
    private final WorkbenchPointPlacement pointPlacement;
    private final WorkbenchAreaPlacement areaPlacement;
    private final WorkbenchPointOverlay pointOverlay;
    private boolean pointOverlayFailureReported;
    private boolean uiBuildFailed;
    private final WorkbenchWorldSession worldSession;
    private final ProjectWorldWorkbench projectWorld;
    private final WorkbenchPathPicker pathPicker;
    private ProjectWorldStore.World selectedProjectWorld;
    private boolean projectWorldAcknowledged, projectWorldDraftSelection;
    private Page projectWorldReturn = Page.PROJECT;
    private long projectDraftGeneration, observedProjectWorldVersion = -1;
    private String pendingBoundWorld, pickerFocus, pickerField;
    private int boundEntryPhase;
    private long boundEntryGeneration;
    private ProjectWorldStore.Binding boundEntryBinding;
    private UUID observedWorldProject;
    private Path observedWorldProjectDirectory;
    private ProjectWorldWorkbench.State observedWorldPromptState;
    private Component pickerMessage, projectWorldMessage;
    private ControlTree.Bounds nativeWorldBounds;
    private int worldListScroll, creationScroll;
    private ControlTree.Bounds viewportBounds;
    private final Map<UUID, UUID> dockTabs = new HashMap<>();
    private final Set<UUID> hydratedPanels = new HashSet<>();
    private final Set<UUID> invalidPanelViews = new HashSet<>();
    private final Map<UUID, JsonObject> panelViewExtras = new HashMap<>();
    private static final Gson VIEW_JSON = new Gson();
    private final Map<UUID, Integer> objectScrollByPanel = new HashMap<>(), fieldScrollByPanel = new HashMap<>();
    private final Map<UUID, String> displayedPanelContexts = new HashMap<>();
    private final Map<String, Integer> objectContextScroll = new HashMap<>(), fieldContextScroll = new HashMap<>();
    private final Map<UUID, ControlTree.Bounds> panelBounds = new LinkedHashMap<>();
    private final Map<UUID, EditorId> panelTypes = new HashMap<>();
    private final Map<UUID, String> resourceSearch = new HashMap<>(), resourceTabs = new HashMap<>();
    private final Map<String, String> resourceVersions = new HashMap<>();
    private final Map<UUID, UUID> selectedResources = new HashMap<>();
    private final Map<UUID, Integer> resourceScroll = new HashMap<>(), resourceDetailScroll = new HashMap<>();
    private final Map<UUID, String> selectedTasks = new HashMap<>();
    private final Map<UUID, Integer> taskScroll = new HashMap<>();
    private final Map<UUID, Integer> projectScroll = new HashMap<>();
    private final Map<UUID, String> projectSearch = new HashMap<>();
    private ControlTree.Bounds documentBarBounds;
    private final UUID centralResources = UUID.nameUUIDFromBytes("studio/resource-document".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    private boolean resourceDocument;
    private final Map<StudioFormDocument.Key, StudioFormDocument> formDocuments = new LinkedHashMap<>();
    private StudioFormDocument.Key activeFormDocument;
    private UUID formMenuDocument;
    private ProjectWorkbench.Address revealFormField, focusFormField;
    private String pendingFormFocus;
    private final Map<String, ProjectWorkbench.Address> formFieldControls = new HashMap<>();
    private final Map<UUID, Integer> gridScroll = new HashMap<>(), gridColumn = new HashMap<>();
    private final Map<UUID, String> gridSearch = new HashMap<>();
    private ControlTree.Bounds gridBounds;
    private final Map<UUID, String> lastGridOwners = new HashMap<>();
    private final StudioDocumentTabs documentTabs = new StudioDocumentTabs();
    private final Map<UUID, DocumentHandle<?>> knownDocumentHandles = new HashMap<>();
    private final Map<String, StudioDocumentTabs.Key> documentTabControls = new HashMap<>();
    private StudioDocumentTabs.Key documentDropTarget;
    private boolean documentViewsInitialized;
    private StudioDocumentTabs.CloseRequest documentClose;
    private DocumentHandle<?> documentCloseHandle;
    private ProjectWorkbench.DocumentSaveRequest documentCloseSave;
    private ProjectWorkbench.DocumentSaveState documentCloseObservedState;
    private Component documentCloseError;
    private final Map<String, Set<String>> gridSelections = new HashMap<>();
    private final Set<String> gridGroupModes = new HashSet<>();
    private final Map<String, String> gridGroupRaw = new HashMap<>();
    private final Map<String, String> gridSwapSources = new HashMap<>();
    private final Map<UUID, Integer> fieldRowOffset = new HashMap<>(), fieldRowOverflow = new HashMap<>();
    private UUID pointerPanel;
    private UUID menuPanel;
    private Runnable creationContinuation;

    private record DockGrip(UUID workspace, UUID panel, String side, ControlTree.Bounds bounds) {}

    private final List<DockGrip> dockGrips = new ArrayList<>();
    private final StudioDocking<UUID> panelDocking = new StudioDocking<>();
    private final List<StudioDocking.Source<UUID>> dockSources = new ArrayList<>();
    private final List<StudioDocking.Region> dockRegions = new ArrayList<>();
    private DockGrip draggingDock;
    private String themeName = MinecraftWorkbenchPreferences.theme();
    private final ProjectWorkbench model;
    private final WorkbenchListProjection listProjection;
    private final WorkbenchInspectorProjection inspectorProjection;
    private final WorkbenchContentProjection contentProjection;
    private final Set<UUID> buildingContentDocuments = new HashSet<>();
    private final StudioContentSlots<InspectorContent> inspectorContents = new StudioContentSlots<>(64);
    private StudioContentSlots<InspectorContent>.Publication inspectorPublication;
    private long contentEpoch;

    private record InspectorContent(String id, List<StudioView.Element> elements) {

        InspectorContent {
            elements = List.copyOf(elements);
        }
    }

    private final Set<String> buildingInspectorViews = new HashSet<>();
    private long reconciledDocumentVersion = Long.MIN_VALUE;

    private record ListState(StudioRowSource<?> source, StudioVirtualList.Window window, int offset, String query) {}

    private record ObjectListContext(UUID panel, UUID document, StudioRowSource<ObjectHierarchy.Row> source) {}

    private record TreeFocusOrigin(ObjectListContext context, StudioFocusNavigation.FocusRef focus, UUID activeDocument) {}

    private Map<String, ListState> listStates = Map.of();
    private Map<String, ObjectListContext> objectLists = Map.of();
    private final Map<String, ListState> buildingListStates = new HashMap<>();
    private final Set<String> buildingListViews = new HashSet<>();
    private final Map<String, ObjectListContext> buildingObjectLists = new HashMap<>();
    private final WorldTargetWorkbench worldTarget;
    private final RuntimeInstallationWorkbench installations;
    private final RuntimeTestWorkbench tests;
    private long observedTestVersion = -1;
    private Component testActionError;
    private long testActionErrorVersion = -1;
    private RuntimeTestWorkbench.Input rejectedTestInput;
    private boolean testCancellationRequested;
    private long observedInstallVersion = -1;
    private EditorId installationProfile;
    private boolean installationDraft = true;
    private Component installationActionError;
    private long installationActionErrorVersion = -1;
    private Page installationActionErrorPage;
    private boolean installationActionErrorDraft;
    private UUID selectedInstallationRecord;
    private RuntimeInstallationWorkbench.RecordView explicitInstallationRecord, replacementDraft;
    private EditorId targetDimension;
    private Component worldMappingError;
    private WorldTargetWorkbench.RegistrationPrompt worldRegistrationPrompt;
    private long observedWorldVersion = -1;
    private int drawer = -1;
    private int drawerBeforeOverlay = -1;
    private ControlTree.Bounds drawerBounds;
    private final Map<String, String> form = new HashMap<>();
    private EditorId projectCategory, projectTemplate;
    private final Map<EditorId, Map<String, String>> templateDrafts = new HashMap<>();
    private Component projectCreationError;
    private final Map<ProjectWorkbench.Address, Component> fieldErrors = new HashMap<>();
    private final Map<String, Set<String>> collapsedObjects = new HashMap<>();
    private final Map<String, String> objectSearch = new HashMap<>();
    private final Map<String, String> objectControls = new HashMap<>();
    private final Map<String, UUID> objectDocuments = new HashMap<>(), objectPanels = new HashMap<>();
    private final Map<UUID, PanelInstance> panelInstances = new HashMap<>();
    private final Map<String, String> creationRaw = new LinkedHashMap<>();
    private ProjectWorkbench.CreationDraft objectCreation;
    private ProjectWorkbench.Address creationAttachTo;
    private Component creationError;
    private UUID creationPanelTarget;
    private final Map<String, String> inspectorPages = new HashMap<>();
    private final Map<UUID, String> inspectorSearch = new HashMap<>();
    private final Map<String, Set<String>> inspectorCollapsed = new HashMap<>();

    private record InspectorItem(InspectorPresentation.Group group, InspectorPresentation.Row row, DocumentEditor.ReferenceList reference,
                                 String target, StudioDocument.Section section, ProjectWorkbench.CreationChoice creator, StudioComponents.SectionIntro intro) {

        InspectorItem(InspectorPresentation.Group group, InspectorPresentation.Row row, DocumentEditor.ReferenceList reference, String target, StudioDocument.Section section, ProjectWorkbench.CreationChoice creator) {
            this(group, row, reference, target, section, creator, null);
        }

        InspectorItem(InspectorPresentation.Group group, InspectorPresentation.Row row, DocumentEditor.ReferenceList reference) {
            this(group, row, reference, null, null, null);
        }

        InspectorItem(InspectorPresentation.Group group, InspectorPresentation.Row row, DocumentEditor.ReferenceList reference, String target, StudioDocument.Section section) {
            this(group, row, reference, target, section, null);
        }
    }

    private ProjectWorkbench.Address revealField;
    private ProjectWorkbench.Address choiceAddress;
    private StudioFormDocument.Key choiceDocumentForm;
    private DocumentEditor.Field choiceField;
    private String choiceCreationProperty, choiceFormFocus;
    private int choiceFormScroll;
    private boolean restoreChoiceFocus;
    private String choiceSearch = "";
    private int choiceScroll;
    private ControlTree.Bounds choiceAnchor;
    private ProjectWorkbench.ReferenceDraft referenceDraft;
    private ProjectWorkbench.RemovalDraft removalDraft;
    private final List<String> referenceSelection = new ArrayList<>();
    private String referenceSearch = "";
    private int referenceScroll;
    private boolean referenceAvailable;
    private final Set<String> inlineUsageExpanded = new HashSet<>();
    private final Map<String, String> revealObjects = new HashMap<>(), consumedObjectReveals = new HashMap<>();
    private Component creationLabel;
    private String addSearch = "", addCategory = "", commandSearch = "";

    private record StudioCommand(String key, Runnable action, boolean enabled) {}

    private EditorId selectedCreator;
    private UUID objectMenuDocument, catalogDocument;
    private String objectMenuNode, catalogNode;
    private int addScroll;
    private int addCategoryScroll;
    private final WorkspaceManager workspaces;
    private final WorkspaceLayoutSession layouts;
    private final Map<EditorId, WorkspaceTemplate> templates = new LinkedHashMap<>();
    private final List<WorkspaceEdit> workspaceEdits = new ArrayList<>();
    private final Map<UUID, Integer> compactPanels = new HashMap<>();
    private UUID drawerPanelIdentity;
    private WorkbenchSurface ui;
    private Page page = Page.EDITOR;
    private boolean creating, openingProject, rebuildRequested = true, tasks;
    private EditorId documentType, importer, resourceImporter;
    private int objectScroll, fieldScroll, problemScroll, documentScroll, documentListScroll, workspaceScroll, selectedWorkspaceEdit;
    private int dialogScroll, dialogMaxScroll;
    private int menuAnchor;
    private int menuY = 25;
    private boolean restoreFocus, hadProject, focusMenuOnPublish;
    private boolean acceptingResource, inspectingResource, resourceTaskKnown, resourceTaskFinished;
    private boolean inspectingImport, importTaskFinished, importFailureDetails;
    private UUID importedDocument;
    private Component importTaskSource, importTaskStatus;
    private List<Problem> importResultProblems = List.of();
    private Component resourceTaskStatus;
    private Component resourceTaskSource;
    private List<Component> resourceTaskSummary = List.of();
    private int worldStep;
    private long observedVersion = -1;
    private UUID dragWorkspace;
    private UUID observedDocument;
    private double dragX;
    private String focusBeforeOverlay, confirmationFocus;
    private int layer, dx, dy, dw, dh;
    private List<StudioComponents.Action> collectingFooter;

    private static final class WorkspaceEdit {

        final UUID draftId = UUID.randomUUID();
        UUID existing, copyOf;
        EditorId template;
        String name;
        boolean visible;

        WorkspaceEdit(UUID existing, UUID copyOf, EditorId template, String name, boolean visible) {
            this.existing = existing;
            this.copyOf = copyOf;
            this.template = template;
            this.name = name;
            this.visible = visible;
        }
    }

    WorkbenchScreen(ProjectWorkbench model, WorldTargetWorkbench worldTarget, RuntimeInstallationWorkbench installations, RuntimeTestWorkbench tests, ProjectWorldWorkbench projectWorld, java.util.concurrent.Executor layoutIO) {
        super(c("open"));
        worldSession = new WorkbenchWorldSession(this, this::request);
        this.model = model;
        this.worldTarget = worldTarget;
        this.installations = installations;
        listProjection = new WorkbenchListProjection(model, text -> component(text).getString());
        inspectorProjection = new WorkbenchInspectorProjection(model, listProjection);
        contentProjection = new WorkbenchContentProjection(model, listProjection);
        this.tests = tests;
        this.projectWorld = Objects.requireNonNull(projectWorld);
        pointPlacement = new WorkbenchPointPlacement(model, projectWorld, worldTarget);
        areaPlacement = new WorkbenchAreaPlacement(model, projectWorld, worldTarget);
        pointOverlay = new WorkbenchPointOverlay(model, projectWorld, worldTarget);
        for (var template : model.workspaceTemplates()) templates.put(template.id(), template);
        workspaces = new WorkspaceManager(templates.values());
        var client = net.minecraft.client.Minecraft.getInstance();
        pathPicker = new WorkbenchPathPicker(client::execute);
        worldSession.entryPolicy(id -> projectWorld.entryAllowed(client.getLevelSource().getBaseDir(), id));
        layouts = new WorkspaceLayoutSession(workspaces, client.gameDirectory.toPath().resolve("config/fpsmatch-editor-layouts"), layoutIO, client::execute,
                this::clearLayoutViewCaches, this::request, failure -> com.mojang.logging.LogUtils.getLogger().warn("Cannot persist editor workspace layout", failure));
        syncLayoutScope();
    }

    private WorkspaceState defaultWorkspaceState() {
        var defaults = new WorkspaceManager(templates.values());
        if (!templates.isEmpty()) {
            var template = templates.values().iterator().next();
            defaults.create(template.id(), component(template.label()).getString());
        }
        return defaults.snapshot();
    }

    private void syncLayoutScope() {
        UUID project = model.projectId().orElse(null);
        Path directory = project == null ? null : model.directory().orElseThrow();
        if (!layouts.matches(directory, project)) {
            persistPanelViews();
            layouts.select(directory, project, defaultWorkspaceState());
        }
    }

    private void clearLayoutViewCaches() {
        cancelWorldInput();
        listStates = Map.of();
        objectLists = Map.of();
        revealObjects.clear();
        inspectorContents.clear();
        contentProjection.clear();
        inspectorProjection.clear();
        listProjection.clear();
        reconciledDocumentVersion = Long.MIN_VALUE;
        if (ui != null) {
            ui.cancelPointer();
            ui.cancelNavigation();
        }
        cancelPanelDocking();
        cancelDocumentTabDrag();
        dragWorkspace = null;
        draggingDock = null;
        hydratedPanels.clear();
        invalidPanelViews.clear();
        panelViewExtras.clear();
        dockTabs.clear();
        compactPanels.clear();
        displayedPanelContexts.clear();
        objectScrollByPanel.clear();
        fieldScrollByPanel.clear();
        objectContextScroll.clear();
        fieldContextScroll.clear();
        fieldRowOffset.clear();
        fieldRowOverflow.clear();
        resourceSearch.clear();
        resourceTabs.clear();
        resourceVersions.clear();
        selectedResources.clear();
        resourceScroll.clear();
        resourceDetailScroll.clear();
        selectedTasks.clear();
        taskScroll.clear();
        projectScroll.clear();
        projectSearch.clear();
        objectSearch.clear();
        collapsedObjects.clear();
        inspectorPages.clear();
        inspectorSearch.clear();
        inspectorCollapsed.clear();
        drawer = -1;
        drawerPanelIdentity = null;
        pointerPanel = null;
        menuPanel = null;
        workspaceEdits.clear();
        request();
    }

    void tickLayouts() {
        layouts.tick();
    }

    java.util.concurrent.CompletableFuture<Void> flushLayoutsOnShutdown() {
        return layouts.flushOnShutdown();
    }

    private Optional<Component> layoutProblem() {
        return layouts.problem().map(WorkbenchScreen::c).or(() -> invalidPanelViews.isEmpty() ? Optional.empty() : Optional.of(c("layout_view_invalid")));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void init() {
        cancelPanelDocking();
        cancelDocumentTabDrag();
        dragWorkspace = null;
        draggingDock = null;
        if (ui != null) {
            ui.cancelPointer();
            ui.reflow();
        }
        width = (int) Math.ceil(minecraft.getWindow().getGuiScaledWidth() / DENSITY);
        height = (int) Math.ceil(minecraft.getWindow().getGuiScaledHeight() / DENSITY);
        if (worldSession.nativeRendering()) return;
        // Association validation is maintained by WorkbenchClient, independently of screen layout.
        cancelWorldInput();
        worldSession.attach();
        if (viewport == null) {
            viewport = new WorkbenchWorldViewport();
            viewport.attach(this);
        } else viewport.invalidateLayout();
        if (ui == null) ui = new WorkbenchSurface(this::request, this::requestFrame, DENSITY);
        ui.refreshResources();
        ui.theme(themeName);
        viewport.setSceneSupplier(this::prepareWorldPointScene);
        rebuild();
    }

    @Override
    public void removed() {
        cancelWorldInput();
        inspectorContents.clear();
        contentProjection.clear();
        inspectorProjection.clear();
        pathPicker.cancelPending();
        projectWorld.cancelInspection();
        if (projectWorld.canCancelPreparation()) projectWorld.cancelPreparation();
        projectDraftGeneration++;
        persistPanelViews();
        layouts.flush();
        if (ui != null) {
            ui.cancelPointer();
            if (uiBuildFailed) ui.abortInput();
            else ui.cancelNavigation();
            ui.releasePaintResources();
        }
        cancelDocumentTabDrag();
        cancelPanelDocking();
        clearDocumentClose();
        if (page == Page.DOCUMENT_CLOSE) page = Page.EDITOR;
        if (viewport != null) {
            viewport.close();
            viewport = null;
        }
        worldSession.detach();
        dragWorkspace = null;
        draggingDock = null;
    }

    @Override
    public void onClose() {
        if (areaPlacement.active()) {
            cancelAreaPlacement();
            return;
        }
        if (pointPlacement.active()) {
            cancelPointPlacement();
            return;
        }
        if (uiBuildFailed) {
            minecraft.setScreen(null);
            return;
        }
        if (nativeCreationVisible()) {
            worldSession.requestCancel();
            return;
        }
        if (ui != null) ui.cancelNavigation();
        if (layouts.loading()) {
            layouts.cancelLoad();
            return;
        }
        if (panelDocking.pending()) {
            cancelPanelDocking();
            ui.cancelPointer();
            return;
        }
        if (documentTabs.dragging()) {
            cancelDocumentTabDrag();
            ui.cancelPointer();
            request();
            return;
        }
        if (page != Page.EDITOR) cancelOverlay();
        else if (drawer >= 0) {
            drawer = -1;
            request();
        } else if (discardFocusedFormInput()) request();
        else if (model.activeDocument().filter(document -> gridSwapSources.keySet().removeIf(key -> key.startsWith(document + "/"))).isPresent()) request();
        else if (worldSession.selecting()) worldSession.cancelSelection();
        else if (worldSession.hasNative()) worldSession.escape();
        else if (worldSession.mayReturn()) returnToHost();
    }

    private void returnToHost() {
        if (!worldSession.mayReturn()) return;
        if (objectCreation != null) {
            guardCreation(this::returnToHost);
            return;
        }
        ui.focus(null);
        worldSession.detach();
        minecraft.setScreen(null);
    }

    private boolean discardFocusedFormInput() {
        var address = ui.tree.focused().map(node -> formFieldControls.get(node.id())).orElse(null);
        if (address == null || !model.pending(address)) return false;
        model.discardInput(address);
        fieldErrors.remove(address);
        return true;
    }

    private void clearFormFocus() {
        revealFormField = null;
        focusFormField = null;
        pendingFormFocus = null;
    }

    private void cancelPointPlacement() {
        if (pointPlacement.active()) {
            pointPlacement.cancel();
            request();
        }
    }

    private void cancelAreaPlacement() {
        if (areaPlacement.active()) {
            areaPlacement.cancel();
            request();
        }
    }

    void cancelWorldInput() {
        cancelPointPlacement();
        cancelAreaPlacement();
        pointOverlay.invalidate();
        if (viewport != null) viewport.cancelInput();
    }

    @Override
    public void tick() {
        worldSession.tick();
        if (pointPlacement.active() && (!pointPlacement.current() || !minecraft.isWindowActive())) cancelPointPlacement();
        if (areaPlacement.active() && (!areaPlacement.current() || !minecraft.isWindowActive())) cancelAreaPlacement();
        pollBoundWorldEntry();
        UUID currentProject = model.projectId().orElse(null);
        Path currentDirectory = model.directory().orElse(null);
        if (!Objects.equals(observedWorldProject, currentProject) || !Objects.equals(observedWorldProjectDirectory, currentDirectory)) {
            observedWorldProject = currentProject;
            observedWorldProjectDirectory = currentDirectory;
            observedWorldPromptState = null;
            selectedProjectWorld = null;
            projectWorldAcknowledged = false;
            projectWorldMessage = null;
            projectDraftGeneration++;
            form.remove("_project_world_candidate");
            form.remove("_project_world_original");
        }
        if (observedWorldPromptState != projectWorld.state()) {
            observedWorldPromptState = projectWorld.state();
            if (!nativeCreationVisible() && page == Page.EDITOR && model.hasProject() && projectWorld.requiresWorld() && Set.of(ProjectWorldWorkbench.State.UNBOUND, ProjectWorldWorkbench.State.BLOCKED, ProjectWorldWorkbench.State.UNKNOWN).contains(projectWorld.state())) openProjectWorld(false);
        }
        if (viewport != null) {
            viewport.setWorldPreparing(worldSession.preparing());
            viewport.setWorldEnabled(!uiBuildFailed && worldEditingReady());
            viewport.tick();
        }
        if (uiBuildFailed) return;
        ui.windowActive(minecraft.isWindowActive());
        if (!minecraft.isWindowActive()) {
            ui.cancelPointer();
            cancelDocumentTabDrag();
            cancelPanelDocking();
            dragWorkspace = null;
            draggingDock = null;
            cancelWorldInput();
            worldSession.clearFocus();
        }
        pollDocumentClose();
        if (openingProject && model.hasProject() && !projectWorld.busy() && projectWorld.state() == ProjectWorldWorkbench.State.READY) {
            openingProject = false;
            if (page == Page.PROJECT) hideOverlay();
        } else if (openingProject && !model.hasProject() && !model.busy() && !projectWorld.busy() && !model.pendingCreation()) openingProject = false;
        if (page == Page.CLOSE && !model.hasProject() && !model.pendingCreation() && !model.busy()) hideOverlay();
        if (inspectingResource && !model.busy()) {
            inspectingResource = false;
            resourceTaskStatus = component(model.status());
            resourceTaskSummary = model.resourceImportSummary().stream().map(WorkbenchScreen::component).toList();
            request();
        }
        if (inspectingImport && !model.busy()) {
            inspectingImport = false;
            importTaskStatus = component(model.status());
            request();
        }
        if (acceptingResource && !model.busy()) {
            acceptingResource = false;
            resourceTaskFinished = model.status().equals(ProjectWorkbench.text("resource_imported"));
            resourceTaskStatus = component(model.status());
            request();
        }
        if (rebuildRequested || observedVersion != model.version() || observedWorldVersion != worldTarget.version() || observedProjectWorldVersion != projectWorld.version() || observedInstallVersion != installations.version() || observedTestVersion != tests.version()) rebuild();
    }

    private void show(Page next) {
        cancelPointPlacement();
        cancelAreaPlacement();
        pathPicker.cancelPending();
        pickerMessage = null;
        projectWorld.cancelInspection();
        if (projectWorld.canCancelPreparation()) projectWorld.cancelPreparation();
        projectDraftGeneration++;
        cancelDocumentTabDrag();
        cancelPanelDocking();
        draggingDock = null;
        ui.cancelPointer();
        if (page == Page.DOCUMENT_CLOSE && next != Page.DOCUMENT_CLOSE) clearDocumentClose();
        if (viewport != null) viewport.cancelInput();
        worldSession.clearFocus();
        if (next == Page.RESOURCES) {
            hideOverlay();
            activateBottom(WorkspaceTemplates.RESOURCES);
            return;
        }
        if (next == Page.RESOURCE_IMPORT) {
            resourceTaskFinished = false;
            resourceTaskSummary = List.of();
        }
        if (page == Page.EDITOR) {
            focusBeforeOverlay = ui.tree.focused().map(ControlTree.Node::id).orElse(null);
            drawerBeforeOverlay = drawer;
        }
        ui.focus(null);
        if (next != Page.FIELD_CHOICES) drawer = -1;
        page = next;
        focusMenuOnPublish = menuPage() && next != Page.ADD_MENU;
        dialogScroll = 0;
        request();
    }

    private void hideOverlay() {
        pathPicker.cancelPending();
        projectWorld.cancelInspection();
        if (projectWorld.canCancelPreparation()) projectWorld.cancelPreparation();
        projectDraftGeneration++;
        ui.cancelNavigation();
        if (page == Page.DOCUMENT_CLOSE) clearDocumentClose();
        page = Page.EDITOR;
        drawer = drawerBeforeOverlay;
        drawerBeforeOverlay = -1;
        choiceAddress = null;
        choiceField = null;
        choiceCreationProperty = null;
        choiceDocumentForm = null;
        restoreChoiceFocus = false;
        referenceDraft = null;
        referenceSelection.clear();
        ui.releaseText("reference/search");
        ui.releaseText("choice/search");
        restoreFocus = true;
        request();
    }

    private void cancelOverlay() {
        if (page == Page.PROJECT && projectWorld.busy()) {
            if (projectWorld.canCancelPreparation()) cancelProjectFlow();
            return;
        }
        if (page == Page.PROJECT_WORLD) {
            if (projectWorldReturn == Page.EDITOR) hideOverlay();
            else show(projectWorldReturn);
            return;
        }
        if (page == Page.TEST) {
            dismissTest();
            return;
        }
        if (page == Page.CREATION_SWITCH) {
            stayWithCreation();
            return;
        }
        if (page == Page.INSTALL && installationDraft && replacementDraft != null) {
            openInstallationRecords();
            return;
        }
        if (page == Page.INSTALL) installations.dismiss();
        if (page == Page.WORLD_REGISTER) {
            worldRegistrationPrompt = null;
            openWorldTarget();
            return;
        }
        if (page == Page.WORLD_TARGET) worldTarget.cancelPending();
        if (page == Page.FIELD_CHOICES) {
            if (choiceCreationProperty != null) returnCreationChoices();
            else returnFieldChoices();
            return;
        }
        if (page == Page.REFERENCES) model.cancelReferences();
        if (page == Page.OBJECT_REMOVE) {
            model.cancelRemoval();
            removalDraft = null;
        }
        if (page == Page.IMPORT || page == Page.IMPORT_REPORT) cancelImportTask();
        if (page == Page.RESOURCE_IMPORT || page == Page.RESOURCE_REPORT) cancelResourceTask();
        if (page == Page.WORKSPACES) {
            ui.releaseText("workspace-rename/");
            workspaceEdits.clear();
        }
        if (page == Page.OBJECT_CREATE) clearObjectCreation();
        hideOverlay();
    }

    private boolean menuPage() {
        return switch (page) {
            case ALL_MENU, PROJECT_MENU, EDIT_MENU, ADD_MENU, VIEW_MENU, WINDOW_MENU, TOOLS_MENU, HELP_MENU, OBJECT_MENU, PANEL_MENU, PANEL_ADD, DOCUMENT_FORMS -> true;
            default -> false;
        };
    }

    private void request() {
        contentEpoch++;
        rebuildRequested = true;
    }

    private void requestFrame() {
        rebuildRequested = true;
    }

    private void run(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException failure) {
            model.report(failure);
        } finally {
            request();
        }
    }

    private void rebuild() {
        if (uiBuildFailed) return;
        try {
            if (ui.refreshResources()) {
                cancelWorldInput();
                ui.reflow();
            }
            syncLayoutScope();
            String scope = model.directory().map(path -> path.toAbsolutePath().normalize().toString()).orElse("") + "|" + model.projectId().map(UUID::toString).orElse("");
            try (var layout = ui.view.beginPublication(scope); var components = ui.components.view().beginPublication(scope); var contents = inspectorContents.begin(scope)) {
                inspectorPublication = contents;
                rebuildFrame(scope, () -> {
                    layout.commit();
                    components.commit();
                    contents.commit();
                });
            } finally {
                inspectorPublication = null;
            }
        } catch (RuntimeException failure) {
            uiBuildFailed = true;
            rebuildRequested = false;
            ui.abortInput();
            cancelDocumentTabDrag();
            cancelPanelDocking();
            dragWorkspace = null;
            draggingDock = null;
            cancelWorldInput();
            worldSession.clearFocus();
            if (viewport != null) viewport.setWorldEnabled(false);
            com.mojang.logging.LogUtils.getLogger().error("Cannot publish Studio UI; preserving editing sessions for retry", failure);
        }
    }

    private void retryUi() {
        uiBuildFailed = false;
        request();
        rebuild();
    }

    private void rebuildFrame(String scope, Runnable afterCommit) {
        buildingContentDocuments.clear();
        buildingListStates.clear();
        buildingObjectLists.clear();
        buildingListViews.clear();
        buildingInspectorViews.clear();
        consumedObjectReveals.clear();
        rebuildRequested = false;
        ui.begin();
        objectControls.clear();
        objectDocuments.clear();
        objectPanels.clear();
        formFieldControls.clear();
        documentTabControls.clear();
        panelInstances.clear();
        panelBounds.clear();
        panelTypes.clear();
        dockGrips.clear();
        dockSources.clear();
        dockRegions.clear();
        viewportBounds = null;
        gridBounds = null;
        documentBarBounds = null;
        confirmationFocus = null;
        layer = 0;
        if (hadProject && !model.hasProject()) {
            objectCreation = null;
            creationPanelTarget = null;
            creationRaw.clear();
            fieldErrors.clear();
            collapsedObjects.clear();
            objectSearch.clear();
        }
        hadProject = model.hasProject();
        formDocuments.keySet().removeIf(key -> !listProjection.documents().containsKey(key.document()));
        if (activeFormDocument != null && !listProjection.documents().containsKey(activeFormDocument.document())) {
            activeFormDocument = null;
            clearFormFocus();
        }
        if (!model.hasProject()) resourceDocument = false;
        reconcileDocumentViews();
        UUID current = model.activeDocument().orElse(null);
        if (!Objects.equals(current, observedDocument)) {
            observedDocument = current;
            if (current != null && model.selectedNode().isEmpty()) runSelectionDefault();
        }
        fieldErrors.keySet().removeIf(address -> !model.pending(address));
        shell();
        if (page != Page.EDITOR && page != Page.PROJECT && page != Page.PROJECT_WORLD && page != Page.NEW_DOCUMENT && page != Page.DOCUMENT_CLOSE && page != Page.INSTALL && page != Page.TEST && page != Page.FIELD_CHOICES && page != Page.ADD_MENU && page != Page.IMPORT && page != Page.IMPORT_REPORT && page != Page.RESOURCE_IMPORT && page != Page.RESOURCE_REPORT && page != Page.WORLD_TARGET) {
            layer = 1;
            dw = Math.max(120, Math.min(menuPage() ? (page == Page.ADD_MENU ? 330 : 210) : page == Page.PROJECT ? 620 : 540, width - 24));
            dh = Math.max(100, Math.min(height - 36, menuPage() ? menuHeight() : page == Page.PROJECT ? 400 : page == Page.CLOSE ? 240 : 360));
            dx = menuPage() ? Math.max(6, Math.min(menuAnchor, width - dw - 6)) : (width - dw) / 2;
            dy = menuPage() ? Math.max(6, Math.min(menuY, height - dh - 6)) : (height - dh) / 2;
            ui.dimOverlay = !menuPage();
            ui.surface("dialog/" + page, dx, dy, dw, dh, layer, menuPage() ? Kind.MENU : Kind.DIALOG);
            boolean popup = menuPage();
            if (!popup) {
                label(page == Page.REFERENCES && referenceDraft != null ? component(referenceDraft.list().label()).copy().append(" · ").append(component(referenceDraft.list().unit())) : c(titleKey()), dx + 14, dy + 12, dw - 60);
                action("dialog/close", Component.literal("×"), dx + dw - 30, dy + 6, 22, this::cancelOverlay);
            }
            int viewportHeight = Math.max(0, dh - (popup ? 12 : 106));
            dialogMaxScroll = Math.max(0, dialogContentHeight() - (popup ? 34 : 40) - viewportHeight);
            dialogScroll = Math.max(0, Math.min(dialogScroll, dialogMaxScroll));
            int originalY = dy;
            ui.clip(new ControlTree.Bounds(dx + 6, dy + (popup ? 6 : 34), dw - 12, viewportHeight));
            if (popup) dy -= 34;
            dy -= dialogScroll;
            try {
                switch (page) {
                    case PANEL_MENU -> panelMenu();
                    case PANEL_ADD -> panelAddMenu();
                    case DOCUMENT_FORMS -> documentFormsMenu();
                    case COMMANDS -> commandDialog();
                    case CREATION_SWITCH -> paragraph(c("creation_switch_hint"), dx + 14, dy + 44, dw - 28, 8);
                    case ALL_MENU -> allMenus();
                    case PROJECT_MENU -> projectMenu();
                    case EDIT_MENU -> editMenu();
                    case WINDOW_MENU -> windowMenu();
                    case ADD_MENU -> addCatalog();
                    case OBJECT_CREATE -> objectCreateForm();
                    case OBJECT_MENU -> objectMenu();
                    case OBJECT_INFO -> objectInfo();
                    case FIELD_CHOICES -> fieldChoices();
                    case REFERENCES -> referenceDialog();
                    case OBJECT_REMOVE -> removalDialog();
                    case VIEW_MENU -> viewMenu();
                    case HELP_MENU -> paragraph(c("quick_help"), dx + 14, dy + 42, dw - 28, 12);
                    case TOOLS_MENU -> toolsMenu();
                    case CLOSE -> closeForm();
                    case RESOURCES -> resourceList();
                    case RESOURCE_IMPORT -> resourceImportForm();
                    case RESOURCE_REPORT -> resourceImportReport();
                    case WORLD_TARGET -> worldTargetForm();
                    case WORLD_REGISTER -> worldRegistrationForm();
                    case INSTALL -> installationForm();
                    case INSTALL_RECORDS -> installationRecords();
                    case PROBLEMS -> problemDialog();
                    case DOCUMENTS -> documentsDialog();
                    case WORKSPACES -> workspaceDialog();
                    case WORKSPACE_NEW -> workspaceNew();
                    default -> {}
                }
            } finally {
                dy = originalY;
                ui.clip(null);
            }
            if (!popup) {
                footerActions();
                if (page == Page.OBJECT_CREATE || page == Page.CREATION_SWITCH) {
                    label(c("create_object"), dx + 14, dy + dh - 48, dw - 28);
                    if (creationError != null) ui.error(creationError, dx + 14, dy + dh - 64, dw - 28, layer);
                } else if (page == Page.INSTALL || page == Page.INSTALL_RECORDS) {
                    label(installationStatus(), dx + 14, dy + dh - 48, dw - 28);
                    Component error = installationError();
                    if (error != null) ui.error(error, dx + 14, dy + dh - 64, dw - 28, layer);
                } else if (page == Page.PROJECT_WORLD) {
                    Component message = projectWorldPanelMessage();
                    if (message == null) message = projectWorldPanelStatus();
                    ui.components.paragraph("project-world/footer-message", message.getString(), new ControlTree.Bounds(dx + 14, dy + dh - 75, dw - 42, 38), layer, projectWorld.failure().isPresent() || message.equals(c("project_world_failed")) ? Variant.DANGER : Variant.MUTED);
                } else {
                    boolean diagnosticShown = page == Page.PROJECT && projectFailure().isPresent() || page == Page.PROBLEMS && model.failureDiagnostic().isPresent();
                    if (!diagnosticShown) label(page == Page.PROJECT ? projectPageStatus() : page == Page.WORLD_TARGET && worldMappingError != null ? worldMappingError : page == Page.WORLD_REGISTER ? registrationStatus() : component(page == Page.WORLD_TARGET ? worldTarget.status() : model.status()), dx + 14, dy + dh - 48, dw - 28);
                    if (page == Page.PROJECT) {
                        if (!diagnosticShown && projectCreationError != null) ui.error(projectCreationError, dx + 14, dy + dh - 64, dw - 28, layer);
                    } else if (!diagnosticShown && page != Page.WORLD_TARGET && page != Page.WORLD_REGISTER && !model.problems().isEmpty()) label(component(model.problems().get(0).message()), dx + 14, dy + dh - 64, dw - 28);
                }
            }
            if (dialogMaxScroll > 0) {
                // A narrow side rail keeps scrolling separate from fixed footer actions.
                action("dialog/scroll-up", Component.literal("↑"), dx + dw - 26, dy + 36, 18, () -> dialogScroll -= 44);
                action("dialog/scroll-down", Component.literal("↓"), dx + dw - 26, dy + dh - (popup ? 28 : 86), 18, () -> dialogScroll += 44);
            }
        }
        if (page == Page.DOCUMENT_CLOSE) {
            layer = 1;
            documentCloseDialog();
        }
        if (page == Page.PROJECT || page == Page.PROJECT_WORLD || page == Page.NEW_DOCUMENT) {
            layer = 1;
            projectDialog();
        }
        if (page == Page.INSTALL) {
            layer = 1;
            installationTaskDialog();
        }
        if (page == Page.TEST) {
            layer = 1;
            testTaskDialog();
        }
        if (page == Page.FIELD_CHOICES) {
            layer = 2;
            choicePopup();
        }
        if (page == Page.ADD_MENU) {
            layer = 2;
            catalogPopup();
        }
        if (page == Page.RESOURCE_IMPORT || page == Page.RESOURCE_REPORT) {
            layer = 1;
            resourceTaskDialog();
        }
        if (page == Page.IMPORT || page == Page.IMPORT_REPORT) {
            layer = 1;
            importTaskDialog();
        }
        if (page == Page.WORLD_TARGET) {
            layer = 1;
            worldTargetDialog();
        }
        if (!worldSession.hasNative() && (page != Page.EDITOR || drawer >= 0)) {
            cancelWorldInput();
            worldSession.clearFocus();
        }
        nativeWorldBounds = nativeCreationVisible() ? new ControlTree.Bounds(8, 36, Math.max(1, width - 16), Math.max(1, height - 44)) : null;
        if (layouts.loading()) {
            layer = 4;
            ui.dimOverlay = true;
            int loadingWidth = Math.min(300, width - 24), loadingX = (width - loadingWidth) / 2, loadingY = (height - 88) / 2;
            ui.surface("studio/layout-loading", loadingX, loadingY, loadingWidth, 88, layer, Kind.DIALOG);
            paragraph(c("layout_storage_loading"), loadingX + 14, loadingY + 18, loadingWidth - 28, 2);
            action("studio/layout-cancel", c("cancel"), loadingX + 14, loadingY + 54, loadingWidth - 28, layouts::cancelLoad);
        }
        if (nativeCreationVisible()) {
            ui.surface("project/native-overlay", 0, 0, width, height, 5, Kind.DIALOG);
            ui.heading(c("project_world_native"), 12, 11, Math.max(20, width - 130), 5);
            ui.components.button(studioAction("project/native-cancel", c("cancel"), Icon.CLOSE, Variant.DEFAULT, worldSession.canCancel(), false, worldSession::requestCancel), nativeCreationCancelBounds(), 5, Kind.BUTTON);
        }
        ui.publish(scope, () -> {
            afterCommit.run();
            listStates = Map.copyOf(buildingListStates);
            objectLists = Map.copyOf(buildingObjectLists);
            contentProjection.retainDocuments(buildingContentDocuments);
            inspectorProjection.retainViews(buildingInspectorViews);
            listProjection.retainViews(buildingListViews);
            consumedObjectReveals.forEach((key, value) -> revealObjects.remove(key, value));
        });
        if (viewport != null) {
            viewport.configure(viewportBounds, DENSITY);
            viewport.setWorldPreparing(worldSession.preparing());
            viewport.setWorldEnabled(worldEditingReady());
        }
        worldSession.configure(nativeWorldBounds != null ? nativeWorldBounds : viewportBounds);
        persistPanelViews();
        if (pickerFocus != null) {
            ui.restoreFocus(pickerFocus);
            pickerFocus = null;
        }
        reconcilePanelDocking();
        if (restoreFocus) {
            ui.restoreFocus(focusBeforeOverlay);
            restoreFocus = false;
        }
        if (focusMenuOnPublish && menuPage() && ui.tree.topLayer() == 1) {
            ui.tree.snapshot().stream().filter(node -> node.layer() == 1 && node.focusable() && node.enabled())
                    .findFirst().ifPresent(node -> ui.focus(node.id()));
            focusMenuOnPublish = false;
        }
        if (confirmationFocus != null && ui.tree.focused().isEmpty()) ui.restoreFocus(confirmationFocus);
        if (pendingFormFocus != null && page == Page.EDITOR) {
            ui.restoreFocus(pendingFormFocus);
            if (ui.tree.focused().map(node -> node.id().equals(pendingFormFocus)).orElse(false)) focusFormField = null;
            pendingFormFocus = null;
        }
        if (page == Page.COMMANDS && ui.tree.focused().isEmpty()) ui.restoreFocus("studio/command-search");
        if (restoreChoiceFocus) {
            ui.restoreFocus(choiceFormFocus);
            restoreChoiceFocus = false;
        }
        observedVersion = model.version();
        observedWorldVersion = worldTarget.version();
        observedProjectWorldVersion = projectWorld.version();
        observedInstallVersion = installations.version();
        observedTestVersion = tests.version();
    }

    private void footerActions() {
        switch (page) {
            case COMMANDS -> footerAction("studio/commands/cancel", c("cancel"), 0, 1, this::cancelOverlay, true, "");
            case CREATION_SWITCH -> {
                footerAction("creation-switch/finish", c("creation_finish_switch"), 0, 3, () -> finishCreationSwitch(true), editableFields(), "protected_status");
                footerAction("creation-switch/discard", c("creation_discard_switch"), 1, 3, () -> finishCreationSwitch(false), true, "");
                footerAction("creation-switch/stay", c("creation_stay"), 2, 3, this::stayWithCreation, true, "");
            }
            case INSTALL -> {
                if (installations.state() == RuntimeInstallationWorkbench.State.READY)
                    footerAction("install/confirm", installationConfirmationLabel(), 0, 3, () -> installationAction(installations::confirm), installations.canConfirm(), "world_unverified");
                else if (installations.busy())
                    footerAction("install/cancel", c("cancel_task"), 0, 3, () -> installationAction(installations::cancel), true, "");
                else if (installations.canQuery())
                    footerAction("install/query", c("install_query"), 0, 3, () -> installationAction(installations::query), true, "");
                else footerAction("install/prepare", c("install_prepare"), 0, 3, this::beginInstallation,
                        installationBlocker().isEmpty(), "install_source_required");
                footerAction("install/records", c("install_records"), 1, 3, this::openInstallationRecords, installations.canStart() && worldTarget.installationTarget().isPresent(), "world_unverified");
                footerAction("install/back", c(installations.state() == RuntimeInstallationWorkbench.State.READY ? "cancel" : "back"), 2, 3, this::cancelOverlay, true, "");
            }
            case INSTALL_RECORDS -> {
                footerAction("install/refresh", c("install_refresh"), 0, 2, this::refreshInstallationRecords, !installations.readingRecords() && worldTarget.installationTarget().isPresent(), "world_unverified");
                footerAction("install/records-back", c("back"), 1, 2, this::openInstallation, true, "");
            }
            case WORLD_TARGET -> {
                footerAction("world/verify", c("world_verify"), 0, 3, worldTarget::bind, model.hasProject() && worldTarget.available() && !worldTarget.busy() && !worldTarget.mappings().isEmpty() && worldMappingError == null, "world_unavailable");
                footerAction("world/disconnect", c("world_disconnect"), 1, 3, worldTarget::disconnect, !worldTarget.registering() && (worldTarget.state() == WorldTargetWorkbench.State.BOUND || worldTarget.busy()), "world_unverified");
                footerAction("world/back", c(worldTarget.busy() && !worldTarget.registering() ? "cancel_task" : "back"), 2, 3, this::cancelOverlay, true, "");
            }
            case WORLD_REGISTER -> {
                if (worldRegistrationPrompt != null) footerAction("world/register-confirm", c("world_register_confirm"), 0, 2, () -> {
                    worldTarget.register(worldRegistrationPrompt);
                    worldRegistrationPrompt = null;
                }, worldTarget.canRegister(worldRegistrationPrompt), "world_registration_stale");
                footerAction("world/register-back", c(worldRegistrationPrompt != null ? "cancel" : "world_return_target"), worldRegistrationPrompt != null ? 1 : 0, worldRegistrationPrompt != null ? 2 : 1, this::cancelOverlay, true, "");
            }
            case PROJECT -> {
                String disabledReason = projectSubmitDisabledReason();
                if (!model.hasProject() && !model.pendingCreation()) footerAction("project/submit", c(creating ? "create_project" : "open_project"), 0, 2, () -> {
                    try {
                        Path path = Path.of(form.getOrDefault("project_path", ""));
                        projectCreationError = null;
                        if (creating) projectWorld.create(path, form.getOrDefault("project_name", ""), projectTemplate, form.getOrDefault("first_document_name", ""), Map.copyOf(projectTemplateRaw()), selectedProjectWorld);
                        else model.open(path);
                        openingProject = true;
                    } catch (RuntimeException failure) {
                        model.report(failure);
                        projectCreationError = model.problems().isEmpty() ? c("failed") : component(model.problems().get(0).message());
                    }
                }, disabledReason.isEmpty(), disabledReason);
                footerAction("project/cancel", c(model.busy() || projectWorld.busy() ? "cancel_task" : "cancel"), 1, 2, this::cancelProjectFlow, projectWorld.canCancelPreparation() || !projectWorld.busy() && (!model.busy() || model.canCancel()), "publishing");
            }
            case PROJECT_WORLD -> {
                footerAction("project-world/back", c("project_world_back"), 0, 2, this::cancelOverlay, true, "");
                if (projectWorld.binding().isPresent()) footerAction("project-world/enter", c("project_world_open"), 1, 2, this::openAuthoringWorld, projectWorld.state() == ProjectWorldWorkbench.State.READY && !projectWorld.busy(), "project_world_blocked");
                else footerAction("project-world/confirm", c("project_world_use"), 1, 2, this::acceptProjectWorld, selectedProjectWorld != null && !model.busy() && !projectWorld.busy() && (!model.hasProject() || projectWorld.state() == ProjectWorldWorkbench.State.UNBOUND), "project_world_required");
            }
            case NEW_DOCUMENT -> {
                footerAction("document/import", c("import_legacy"), 0, 3, this::openImportTask, model.hasProject(), "missing_project");
                footerAction("document/create", c("create_document"), 1, 3, () -> guardCreation(() -> {
                    model.createDocument(documentType, form.getOrDefault("document_name", ""));
                    hideOverlay();
                }), model.hasProject() && documentType != null && !model.busy(), "missing_project");
                footerAction("dialog/cancel", c("cancel"), 2, 3, this::cancelOverlay, true, "");
            }
            case RESOURCES -> {
                var view = model.resources();
                footerAction("resources/import", c("resource_import"), 0, 3, this::openResourceImport, !model.busy() && !model.protectedMode(), "protected_status");
                footerAction("resources/undo", c("resource_undo"), 1, 3, model::undoResources, !model.busy() && view.map(v -> v.canUndo()).orElse(false), "unavailable_history");
                footerAction("resources/redo", c("resource_redo"), 2, 3, model::redoResources, !model.busy() && view.map(v -> v.canRedo()).orElse(false), "unavailable_history");
            }
            case RESOURCE_IMPORT -> {
                footerAction("resource-import/inspect", c("inspect_import"), 0, 2, this::inspectResourceImport, resourceImporter != null && !model.busy(), "missing_importer");
                footerAction("resource-import/cancel", c("cancel"), 1, 2, this::cancelOverlay, true, "");
            }
            case RESOURCE_REPORT -> {
                footerAction("resource-import/accept", c("resource_accept"), 0, 2, () -> {
                    model.acceptResourceImport();
                    acceptingResource = true;
                }, model.resourceImportReady() && !model.busy(), "inspect_import");
                footerAction("resource-import/back", c(model.busy() ? "cancel_task" : "resource_back"), 1, 2, () -> {
                    cancelResourceTask();
                    show(Page.RESOURCE_IMPORT);
                }, !model.busy() || model.canCancel(), "publishing");
            }
            case CLOSE -> {
                footerAction("close/save", c("save_all"), 0, 3, model::saveAll, model.hasProject() && !model.busy() && !model.unresolvedSave(), "unknown_close");
                footerAction("close/discard", c(model.unsaved() ? "discard_close" : "close_project"), 1, 3, () -> model.closeProject(true), !model.busy() && !model.unresolvedSave(), "unknown_close");
                footerAction("dialog/cancel", c("cancel"), 2, 3, this::cancelOverlay, true, "");
            }
            case WORKSPACES -> {
                footerAction("workspace/apply", c("apply"), 0, 2, () -> guardCreation(this::applyWorkspaceEdits), !workspaceEdits.isEmpty(), "no_workspace");
                footerAction("dialog/cancel", c("cancel"), 1, 2, this::cancelOverlay, true, "");
            }
            case OBJECT_CREATE -> {
                footerAction("object/create", c("create_object"), 0, 2, this::confirmObjectCreation, objectCreation != null && !model.protectedMode() && !model.unresolvedSave(), "protected_status");
                footerAction("object/cancel", c("cancel"), 1, 2, this::cancelOverlay, true, "");
            }
            case FIELD_CHOICES -> {
                footerAction("choice/previous", c("previous"), 0, 3, () -> choiceScroll -= choiceRows(), choiceScroll > 0, "");
                footerAction("choice/next", c("next"), 1, 3, () -> choiceScroll += choiceRows(), choiceScroll + choiceRows() < filteredChoices().size(), "");
                footerAction("choice/cancel", c("cancel"), 2, 3, this::cancelOverlay, true, "");
            }
            case REFERENCES -> {
                footerAction("reference/previous", c("previous"), 0, 4, () -> referenceScroll -= referenceRows(), referenceScroll > 0, "");
                footerAction("reference/next", c("next"), 1, 4, () -> referenceScroll += referenceRows(), referenceScroll + referenceRows() < filteredReferences().size(), "");
                footerAction("reference/apply", c("apply"), 2, 4, () -> {
                    model.replaceReferences(referenceDraft, referenceSelection);
                    hideOverlay();
                }, referenceDraft != null && !model.protectedMode() && !model.unresolvedSave(), "protected_status");
                footerAction("reference/cancel", c("cancel"), 3, 4, this::cancelOverlay, true, "");
            }
            case OBJECT_REMOVE -> {
                footerAction("object/remove", c("delete_object"), 0, 2, this::confirmRemoval, removalDraft != null && removalDraft.plan().allowed() && !model.protectedMode() && !model.unresolvedSave(), "delete_blocked");
                footerAction("object/remove-cancel", c("cancel"), 1, 2, this::cancelOverlay, true, "");
            }
            default -> footerAction("dialog/cancel", c("back"), 0, 1, this::cancelOverlay, true, "");
        }
    }

    private void footerAction(String id, Component caption, int slot, int count, Runnable action, boolean enabled, String reason) {
        if (collectingFooter != null) {
            boolean primary = id.equals("project/submit") || id.equals("document/create") || id.equals("project-world/confirm") || id.equals("project-world/enter");
            collectingFooter.add(new StudioComponents.Action(id, caption.getString(), Icon.NONE, primary ? Variant.PRIMARY : Variant.DEFAULT, enabled, false, enabled || reason.isBlank() ? "" : c(reason).getString(), () -> run(action)));
            return;
        }
        int w = Math.min(116, (dw - 28 - (count - 1) * 6) / count), x = dx + dw - 14 - count * w - (count - 1) * 6 + slot * (w + 6);
        action(id, caption, x, dy + dh - 29, w, action, enabled, reason);
    }

    private void inspectImport() {
        importTaskFinished = false;
        importFailureDetails = false;
        importedDocument = null;
        importResultProblems = List.of();
        var selected = model.importers().get(importer);
        importTaskSource = Component.literal(model.projectName()).append(" · ").append(component(selected.label()));
        try {
            Map<String, Path> paths = new HashMap<>();
            selected.inputs().forEach(source -> {
                String raw = form.getOrDefault(importInputKey(source.id()), "");
                if (!raw.isBlank()) paths.put(source.id(), Path.of(raw));
            });
            model.inspectImport(importer, paths);
            inspectingImport = model.busy();
            importTaskStatus = component(model.status());
        } catch (RuntimeException failure) {
            inspectingImport = false;
            model.reportImportFailure(failure);
            importTaskStatus = component(model.status());
        }
        show(Page.IMPORT_REPORT);
    }

    private int menuHeight() {
        return switch (page) {
            case PANEL_MENU -> 320;
            case VIEW_MENU -> 110 + activeWorkspace().map(w -> w.panels().size()).orElse(0) * 26;
            case PANEL_ADD -> 60 + availablePanelTypes().size() * 27;
            case ALL_MENU -> 200;
            case PROJECT_MENU -> 269;
            case EDIT_MENU -> 160;
            case WINDOW_MENU -> 328;
            case HELP_MENU -> 210;
            case OBJECT_MENU -> 144 + objectForms().size() * 25;
            case DOCUMENT_FORMS -> 44 + documentForms(formMenuDocument).size() * 25;
            case ADD_MENU -> Math.max(150, 84 + activeCreationChoices().size() * 46);
            default -> 184;
        };
    }

    private int dialogContentHeight() {
        return switch (page) {
            case PANEL_MENU -> 350;
            case VIEW_MENU -> 130 + activeWorkspace().map(w -> w.panels().size()).orElse(0) * 26;
            case PANEL_ADD -> 60 + availablePanelTypes().size() * 27;
            case DOCUMENT_FORMS -> 44 + documentForms(formMenuDocument).size() * 25;
            case OBJECT_MENU -> 144 + objectForms().size() * 25;
            case COMMANDS -> 90 + studioCommands().size() * 27;
            case CREATION_SWITCH -> 200;
            case PROBLEMS -> Math.max(230, dh - 86);
            case ALL_MENU -> 244;
            case PROJECT_MENU -> 295;
            case CLOSE -> 244;
            case PROJECT, PROJECT_WORLD, NEW_DOCUMENT -> 0; // Measured by projectDialog.
            case WORKSPACES -> 280;
            case ADD_MENU -> 106 + activeCreationChoices().size() * 46;
            case OBJECT_CREATE -> 100 + (objectCreation == null ? 0 : objectCreation.fields().size() * 46);
            case OBJECT_INFO -> objectInfoHeight();
            case OBJECT_REMOVE -> removalDraft == null ? 100 : 188 + removalDraft.plan().objects().size() * 22 + removalDraft.plan().references().size() * 44;
            case FIELD_CHOICES, REFERENCES -> 80;
            case WORKSPACE_NEW -> 70 + templates.size() * 28;
            case RESOURCE_IMPORT -> 180 + (resourceImporter == null ? 1 : model.resourceImporters().get(resourceImporter).inputs().size()) * 48;
            case RESOURCE_REPORT -> 170 + model.resourceImportSummary().size() * 44;
            case RESOURCES -> 150 + model.resources().map(v -> v.content().resources().size()).orElse(0) * 48;
            case WORLD_TARGET -> 430 + worldTarget.mappings().size() * 28 + (worldTarget.canRegister() ? 32 : 0) + (worldTarget.registrationReceipt().isPresent() ? 32 : 0);
            case WORLD_REGISTER -> worldRegistrationHeight();
            case INSTALL -> installationHeight();
            case INSTALL_RECORDS -> installationRecordsHeight();
            default -> Math.max(120, dh - 86);
        };
    }

    private void runSelectionDefault() {
        try {
            var nodes = model.nodes();
            if (!nodes.isEmpty()) model.selectNode(nodes.get(0).id());
        } catch (RuntimeException failure) {
            model.report(failure);
        }
    }

    private String titleKey() {
        return switch (page) {
            case CREATION_SWITCH -> "creation_switch_title";
            case COMMANDS -> "studio_commands";
            case ALL_MENU -> "menu";
            case PROJECT_MENU -> "project";
            case PROJECT_WORLD -> "project_world_title";
            case PROJECT -> model.hasProject() || model.pendingCreation() ? "project_details" : creating ? "new_project" : "open_project";
            case EDIT_MENU -> "edit_menu";
            case WINDOW_MENU -> "window_menu";
            case ADD_MENU -> "add_menu";
            case OBJECT_MENU -> "object_actions";
            case OBJECT_CREATE -> "create_object";
            case OBJECT_INFO -> "identity_details";
            case VIEW_MENU -> "view_menu";
            case HELP_MENU -> "help_menu";
            case FIELD_CHOICES -> "choose_value";
            case OBJECT_REMOVE -> "delete_object";
            case REFERENCES -> "manage_references";
            case TOOLS_MENU -> "tools_menu";
            case NEW_DOCUMENT -> "new_document";
            case IMPORT -> "import_legacy";
            case IMPORT_REPORT -> "import_report";
            case CLOSE -> "close_project";
            case DOCUMENT_CLOSE -> "studio_close_document_title";
            case PROBLEMS -> "problems";
            case RESOURCES -> "resources";
            case RESOURCE_IMPORT -> "resource_import";
            case RESOURCE_REPORT -> "resource_report";
            case WORLD_TARGET -> "world_target";
            case WORLD_REGISTER -> "world_register";
            case INSTALL -> "install_title";
            case INSTALL_RECORDS -> "install_records";
            case TEST -> "test_title";
            case DOCUMENTS -> "documents";
            case WORKSPACES, WORKSPACE_NEW -> "workspace_manage";
            default -> "open";
        };
    }

    private void shell() {
        documentBarBounds = null;
        var view = ui.view;
        var title = new ArrayList<StudioView.Element>();
        boolean compact = width < studioUnits(1100), narrow = !widePanels();
        if (!compact) title.add(view.button(studioAction("studio/home", c("studio_brand"), Icon.BOX, Variant.DEFAULT, true, false, () -> show(Page.PROJECT))));
        if (compact) title.add(shellMenu("menu", Page.ALL_MENU));
        else {
            title.add(shellMenu("project", Page.PROJECT_MENU));
            title.add(shellMenu("edit_menu", Page.EDIT_MENU));
            title.add(shellMenu("view_menu", Page.VIEW_MENU));
            title.add(shellMenu("tools_menu", Page.TOOLS_MENU));
            title.add(shellMenu("help_menu", Page.HELP_MENU));
        }
        var projectTitle = model.hasProject() ? Component.literal(model.projectName()).append(c(model.unsaved() ? "dirty_mark" : "saved_mark")) : c("studio_brand");
        title.add(view.text("studio/project-title", projectTitle.getString(), TextSize.BODY, Variant.MUTED).style(Style.fill()));
        title.add(view.button(studioAction("studio/commands", c("studio_commands"), Icon.SEARCH, Variant.OUTLINED, true, false, this::openCommands), compact ? Kind.ICON : Kind.BUTTON));
        Component workspace = activeWorkspace().<Component>map(value -> Component.literal(value.name())).orElse(c("workspace_manage"));
        title.add(view.button(studioAction("workspace/current", workspace, Icon.LAYOUT, Variant.DEFAULT, true, false, this::manageWorkspaces), narrow ? Kind.ICON : Kind.BUTTON).style(Style.content().width(narrow ? Length.fixed(20) : Length.content().limits(20, studioUnits(160)))));
        title.add(view.button(studioAction("studio/test", c("studio_test"), Icon.PLAY, Variant.PRIMARY, model.hasProject() && !model.busy(), false, this::openTestWorkflow)));
        title.add(view.button(studioAction("host/return", c("studio_close_editor"), Icon.CLOSE, Variant.DEFAULT, worldSession.mayReturn(), false, this::returnToHost), Kind.ICON));
        var top = view.surface("studio/titlebar", Kind.HEADER, FlowLayout.Kind.ROW, Style.defaults().height(Length.fixed(MENU_BOTTOM)).padding(Insets.symmetric(4, 4)).gap(3), title);
        var body = view.row("studio/body", Style.fill(), List.of(studioRail(), view.placeholder("studio/panels", Style.fill())));
        var status = new ArrayList<StudioView.Element>();
        status.add(view.button(studioAction("world/status", worldStatus(), Icon.NONE, Variant.DEFAULT, true, false, this::openWorldTarget)).style(Style.fill()));
        if (tests.state() != RuntimeTestWorkbench.State.IDLE || testActionError != null) status.add(view.button(studioAction("test/status", c("test_title").copy().append(" · ").append(testStatus()), Icon.NONE, Variant.DEFAULT, true, false, () -> show(Page.TEST))).style(Style.fill()));
        else status.add(view.button(studioAction("install/status", c("test_workflow").copy().append(" · ").append(component(installations.status())), Icon.NONE, Variant.DEFAULT, true, false, this::openInstallationTask)).style(Style.fill()));
        var footer = view.surface("studio/statusbar", Kind.HEADER, FlowLayout.Kind.ROW, Style.defaults().height(Length.fixed(STATUS_HEIGHT)), status);
        var arranged = view.layout(view.column("studio/shell", Style.fill(), List.of(top, body, footer)), new ControlTree.Bounds(0, 0, width, height));
        ui.emit(arranged, 0);
        ui.detail("studio/commands", "Ctrl Shift P");
        var panels = arranged.bounds("studio/panels");
        try {
            editorPanels(panels.y(), panels.height());
        } catch (RuntimeException failure) {
            model.report(failure);
        }
    }

    private StudioView.Element shellMenu(String key, Page next) {
        String id = "menu/" + key;
        return ui.view.button(studioAction(id, c(key), Icon.NONE, Variant.DEFAULT, true, false, () -> {
            menuAnchor = ui.tree.snapshot().stream().filter(node -> node.id().equals(id)).findFirst().map(node -> node.bounds().x()).orElse(0);
            menuY = MENU_BOTTOM;
            show(next);
        }));
    }

    private StudioView.Element studioRail() {
        var controls = new ArrayList<StudioView.Element>();
        EditorId[] types = { WorkspaceTemplates.PROJECT, WorkspaceTemplates.OBJECTS, WorkspaceTemplates.RESOURCES, WorkspaceTemplates.INSPECTOR, WorkspaceTemplates.PROBLEMS, WorkspaceTemplates.TASKS };
        Icon[] icons = { Icon.FOLDER, Icon.TREE, Icon.RESOURCES, Icon.SLIDERS, Icon.WARNING, Icon.CLOCK };
        for (int i = 0; i < types.length; i++) {
            EditorId type = types[i];
            controls.add(ui.view.button(studioAction("studio/rail/" + type, panelLabel(type), icons[i], Variant.DEFAULT, true, false, () -> activateBottom(type)), Kind.ICON));
        }
        controls.add(ui.view.placeholder("studio/rail/space", Style.fill()));
        controls.add(ui.view.button(studioAction("studio/theme", c(themeName.equals("dark") ? "studio_theme_light" : "studio_theme_dark"), themeName.equals("dark") ? Icon.SUN : Icon.MOON, Variant.DEFAULT, true, false, () -> setTheme(themeName.equals("dark") ? "light" : "dark")), Kind.ICON));
        controls.add(ui.view.button(studioAction("studio/layout", c("workspace_manage"), Icon.LAYOUT, Variant.DEFAULT, true, false, () -> {
            menuAnchor = TOOL_RAIL;
            menuY = Math.max(BODY_TOP, height - 260);
            show(Page.WINDOW_MENU);
        }), Kind.ICON));
        return ui.view.surface("studio/rail", Kind.PANEL, Style.fill().width(Length.fixed(TOOL_RAIL)).padding(Insets.symmetric(1, 4)).gap(3), controls);
    }

    private void documentBar(PanelInstance panel, int x, int y, int w, boolean ownActions) {
        documentBarBounds = new ControlTree.Bounds(x, y, w, studioUnits(35));
        var tabs = new ArrayList<StudioComponents.DocumentTab>();
        var order = documentTabs.keys();
        var dragged = documentTabs.dragSource().filter(ignored -> documentTabs.movedDuringDrag());
        for (var key : order) {
            Component caption = documentViewCaption(key);
            String id = key.controlId();
            boolean dirty = key.document() != null && (model.documentUnsaved(key.document()) || model.documentHasPendingInputs(key.document()));
            Icon icon = switch (key.kind()) {
                case SCENE -> Icon.MAP;
                case FORM -> Icon.SLIDERS;
                case GRID -> Icon.GRID;
                case RESOURCES -> Icon.RESOURCES;
            };
            var activate = studioAction(id, caption, icon, Variant.DEFAULT, true, documentTabs.active().filter(key::equals).isPresent(), () -> guardCreation(() -> activateDocumentView(key)));
            var insertion = StudioComponents.TabInsertion.NONE;
            if (dragged.isPresent() && key.equals(documentDropTarget) && !key.equals(dragged.orElseThrow())) insertion = order.indexOf(dragged.orElseThrow()) < order.indexOf(key) ? StudioComponents.TabInsertion.AFTER : StudioComponents.TabInsertion.BEFORE;
            tabs.add(new StudioComponents.DocumentTab(activate, dirty, c("studio_document_close", caption.getString()).getString(), () -> run(() -> requestDocumentClose(key)), dragged.filter(key::equals).isPresent(), insertion));
            documentTabControls.put(id, key);
            documentTabControls.put(id + "/close", key);
            documentTabControls.put(id + "/surface", key);
        }
        var controls = new ArrayList<StudioView.Element>();
        controls.add(ui.view.placeholder("studio/document-tabs/area", Style.fill()));
        controls.add(ui.view.button(studioAction("document/new", c("new_document"), Icon.PLUS, Variant.DEFAULT, true, false, () -> show(Page.NEW_DOCUMENT)), Kind.ICON));
        controls.add(ui.view.button(studioAction("document/list", c("documents"), Icon.MORE, Variant.DEFAULT, true, false, () -> show(Page.DOCUMENTS)), Kind.ICON));
        if (ownActions) for (var action : panelActions(panel, x, y, w)) controls.add(ui.view.button(action, Kind.ICON));
        var arranged = ui.view.layout(ui.view.surface("studio/documents", Kind.HEADER, FlowLayout.Kind.ROW, Style.fill(), controls), documentBarBounds);
        ui.emit(arranged, layer);
        var area = arranged.bounds("studio/document-tabs/area");
        documentScroll = ui.components.documentTabs("studio/document-tabs", area, layer, tabs, documentScroll);
        if (tabs.isEmpty()) ui.emit(ui.view.layout(ui.view.text("studio/no-document", c("studio_no_open_views").getString(), TextSize.SMALL, Variant.MUTED), area), layer);
    }

    private List<StudioDocument.Form> documentForms(UUID document) {
        return document == null ? List.of() : listProjection.forms(document);
    }

    private List<StudioDocument.Form> objectForms() {
        return documentForms(objectMenuDocument).stream().filter(form -> form.target().equals(objectMenuNode)).toList();
    }

    private Optional<StudioFormDocument.Key> activeFormKey() {
        return Optional.ofNullable(activeFormDocument).filter(key -> !resourceDocument && model.activeDocument().filter(key.document()::equals).isPresent());
    }

    private void openSceneDocument(UUID document) {
        guardCreation(() -> activateDocumentView(StudioDocumentTabs.Key.scene(document)));
    }

    private void openFormDocument(UUID document, StudioDocument.Form form) {
        guardCreation(() -> {
            activateDocumentView(StudioDocumentTabs.Key.form(document, form.id()));
            activeWorkspace().flatMap(workspace -> workspace.panels().stream().filter(panel -> panel.type().equals(WorkspaceTemplates.VIEWPORT)).findFirst()).ifPresent(panel -> focusPanel(panel.id()));
            drawer = -1;
            request();
        });
    }

    private boolean documentViewAvailable(StudioDocumentTabs.Key key) {
        if (key.kind() == StudioDocumentTabs.Kind.RESOURCES) return model.hasProject();
        if (!listProjection.documents().containsKey(key.document())) return false;
        return switch (key.kind()) {
            case FORM -> documentForms(key.document()).stream().anyMatch(form -> form.id().equals(key.target()));
            case GRID -> projectedGrid(key.document(), key.target()).isPresent();
            default -> true;
        };
    }

    private Component documentViewCaption(StudioDocumentTabs.Key key) {
        if (key.kind() == StudioDocumentTabs.Kind.RESOURCES) return c("resources");
        var handle = listProjection.documents().get(key.document());
        Component name = Component.literal(handle == null ? key.document().toString() : handle.displayName());
        return switch (key.kind()) {
            case FORM -> name.copy().append(" · ").append(documentForms(key.document()).stream().filter(form -> form.id().equals(key.target())).findFirst().map(form -> component(form.label())).orElse(Component.literal(key.target())));
            case GRID -> name.copy().append(" · ").append(projectedGrid(key.document(), key.target()).map(grid -> gridCaption(key.document(), grid)).orElse(Component.literal(key.target())));
            default -> name;
        };
    }

    private void activateDocumentView(StudioDocumentTabs.Key key) {
        activateDocumentView(key, null);
    }

    private void activateDocumentView(StudioDocumentTabs.Key key, TreeFocusOrigin origin) {
        if (!documentViewAvailable(key)) return;
        cancelWorldInput();
        worldSession.clearFocus();
        if (!treeFocusCurrent(origin) || !Objects.equals(key.document(), origin.context().document())) ui.focus(null);
        clearFormFocus();
        documentTabs.open(key);
        resourceDocument = key.kind() == StudioDocumentTabs.Kind.RESOURCES;
        activeFormDocument = null;
        if (key.document() != null) model.selectDocument(key.document());
        if (key.kind() == StudioDocumentTabs.Kind.FORM) {
            var form = documentForms(key.document()).stream().filter(value -> value.id().equals(key.target())).findFirst().orElseThrow();
            model.selectNode(key.document(), form.target());
            activeFormDocument = new StudioFormDocument.Key(key.document(), key.target());
            formDocuments.computeIfAbsent(activeFormDocument, ignored -> new StudioFormDocument());
        } else if (key.kind() == StudioDocumentTabs.Kind.GRID) {
            var grid = projectedGrid(key.document(), key.target()).orElseThrow();
            String selected = model.selectedNode(key.document()).orElse("");
            if (!selected.equals(grid.owner()) && grid.cells().stream().noneMatch(cell -> cell.node().filter(selected::equals).isPresent())) model.selectNode(key.document(), grid.owner());
            lastGridOwners.put(key.document(), grid.owner());
        }
        request();
    }

    /** Seed once per project; rendering never reopens a view which the user closed. */
    private void reconcileDocumentViews() {
        if (reconciledDocumentVersion == model.version()) return;
        if (!model.hasProject()) {
            documentTabs.clear();
            knownDocumentHandles.clear();
            documentViewsInitialized = false;
            activeFormDocument = null;
            resourceDocument = false;
            if (page == Page.DOCUMENT_CLOSE) hideOverlay();
            else clearDocumentClose();
            return;
        }
        var handles = listProjection.documents();
        boolean replaced = knownDocumentHandles.entrySet().stream().anyMatch(entry -> handles.containsKey(entry.getKey()) && handles.get(entry.getKey()) != entry.getValue());
        if (replaced) {
            documentTabs.clear();
            knownDocumentHandles.clear();
            formDocuments.clear();
            lastGridOwners.clear();
            activeFormDocument = null;
            resourceDocument = false;
            documentViewsInitialized = false;
        }
        var before = documentTabs.active();
        if (!documentViewsInitialized) {
            documentViewsInitialized = true;
            model.activeDocument().ifPresent(id -> documentTabs.open(StudioDocumentTabs.Key.scene(id)));
        } else {
            documentTabs.retain(key -> documentViewAvailable(key) && (key.document() == null || knownDocumentHandles.get(key.document()) == handles.get(key.document())));
            for (var entry : handles.entrySet()) if (!knownDocumentHandles.containsKey(entry.getKey())) documentTabs.open(StudioDocumentTabs.Key.scene(entry.getKey()));
        }
        knownDocumentHandles.clear();
        knownDocumentHandles.putAll(handles);
        if (!before.equals(documentTabs.active())) {
            if (documentTabs.active().isPresent()) activateDocumentView(documentTabs.active().orElseThrow());
            else {
                activeFormDocument = null;
                resourceDocument = false;
                clearFormFocus();
                cancelWorldInput();
                worldSession.clearFocus();
            }
        }
        if (documentClose != null && !documentCloseCurrent()) {
            if (page == Page.DOCUMENT_CLOSE) hideOverlay();
            else clearDocumentClose();
        }
        reconciledDocumentVersion = model.version();
    }

    private void requestDocumentClose(StudioDocumentTabs.Key key) {
        guardCreation(() -> {
            if (!documentTabs.contains(key)) return;
            String returnFocus = ui.tree.focused().map(ControlTree.Node::id).orElse(key.controlId());
            ui.focus(null);
            cancelWorldInput();
            worldSession.clearFocus();
            cancelDocumentTabDrag();
            documentClose = documentTabs.requestClose(key);
            documentCloseHandle = key.document() == null ? null : listProjection.documents().get(key.document());
            documentCloseSave = null;
            documentCloseObservedState = null;
            documentCloseError = null;
            if (key.document() != null && (model.documentUnsaved(key.document()) || model.documentHasPendingInputs(key.document()))) {
                show(Page.DOCUMENT_CLOSE);
                focusBeforeOverlay = returnFocus;
            } else finishDocumentClose();
        });
    }

    private boolean documentCloseCurrent() {
        return documentTabs.matches(documentClose) && (documentClose.key().document() == null || documentCloseHandle != null && listProjection.documents().get(documentClose.key().document()) == documentCloseHandle);
    }

    private void clearDocumentClose() {
        documentClose = null;
        documentCloseHandle = null;
        documentCloseSave = null;
        documentCloseObservedState = null;
        documentCloseError = null;
    }

    private void finishDocumentClose() {
        if (!documentCloseCurrent()) return;
        var before = documentTabs.active();
        documentTabs.close(documentClose);
        if (page == Page.DOCUMENT_CLOSE) hideOverlay();
        else clearDocumentClose();
        if (!before.equals(documentTabs.active())) {
            if (documentTabs.active().isPresent()) activateDocumentView(documentTabs.active().orElseThrow());
            else {
                ui.focus(null);
                activeFormDocument = null;
                resourceDocument = false;
                clearFormFocus();
                cancelWorldInput();
                worldSession.clearFocus();
            }
        }
        focusBeforeOverlay = documentTabs.active().map(StudioDocumentTabs.Key::controlId).orElse("document/new");
        restoreFocus = true;
        request();
    }

    private void beginDocumentCloseSave() {
        if (!documentCloseCurrent()) return;
        documentCloseError = null;
        documentCloseObservedState = null;
        documentCloseSave = null;
        try {
            documentCloseSave = model.saveDocument(documentClose.key().document());
        } catch (RuntimeException failure) {
            model.report(failure);
            documentCloseError = model.problems().isEmpty() ? c("failed") : component(model.problems().get(0).message());
        }
        request();
    }

    private void discardAndCloseDocument() {
        if (!documentCloseCurrent()) return;
        UUID document = documentClose.key().document();
        try {
            model.discardDocumentChanges(document);
            fieldErrors.keySet().removeIf(address -> address.document().equals(document));
            for (var workspace : workspaces.workspaces()) for (var panel : workspace.panels()) ui.releaseText("field/" + panel.id() + "/" + document + "/");
            ui.releaseText("field/form/" + document + "/");
            gridSelections.keySet().removeIf(key -> key.startsWith(document + "/"));
            gridSwapSources.keySet().removeIf(key -> key.startsWith(document + "/"));
            gridGroupRaw.keySet().removeIf(key -> key.startsWith(document + "/"));
            ui.releaseText("studio/grid/group/" + document + "/");
            finishDocumentClose();
        } catch (RuntimeException failure) {
            model.report(failure);
            documentCloseError = model.problems().isEmpty() ? c("failed") : component(model.problems().get(0).message());
            request();
        }
    }

    private void pollDocumentClose() {
        if (documentClose == null) return;
        if (!documentCloseCurrent()) {
            if (page == Page.DOCUMENT_CLOSE) hideOverlay();
            else clearDocumentClose();
            return;
        }
        if (documentCloseSave == null) return;
        var state = model.documentSaveState(documentCloseSave);
        if (state.isEmpty()) {
            documentCloseSave = null;
            documentCloseObservedState = null;
            documentCloseError = c("missing_target");
            request();
            return;
        }
        if (state.get() == documentCloseObservedState) return;
        documentCloseObservedState = state.get();
        if (state.get() == ProjectWorkbench.DocumentSaveState.CONFIRMED) {
            UUID document = documentClose.key().document();
            documentCloseSave = null;
            documentCloseObservedState = null;
            if (!model.documentUnsaved(document) && !model.documentHasPendingInputs(document)) finishDocumentClose();
            else documentCloseError = c("studio_close_changed");
        } else if (state.get() == ProjectWorkbench.DocumentSaveState.FAILED) documentCloseError = model.problems().isEmpty() ? c("failed") : component(model.problems().get(0).message());
        else if (state.get() == ProjectWorkbench.DocumentSaveState.CANCELLED) documentCloseError = c("cancelled");
        else if (state.get() == ProjectWorkbench.DocumentSaveState.UNKNOWN) documentCloseError = c("unknown_close");
        request();
    }

    private void documentCloseDialog() {
        ui.dimOverlay = true;
        var layout = ui.components.confirmationDialog("document-close", documentCloseConfirmation(), new ControlTree.Bounds(0, 0, width, height), layer, dialogScroll, value -> {
            dialogScroll = value;
            request();
        });
        dx = layout.bounds().x();
        dy = layout.bounds().y();
        dw = layout.bounds().width();
        dh = layout.bounds().height();
        dialogMaxScroll = layout.maximumScroll();
        dialogScroll = layout.clampScroll(dialogScroll);
        confirmationFocus = layout.initialFocus();
    }

    private Component documentCloseStatus() {
        if (documentCloseSave == null) return c("ready");
        return model.documentSaveState(documentCloseSave).map(state -> c(switch (state) {
            case PREPARING -> "preparing";
            case PUBLISHING -> "publishing";
            case CONFIRMED -> "saved";
            case CANCELLED -> "cancelled";
            case FAILED -> "failed";
            case UNKNOWN -> "unknown";
        })).orElse(c("missing_target"));
    }

    private StudioComponents.Confirmation documentCloseConfirmation() {
        boolean target = documentCloseCurrent(), pending = target && model.documentHasPendingInputs(documentClose.key().document());
        String reason = !target ? "missing_target" : model.busy() ? "busy" : model.unresolvedSave() ? "unknown_close" : model.protectedMode() ? "protected_status" : pending ? "pending_inputs" : "";
        String disabledReason = reason.isEmpty() ? "" : c(reason).getString();
        var messages = new ArrayList<StudioComponents.ConfirmationText>();
        if (target) {
            UUID document = documentClose.key().document();
            messages.add(new StudioComponents.ConfirmationText(c("studio_close_document_hint", documentCloseHandle.displayName()).getString(), Variant.DEFAULT));
            messages.add(new StudioComponents.ConfirmationText(c(model.documentHasSavedBaseline(document) ? "studio_close_saved_baseline" : "studio_close_initial_baseline").getString(), Variant.MUTED));
            if (pending) messages.add(new StudioComponents.ConfirmationText(c("studio_close_pending_hint").getString(), Variant.DANGER));
        } else messages.add(new StudioComponents.ConfirmationText(c("missing_target").getString(), Variant.DANGER));
        Component status = documentCloseStatus();
        if (documentCloseSave != null) messages.add(new StudioComponents.ConfirmationText(status.getString(), Variant.MUTED));
        if (documentCloseError != null && !documentCloseError.getString().equals(status.getString())) messages.add(new StudioComponents.ConfirmationText(documentCloseError.getString(), Variant.DANGER));
        boolean discardEnabled = target && !model.busy() && !model.unresolvedSave() && !model.protectedMode(), saveEnabled = target && !model.busy() && !model.protectedMode() && !pending;
        var cancel = studioAction("document-close/cancel", c("cancel"), Icon.NONE, Variant.DEFAULT, true, false, this::cancelOverlay);
        var discard = new StudioComponents.Action("document-close/discard", c("studio_close_discard").getString(), Icon.NONE, Variant.DEFAULT, discardEnabled, false, discardEnabled ? "" : disabledReason, () -> run(this::discardAndCloseDocument));
        var save = model.unresolvedSave() ? new StudioComponents.Action("document-close/save", c("reconcile").getString(), Icon.NONE, Variant.PRIMARY, !model.busy(), false, model.busy() ? c("busy").getString() : "", () -> run(model::reconcile)) :
                new StudioComponents.Action("document-close/save", c("studio_close_save").getString(), Icon.NONE, Variant.PRIMARY, saveEnabled, false, saveEnabled ? "" : disabledReason, () -> run(this::beginDocumentCloseSave));
        return new StudioComponents.Confirmation(c("studio_close_document_title").getString(), messages, studioAction("document-close/close", c("cancel"), Icon.CLOSE, Variant.DEFAULT, true, false, this::cancelOverlay), List.of(cancel, discard, save));
    }

    private Optional<UUID> currentOwnedDocument() {
        return documentTabs.activeOwner().filter(listProjection.documents()::containsKey);
    }

    private boolean canSaveCurrentDocument() {
        return currentOwnedDocument().isPresent() && !model.busy() && !model.unresolvedSave() && !model.protectedMode();
    }

    private void saveCurrentDocument() {
        currentOwnedDocument().ifPresent(document -> {
            ui.focus(null);
            model.saveDocument(document);
        });
    }

    private void openDocumentForms(UUID document, int x, int y) {
        var forms = documentForms(document);
        if (forms.size() == 1) {
            openFormDocument(document, forms.get(0));
            return;
        }
        formMenuDocument = document;
        menuAnchor = x;
        menuY = y;
        show(Page.DOCUMENT_FORMS);
    }

    private void documentFormsMenu() {
        var entries = documentForms(formMenuDocument).stream().map(form -> studioAction("document-form-menu/" + form.id(), component(form.label()), Icon.SLIDERS, Variant.DEFAULT, true, false, () -> {
            hideOverlay();
            openFormDocument(formMenuDocument, form);
        })).toList();
        ui.components.menu("document-form-menu", entries, new ControlTree.Bounds(dx + 8, dy + 40, dw - 16, entries.size() * 25), layer);
    }

    private void formDocument(StudioFormDocument.Key key, int x, int y, int w, int h) {
        var contribution = documentForms(key.document()).stream().filter(form -> form.id().equals(key.form())).findFirst();
        if (contribution.isEmpty()) {
            var empty = ui.view.column(key.controlId() + "/missing", Style.defaults().padding(Insets.all(8)).gap(8), List.of(
                    ui.view.button(studioAction(key.controlId() + "/back", c("studio_form_return_scene"), Icon.PREVIOUS, Variant.DEFAULT, true, false, () -> openSceneDocument(key.document()))),
                    ui.view.paragraph(key.controlId() + "/missing/message", c("missing_target").getString())));
            ui.emit(ui.view.layoutContent(empty, x, y, w), layer);
            return;
        }
        var form = contribution.orElseThrow();
        var state = formDocuments.computeIfAbsent(key, ignored -> new StudioFormDocument());
        var pages = StudioFormDocument.pages(form, model.fields(key.document(), form.target()));
        var anchor = focusFormField != null ? focusFormField : revealFormField;
        boolean reveal = anchor != null && anchor.document().equals(key.document()) && anchor.node().equals(form.target());
        if (reveal && anchor.equals(focusFormField)) state.reveal(pages, anchor.property(), true);
        String selectedPage = state.page(pages), id = key.controlId();
        var navigation = pages.stream().map(candidate -> studioAction(id + "/page/" + candidate.id(), component(candidate.label()), Icon.NONE, Variant.DEFAULT, true, candidate.id().equals(selectedPage), () -> {
            clearFormFocus();
            state.selectPage(candidate.id());
            request();
        })).toList();
        var body = ui.components.formDocument(id, List.of(model.projectName(), listProjection.documents().get(key.document()).displayName(), component(form.label()).getString()), component(form.label()).getString(),
                studioAction(id + "/back", c("studio_form_return_scene"), Icon.PREVIOUS, Variant.DEFAULT, true, false, () -> openSceneDocument(key.document())), navigation,
                c("search_fields").getString(), state.query(), value -> {
                    clearFormFocus();
                    state.query(value);
                    request();
                }, new ControlTree.Bounds(x, y, w, h), layer);
        var content = new ArrayList<StudioView.Element>();
        int columns = body.width() >= studioUnits(600) ? 2 : 1;
        var selected = pages.stream().filter(candidate -> candidate.id().equals(selectedPage)).findFirst();
        if (selected.isPresent()) for (var group : state.groups(selected.orElseThrow(), text -> component(text).getString())) {
            String groupId = id + "/page/" + selectedPage + "/group/" + group.id();
            var children = new ArrayList<StudioView.Element>();
            if (!component(group.label()).getString().isBlank()) children.add(ui.view.button(studioAction(groupId, component(group.label()), state.collapsed(group.id()) ? Icon.CHEVRON_RIGHT : Icon.CHEVRON_DOWN, Variant.DEFAULT, true, false, () -> {
                clearFormFocus();
                state.toggle(group.id());
                request();
            })).style(Style.defaults()));
            if (!state.collapsed(group.id())) {
                var cells = new ArrayList<StudioView.Element>();
                int cluster = 0;
                for (var row : group.rows()) {
                    var prepared = prepareFieldRow(groupId, row, key.document(), form.target(), address -> revealFormField = address);
                    var field = ui.view.fields(prepared.id(), prepared.fields(), prepared.actions());
                    if (row.fields().size() > 1) {
                        if (!cells.isEmpty()) {
                            children.add(ui.view.grid(groupId + "/grid/" + cluster++, Style.defaults().columns(columns).gap(8), List.copyOf(cells)));
                            cells.clear();
                        }
                        children.add(field);
                    } else cells.add(field);
                }
                if (!cells.isEmpty()) children.add(ui.view.grid(groupId + "/grid/" + cluster, Style.defaults().columns(columns).gap(8), cells));
            }
            content.add(ui.view.column(groupId + "/section", Style.defaults().gap(6), children));
        }
        if (content.isEmpty()) content.add(ui.view.paragraph(id + "/empty", c(state.query().isBlank() ? "no_fields" : "studio_no_matching_fields").getString(), TextSize.BODY, Variant.MUTED));
        var root = ui.view.column(id + "/content", Style.defaults().padding(Insets.all(8)).gap(12), content);
        var arranged = ui.view.layoutContent(root, 0, 0, Math.max(0, body.width() - 8));
        int contentHeight = arranged.contentHeight(), offset = Math.min(state.offset(), Math.max(0, contentHeight - body.height()));
        for (var entry : formFieldControls.entrySet()) if (arranged.geometry().byId().containsKey(entry.getKey())) {
            if (reveal && entry.getValue().equals(anchor)) {
                var control = arranged.bounds(entry.getKey());
                offset = StudioFormDocument.revealOffset(offset, control.y(), control.height(), body.height(), contentHeight);
            }
            if (focusFormField != null && entry.getValue().equals(focusFormField)) pendingFormFocus = entry.getKey();
        }
        state.offset(offset);
        ui.components.scrollContent(id + "/page/" + selectedPage, body, contentHeight, layer, offset, value -> {
            state.offset(value);
            request();
        }, area -> ui.emit(arranged.translated(area.x(), area.y()), layer));
        if (reveal) revealFormField = null;
    }

    private void revealProblemField(ProjectWorkbench.Address address) {
        guardCreation(() -> {
            var field = model.fields(address.document(), address.node()).stream().filter(candidate -> candidate.property().equals(address.property())).findFirst();
            var matching = documentForms(address.document()).stream().filter(form -> form.target().equals(address.node()) && field.map(value -> form.pageIds().contains(value.presentation().map(DocumentEditor.Presentation::pageId).orElse("overview"))).orElse(false)).toList();
            if (!matching.isEmpty()) {
                var target = matching.stream().filter(form -> activeFormDocument != null && activeFormDocument.document().equals(address.document()) && activeFormDocument.form().equals(form.id())).findFirst().orElse(matching.get(0));
                openFormDocument(address.document(), target);
                revealFormField = address;
                focusFormField = address;
            } else {
                model.selectDocument(address.document());
                selectObject(address.document(), address.node(), false);
                revealField = address;
                openInspector(address.document());
            }
        });
    }

    private int menu(int x, String key, Page next) {
        int size = Math.max(34, Math.min(70, font.width(c(key)) + 14));
        action("menu/" + key, c(key), x, 5, size, () -> {
            if (next == Page.ADD_MENU) {
                openAdd(x, MENU_BOTTOM);
                return;
            }
            menuAnchor = x;
            menuY = MENU_BOTTOM;
            show(next);
        });
        return x + size + 1;
    }

    private void openCommands() {
        commandSearch = "";
        ui.releaseText("studio/command-search");
        show(Page.COMMANDS);
    }

    private List<StudioCommand> studioCommands() {
        return List.of(
                new StudioCommand("new_project", () -> {
                    creating = true;
                    show(Page.PROJECT);
                }, !model.hasProject() && !model.pendingCreation()),
                new StudioCommand("open_project", () -> {
                    creating = false;
                    show(Page.PROJECT);
                }, !model.hasProject() && !model.pendingCreation()),
                new StudioCommand("new_document", () -> show(Page.NEW_DOCUMENT), model.hasProject()),
                new StudioCommand("save_document", this::saveCurrentDocument, canSaveCurrentDocument()),
                new StudioCommand("save_all", model::saveAll, model.hasProject() && !model.busy() && !model.unresolvedSave()),
                new StudioCommand("undo", model::undo, model.canUndo()), new StudioCommand("redo", model::redo, model.canRedo()),
                new StudioCommand("resources", () -> activateBottom(WorkspaceTemplates.RESOURCES), model.hasProject()),
                new StudioCommand("problems", () -> activateBottom(WorkspaceTemplates.PROBLEMS), true),
                new StudioCommand("tasks", () -> activateBottom(WorkspaceTemplates.TASKS), true),
                new StudioCommand("test_workflow", this::openTestWorkflow, model.hasProject()),
                new StudioCommand(projectWorldActionKey(), this::openAuthoringWorld, !projectWorld.busy()),
                new StudioCommand("workspace_manage", this::manageWorkspaces, true),
                new StudioCommand("layout_restore", () -> guardCreation(this::restoreLayout), activeWorkspace().isPresent()));
    }

    private void commandDialog() {
        ui.edit("studio/command-search", c("studio_commands"), commandSearch, dx + 14, dy + 40, dw - 28, layer, true, value -> commandSearch = value);
        String query = commandSearch.strip().toLowerCase(Locale.ROOT);
        int y = dy + 70;
        for (var command : studioCommands()) if (query.isEmpty() || c(command.key()).getString().toLowerCase(Locale.ROOT).contains(query)) {
            action("studio/command/" + command.key(), c(command.key()), dx + 14, y, dw - 28, () -> {
                hideOverlay();
                command.action().run();
            }, command.enabled(), "missing_target");
            y += 27;
        }
    }

    private void allMenus() {
        Page[] pages = { Page.PROJECT_MENU, Page.EDIT_MENU, Page.ADD_MENU, Page.VIEW_MENU, Page.WINDOW_MENU, Page.TOOLS_MENU, Page.HELP_MENU };
        String[] keys = { "project", "edit_menu", "add_menu", "view_menu", "window_menu", "tools_menu", "help_menu" };
        var entries = new ArrayList<StudioComponents.Action>();
        for (int i = 0; i < pages.length; i++) {
            Page next = pages[i];
            entries.add(menuAction("all-menu/" + keys[i], keys[i], Icon.NONE, true, "", () -> {
                if (next == Page.ADD_MENU) openAdd(dx, dy);
                else show(next);
            }));
        }
        ui.components.menu("all-menu", entries, new ControlTree.Bounds(dx + 8, dy + 40, dw - 16, entries.size() * 25), layer);
    }

    private void welcome(int y, int h) {
        ui.panel("panel/welcome", 6, y, width - 12, h, 0);
        label(c("welcome"), 22, y + 18, width - 44);
        label(c(model.pendingCreation() ? "unknown_close" : "project_intro"), 22, y + 38, width - 44);
        action("project/new", c("new_project"), 22, y + 62, 134, () -> {
            creating = true;
            show(Page.PROJECT);
        }, !model.pendingCreation(), "unknown_close");
        action("project/open", c("open_project"), 164, y + 62, 134, () -> {
            creating = false;
            show(Page.PROJECT);
        }, !model.pendingCreation(), "unknown_close");
    }

    private boolean widePanels() {
        return width > studioUnits(800);
    }

    private int leftWidth() {
        return studioUnits(260);
    }

    private int rightWidth() {
        return studioUnits(290);
    }

    private void editorPanels(int y, int h) {
        var workspace = workspaces.workspaces().stream().filter(w -> workspaces.activeWorkspace().filter(w.id()::equals).isPresent()).findFirst().orElse(null);
        if (workspace == null) {
            label(c("no_workspace"), 18, y + 20, width - 36);
            return;
        }
        workspace.panels().forEach(panel -> hydratePanelView(workspace, panel));
        List<PanelInstance> panels = workspace.panels().stream().filter(panel -> !panelHidden(panel)).toList();
        if (panels.isEmpty()) {
            label(c("no_panels"), 18, y + 20, width - 36);
            return;
        }
        if (widePanels()) for (var item : panels) if (!item.type().equals(WorkspaceTemplates.VIEWPORT)) dockSources.add(new StudioDocking.Source<>(item.id(), "dock-tab/" + item.id(), dockGroupName(item)));
        if (drawer >= 0 && drawerPanelIdentity != null) {
            drawer = -1;
            for (int i = 0; i < panels.size(); i++) if (panels.get(i).id().equals(drawerPanelIdentity)) {
                drawer = i;
                break;
            }
        }
        var maximized = panels.stream().filter(panel -> Boolean.parseBoolean(panel.viewState().getOrDefault("studio.maximized", "false"))).findFirst();
        if (maximized.isPresent() && !panelDocking.moving()) {
            drawer = -1;
            var item = maximized.orElseThrow();
            if (widePanels() && !item.type().equals(WorkspaceTemplates.VIEWPORT)) {
                var header = new ControlTree.Bounds(TOOL_RAIL, y, width - TOOL_RAIL, Math.min(24, h));
                panel(item, TOOL_RAIL, y + header.height(), width - TOOL_RAIL, Math.max(0, h - header.height()), false);
                var title = studioAction("dock-tab/" + item.id(), panelLabel(item.type()), Icon.NONE, Variant.DEFAULT, true, true, () -> focusPanel(item.id()));
                ui.components.dockHeader("dock-max/" + item.id(), header, layer, List.of(title), panelActions(item, header.x(), header.y(), header.width()), null, 0);
            } else panel(item, TOOL_RAIL, y, width - TOOL_RAIL, h, true);
            return;
        }
        if (widePanels()) {
            drawer = -1;
            dockedPanels(workspace, y, h);
            return;
        }
        int center = -1;
        for (int i = 0; i < panels.size(); i++) if (panels.get(i).type().equals(WorkspaceTemplates.VIEWPORT)) center = i;
        var panelTabs = new ArrayList<StudioComponents.Action>();
        for (int i = 0; i < panels.size(); i++) {
            int target = i;
            var panel = panels.get(i);
            panelTabs.add(studioAction("panel-select/" + panel.id(), panelLabel(panel.type()), Icon.NONE, Variant.DEFAULT, true, drawer == i, () -> {
                drawer = drawer == target ? -1 : target;
                drawerPanelIdentity = panel.id();
                compactPanels.put(workspace.id(), target);
            }));
        }
        int first = ui.components.tabs("panel-select", new ControlTree.Bounds(TOOL_RAIL, y, width - TOOL_RAIL, 24), layer, panelTabs, false, compactPanels.getOrDefault(workspace.id(), 0));
        compactPanels.put(workspace.id(), first);
        if (center >= 0 && drawer != center) panel(panels.get(center), TOOL_RAIL, y + 24, width - TOOL_RAIL, Math.max(20, h - 24), true);
        if (drawer >= 0 && drawer < panels.size()) {
            layer = 1;
            ui.dimOverlay = true;
            int w = Math.min(width - 36, panels.get(drawer).type().equals(WorkspaceTemplates.INSPECTOR) ? 300 : 240), x = panels.get(drawer).type().equals(WorkspaceTemplates.INSPECTOR) ? width - w : 0;
            boolean fullHeight = h - 24 < 300;
            int top = fullHeight ? 0 : y + 24, drawerHeight = fullHeight ? height - 20 : Math.max(20, h - 24);
            drawerBounds = new ControlTree.Bounds(x, top, w, drawerHeight);
            panel(panels.get(drawer), x, top, w, drawerHeight, true);
            layer = 0;
        }
    }

    private Optional<WorkspaceInstance> activeWorkspace() {
        return workspaces.workspaces().stream().filter(w -> workspaces.activeWorkspace().filter(w.id()::equals).isPresent()).findFirst();
    }

    private void activateBottom(EditorId type) {
        activeWorkspace().ifPresent(workspace -> {
            var existing = workspace.panels().stream().filter(panel -> panel.type().equals(type)).findFirst();
            if (existing.isPresent()) focusPanel(existing.get().id());
            else if (canAddPanel(type)) focusPanel(workspaces.addPanel(workspace.id(), type).id());
        });
        request();
    }

    private UUID dockId(UUID workspace, String name) {
        return UUID.nameUUIDFromBytes((workspace + "/" + name).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private DockNode dockGroup(WorkspaceInstance workspace, String name, List<PanelInstance> panels) {
        if (panels.isEmpty()) return panelDocking.moving() && StudioDocking.Zone.fromKey(name).isPresent() ? new DockNode.Tabs(dockId(workspace.id(), name), List.of(emptyDockId(workspace.id(), name)), emptyDockId(workspace.id(), name)) : new DockNode.Empty();
        if (panels.size() == 1 && panels.get(0).type().equals(WorkspaceTemplates.VIEWPORT)) return new DockNode.Leaf(panels.get(0).id());
        var ids = panels.stream().map(PanelInstance::id).toList();
        UUID selected = dockTabs.get(dockId(workspace.id(), name));
        if (!ids.contains(selected)) selected = ids.get(0);
        dockTabs.put(dockId(workspace.id(), name), selected);
        return new DockNode.Tabs(dockId(workspace.id(), name), ids, selected);
    }

    private UUID emptyDockId(UUID workspace, String name) {
        return dockId(workspace, "empty/" + name);
    }

    private void dockedPanels(WorkspaceInstance workspace, int y, int h) {
        var leftBottom = new ArrayList<PanelInstance>();
        var leftTop = new ArrayList<PanelInstance>();
        var right = new ArrayList<PanelInstance>();
        var center = new ArrayList<PanelInstance>();
        var bottom = new ArrayList<PanelInstance>();
        for (var panel : workspace.panels()) {
            if (panelHidden(panel)) continue;
            switch (dockGroupName(panel)) {
                case "leftTop" -> leftTop.add(panel);
                case "leftBottom" -> leftBottom.add(panel);
                case "right" -> right.add(panel);
                case "bottom" -> bottom.add(panel);
                default -> center.add(panel);
            }
        }
        int total = width - TOOL_RAIL;
        boolean moving = panelDocking.moving();
        DockNode middle = dockGroup(workspace, "center", center);
        UUID activeBottom = dockTabs.get(dockId(workspace.id(), "bottom"));
        var bottomPanel = bottom.stream().filter(panel -> panel.id().equals(activeBottom)).findFirst().orElse(bottom.isEmpty() ? null : bottom.get(0));
        boolean compactBottom = !moving && bottomPanel != null && bottomPanel.type().equals(WorkspaceTemplates.PROBLEMS) && model.problems().isEmpty() && fieldErrors.isEmpty() && MinecraftWorkbenchPreferences.diagnostic().isEmpty() && layoutProblem().isEmpty();
        int bottomHeight = bottom.isEmpty() ? studioUnits(145) : compactBottom ? studioUnits(32) : panelMeasure(bottom.get(0), "dock.height", studioUnits(145), 60, Math.max(60, h - 100));
        if (!bottom.isEmpty() || moving) middle = new DockNode.Split(dockId(workspace.id(), "bottom-split"), DockNode.Axis.VERTICAL, DockSizing.trailing(h, bottomHeight, 2), middle, dockGroup(workspace, "bottom", bottom));
        var leftAll = new ArrayList<>(leftTop);
        leftAll.addAll(leftBottom);
        int lw = leftAll.isEmpty() ? (moving ? leftWidth() : 0) : panelMeasure(leftAll.get(0), "dock.width", leftWidth(), 110, total / 2), rw = right.isEmpty() ? rightWidth() : panelMeasure(right.get(0), "dock.width", rightWidth(), 140, Math.max(140, total / 2));
        if (!right.isEmpty() || moving) middle = new DockNode.Split(dockId(workspace.id(), "right-split"), DockNode.Axis.HORIZONTAL, DockSizing.trailing(Math.max(0, total - lw - 4d), rw, 2), middle, dockGroup(workspace, "right", right));
        if (!leftAll.isEmpty() || moving) {
            DockNode leftNode = leftTop.isEmpty() && !moving ? dockGroup(workspace, "leftBottom", leftBottom) : dockGroup(workspace, "leftTop", leftTop);
            if (!leftTop.isEmpty() && !leftBottom.isEmpty() || moving) {
                int topHeight = leftTop.isEmpty() ? studioUnits(205) : panelMeasure(leftTop.get(0), "dock.stack-height", studioUnits(205), 80, Math.max(80, h - 110));
                leftNode = new DockNode.Split(dockId(workspace.id(), "left-stack-split"), DockNode.Axis.VERTICAL, DockSizing.leading(h, topHeight, 3), leftNode, dockGroup(workspace, "leftBottom", leftBottom));
            }
            middle = new DockNode.Split(dockId(workspace.id(), "left-split"), DockNode.Axis.HORIZONTAL, DockSizing.leading(total, lw, 3), leftNode, middle);
        }
        var groups = new LinkedHashMap<String, List<PanelInstance>>();
        groups.put("leftTop", leftTop);
        groups.put("leftBottom", leftBottom);
        groups.put("right", right);
        groups.put("bottom", bottom);
        var sizes = new LinkedHashMap<UUID, PanelSize>();
        for (var p : workspace.panels()) if (!panelHidden(p)) sizes.put(p.id(), new PanelSize(p.type().equals(WorkspaceTemplates.INSPECTOR) ? 140 : p.type().equals(WorkspaceTemplates.OBJECTS) ? 110 : 80, compactBottom && bottom.contains(p) ? 0 : panelMinimumHeight(p.type())));
        if (moving) groups.forEach((name, panels) -> { if (panels.isEmpty()) sizes.put(emptyDockId(workspace.id(), name), new PanelSize(80, 45)); });
        var projection = DockSolver.solve(new DockLayout(middle), new Rect(TOOL_RAIL, y, total, h), sizes, studioUnits(5), studioUnits(32));
        Map<UUID, PanelInstance> byId = new HashMap<>();
        workspace.panels().forEach(p -> byId.put(p.id(), p));
        Set<UUID> tabbed = new HashSet<>();
        for (var group : projection.tabs()) tabbed.addAll(group.panels());
        for (var box : projection.panels()) if (box.visible() && byId.containsKey(box.panelId())) {
            var r = box.bounds();
            panel(byId.get(box.panelId()), (int) r.x(), (int) r.y(), (int) r.width(), (int) r.height(), !tabbed.contains(box.panelId()));
        }
        for (var split : projection.splitters()) {
            String side = split.nodeId().equals(dockId(workspace.id(), "left-split")) ? "left" : split.nodeId().equals(dockId(workspace.id(), "left-stack-split")) ? "leftTop" : split.nodeId().equals(dockId(workspace.id(), "right-split")) ? "right" : "bottom";
            var choices = side.equals("left") ? leftAll : side.equals("leftTop") ? leftTop : side.equals("right") ? right : bottom;
            if (choices.isEmpty()) continue;
            var r = split.bounds();
            var bounds = new ControlTree.Bounds((int) r.x(), (int) r.y(), Math.max(4, (int) r.width()), Math.max(4, (int) r.height()));
            dockGrips.add(new DockGrip(workspace.id(), choices.get(0).id(), side, bounds));
            ui.rectangle("dock-split/" + split.nodeId(), (int) r.x(), (int) r.y(), Math.max(1, (int) r.width()), Math.max(1, (int) r.height()), 0, ui.theme.border());
        }
        for (var group : projection.tabs()) {
            var r = group.header();
            var header = dockBounds(r);
            groups.keySet().stream().filter(name -> group.nodeId().equals(dockId(workspace.id(), name))).findFirst().ifPresent(name -> {
                var body = projection.panels().stream().filter(box -> box.panelId().equals(group.active())).findFirst().orElseThrow().bounds();
                dockRegions.add(new StudioDocking.Region(StudioDocking.Zone.fromKey(name).orElseThrow(), StudioDocking.regionBounds(header, dockBounds(body)), groups.get(name).isEmpty()));
            });
            var tabs = new ArrayList<StudioComponents.Action>();
            for (var id : group.panels()) {
                var item = byId.get(id);
                if (item == null) continue;
                Component caption = panelLabel(item.type());
                if (workspace.panels().stream().filter(other -> other.type().equals(item.type())).count() > 1) caption = caption.copy().append(" " + (workspace.panels().indexOf(item) + 1));
                tabs.add(studioAction("dock-tab/" + id, caption, Icon.NONE, Variant.DEFAULT, true, group.active().equals(id), () -> dockTabs.put(group.nodeId(), id)));
            }
            var active = byId.get(group.active());
            String source = moving ? panelDocking.source().map(StudioDocking.Source::controlId).orElse(null) : null;
            ui.components.dockHeader("dock-tabs/" + group.nodeId(), header, layer, tabs, active == null ? List.of() : panelActions(active, header.x(), header.y(), header.width()), source, 0);
        }
        if (moving) for (var region : dockRegions) ui.components.dockTarget("dock-target/" + region.zone().key(), region, layer, panelDocking.target().filter(region.zone()::equals).isPresent(), c("studio_dock_empty").getString());
    }

    private int panelMinimumHeight(EditorId type) {
        if (!type.equals(WorkspaceTemplates.OBJECTS) && !type.equals(WorkspaceTemplates.PROJECT)) return 45;
        int count = type.equals(WorkspaceTemplates.OBJECTS) ? 4 : 3;
        var rows = new ArrayList<StudioView.Element>();
        for (int i = 0; i < count; i++) rows.add(ui.view.placeholder("minimum/" + i, Style.defaults().height(Length.fixed(StudioMetrics.compact().row()))));
        if (type.equals(WorkspaceTemplates.OBJECTS)) rows.add(ui.view.text("minimum/range", "", TextSize.SMALL, Variant.MUTED));
        return ui.view.measure(ui.view.column("minimum", Style.defaults().padding(Insets.all(6)).gap(4), rows), new FlowLayout.Constraints(Integer.MAX_VALUE, Integer.MAX_VALUE)).height();
    }

    private ControlTree.Bounds dockBounds(Rect bounds) {
        return new ControlTree.Bounds((int) bounds.x(), (int) bounds.y(), (int) bounds.width(), (int) bounds.height());
    }

    private int panelMeasure(PanelInstance panel, String key, int fallback, int minimum, int maximum) {
        try {
            return Math.max(minimum, Math.min(maximum, Integer.parseInt(panel.viewState().getOrDefault(key, Integer.toString(fallback)))));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private void resizeDock(DockGrip grip, double x, double y) {
        var workspace = activeWorkspace().filter(w -> w.id().equals(grip.workspace())).orElse(null);
        if (workspace == null) return;
        int value = (int) Math.round(grip.side().equals("left") ? x - TOOL_RAIL : grip.side().equals("right") ? width - x : grip.side().equals("leftTop") ? y - BODY_TOP : height - STATUS_HEIGHT - y);
        String key = grip.side().equals("bottom") ? "dock.height" : grip.side().equals("leftTop") ? "dock.stack-height" : "dock.width";
        for (var panel : workspace.panels()) if (grip.side().equals("left") ? Set.of("leftTop", "leftBottom").contains(dockGroupName(panel)) : dockGroupName(panel).equals(grip.side())) {
            var state = new HashMap<>(panel.viewState());
            state.put(key, Integer.toString(value));
            workspaces.setPanelState(workspace.id(), panel.id(), state);
        }
        request();
    }

    private String dockGroupName(PanelInstance panel) {
        if (panel.type().equals(WorkspaceTemplates.VIEWPORT)) return "center";
        String saved = panel.viewState().get("dock.group");
        if ("left".equals(saved)) return panel.type().equals(WorkspaceTemplates.PROJECT) || panel.type().equals(WorkspaceTemplates.RESOURCES) ? "leftTop" : "leftBottom";
        return Set.of("leftTop", "leftBottom", "right", "center", "bottom").contains(saved == null ? "" : saved) ? saved : dockGroupName(panel.type());
    }

    private String tabGroupName(PanelInstance panel) {
        return dockGroupName(panel);
    }

    private String dockGroupName(EditorId type) {
        return type.equals(WorkspaceTemplates.PROJECT) || type.equals(WorkspaceTemplates.RESOURCES) ? "leftTop" : type.equals(WorkspaceTemplates.OBJECTS) ? "leftBottom" : type.equals(WorkspaceTemplates.INSPECTOR) ? "right" : type.equals(WorkspaceTemplates.TASKS) || type.equals(WorkspaceTemplates.PROBLEMS) ? "bottom" : "center";
    }

    private boolean panelHidden(PanelInstance panel) {
        return Boolean.parseBoolean(panel.viewState().getOrDefault("studio.hidden", "false"));
    }

    private void panelState(UUID workspace, PanelInstance panel, String key, boolean value) {
        var state = new HashMap<>(panel.viewState());
        state.put(key, Boolean.toString(value));
        workspaces.setPanelState(workspace, panel.id(), state);
        request();
    }

    private void hidePanel(UUID panelId) {
        guardCreation(() -> activeWorkspace().ifPresent(workspace -> {
            workspace.panels().stream().filter(panel -> panel.id().equals(panelId)).findFirst().ifPresent(panel -> {
                panelState(workspace.id(), panel, "studio.hidden", true);
                var current = activeWorkspace().orElseThrow().panels().stream().filter(p -> p.id().equals(panelId)).findFirst().orElseThrow();
                panelState(workspace.id(), current, "studio.maximized", false);
            });
            drawer = -1;
            request();
        }));
    }

    private void maximizePanel(UUID panelId) {
        boolean creationTarget = objectCreation != null && panelId.equals(creationPanelTarget);
        Runnable resize = () -> activeWorkspace().ifPresent(workspace -> {
            for (var panel : workspace.panels()) panelState(workspace.id(), panel, "studio.maximized", panel.id().equals(panelId) && !Boolean.parseBoolean(panel.viewState().getOrDefault("studio.maximized", "false")));
            drawer = -1;
            if (creationTarget) focusPanel(panelId);
            request();
        });
        if (creationTarget) resize.run();
        else guardCreation(resize);
    }

    private void panelHeaderActions(PanelInstance panel, int x, int y, int w) {
        var actions = panelActions(panel, x, y, w);
        int right = x + w;
        for (int i = 0; i < actions.size(); i++) {
            var action = actions.get(i);
            ui.components.button(action, new ControlTree.Bounds(right - (actions.size() - i) * 20, y + 2, 20, 20), layer, Kind.ICON);
            ui.detail(action.id(), action.label());
        }
    }

    private List<StudioComponents.Action> panelActions(PanelInstance panel, int x, int y, int w) {
        var actions = new ArrayList<StudioComponents.Action>();
        actions.add(studioAction("panel/menu/" + panel.id(), c("view_menu"), Icon.MORE, Variant.DEFAULT, true, false, () -> openPanelMenu(panel.id(), x + w - 62, y + 24)));
        actions.add(studioAction("panel/maximize/" + panel.id(), c("studio_panel_maximize"), Boolean.parseBoolean(panel.viewState().getOrDefault("studio.maximized", "false")) ? Icon.RESTORE : Icon.MAXIMIZE, Variant.DEFAULT, true, false, () -> maximizePanel(panel.id())));
        if (drawer < 0) actions.add(studioAction("panel/hide/" + panel.id(), c("studio_panel_hide"), Icon.CLOSE, Variant.DEFAULT, true, false, () -> hidePanel(panel.id())));
        else if (layer > 0) actions.add(studioAction("drawer/close", c("cancel"), Icon.CLOSE, Variant.DEFAULT, true, false, () -> {
            drawer = -1;
            request();
        }));
        return actions;
    }

    private void hydratePanelView(WorkspaceInstance workspace, PanelInstance panel) {
        if (hydratedPanels.contains(panel.id()) || invalidPanelViews.contains(panel.id())) return;
        try {
            var decoded = StudioPanelViewState.decode(panel.viewState());
            if (decoded.isEmpty()) {
                hydratedPanels.add(panel.id());
                return;
            }
            JsonObject state = decoded.orElseThrow();
            String prefix = panel.id() + "/";
            readViewNumbers(state, "objectContextScroll", prefix, objectContextScroll);
            readViewNumbers(state, "fieldContextScroll", prefix, fieldContextScroll);
            objectContextScroll.replaceAll((key, value) -> key.startsWith(prefix) ? StudioPanelViewState.listScrollOffset(state, value, 22, 78) : value);
            readViewStrings(state, "resourceVersions", prefix, resourceVersions);
            readViewStrings(state, "objectSearch", prefix, objectSearch);
            readViewSets(state, "objectCollapsed", prefix, collapsedObjects);
            collapsedObjects.replaceAll((key, value) -> Set.copyOf(value));
            readViewStrings(state, "inspectorPages", prefix, inspectorPages);
            readViewSets(state, "inspectorCollapsed", prefix, inspectorCollapsed);
            objectScrollByPanel.put(panel.id(), state.has("objectScroll") ? StudioPanelViewState.listScrollOffset(state, state.get("objectScroll").getAsInt(), 22, 78) : 0);
            fieldScrollByPanel.put(panel.id(), state.has("fieldScroll") ? Math.max(0, state.get("fieldScroll").getAsInt()) : 0);
            if (state.has("inspectorSearch")) inspectorSearch.put(panel.id(), state.get("inspectorSearch").getAsString());
            if (state.has("resourceSearch")) resourceSearch.put(panel.id(), state.get("resourceSearch").getAsString());
            if (state.has("resourceTab")) resourceTabs.put(panel.id(), state.get("resourceTab").getAsString());
            if (state.has("resourceScroll")) resourceScroll.put(panel.id(), Math.max(0, state.get("resourceScroll").getAsInt()));
            if (state.has("resourceDetailScroll")) resourceDetailScroll.put(panel.id(), Math.max(0, state.get("resourceDetailScroll").getAsInt()));
            if (state.has("task")) selectedTasks.put(panel.id(), state.get("task").getAsString());
            if (state.has("taskScroll")) taskScroll.put(panel.id(), Math.max(0, state.get("taskScroll").getAsInt()));
            if (state.has("resource")) selectedResources.put(panel.id(), UUID.fromString(state.get("resource").getAsString()));
            if (state.has("projectSearch")) projectSearch.put(panel.id(), state.get("projectSearch").getAsString());
            if (state.has("projectScroll")) projectScroll.put(panel.id(), StudioPanelViewState.listScrollOffset(state, state.get("projectScroll").getAsInt(), 22, 54));
            if (state.has("active") && state.get("active").getAsBoolean()) dockTabs.put(dockId(workspace.id(), tabGroupName(panel)), panel.id());
            panelViewExtras.put(panel.id(), state.deepCopy());
            hydratedPanels.add(panel.id());
        } catch (RuntimeException failure) {
            invalidPanelViews.add(panel.id());
            com.mojang.logging.LogUtils.getLogger().warn("Cannot restore editor panel view state", failure);
        }
    }

    private void readViewNumbers(JsonObject state, String name, String prefix, Map<String, Integer> target) {
        if (state.has(name)) for (var entry : state.getAsJsonObject(name).entrySet()) target.put(prefix + entry.getKey(), Math.max(0, entry.getValue().getAsInt()));
    }

    private void readViewStrings(JsonObject state, String name, String prefix, Map<String, String> target) {
        if (state.has(name)) for (var entry : state.getAsJsonObject(name).entrySet()) target.put(prefix + entry.getKey(), entry.getValue().getAsString());
    }

    private void readViewSets(JsonObject state, String name, String prefix, Map<String, Set<String>> target) {
        if (state.has(name)) for (var entry : state.getAsJsonObject(name).entrySet()) {
            Set<String> values = new HashSet<>();
            for (var value : entry.getValue().getAsJsonArray()) values.add(value.getAsString());
            target.put(prefix + entry.getKey(), values);
        }
    }

    private JsonObject viewEntries(Map<String, ?> source, String prefix) {
        JsonObject values = new JsonObject();
        source.forEach((key, value) -> { if (key.startsWith(prefix)) values.add(key.substring(prefix.length()), VIEW_JSON.toJsonTree(value)); });
        return values;
    }

    private void persistPanelViews() {
        activeWorkspace().ifPresent(workspace -> {
            for (var panel : workspace.panels()) {
                if (!hydratedPanels.contains(panel.id())) continue;
                String prefix = panel.id() + "/";
                JsonObject state = panelViewExtras.getOrDefault(panel.id(), new JsonObject()).deepCopy();
                savePanelContext(panel);
                state.add("objectContextScroll", viewEntries(objectContextScroll, prefix));
                state.add("fieldContextScroll", viewEntries(fieldContextScroll, prefix));
                state.addProperty("listScrollVersion", 1);
                state.add("resourceVersions", viewEntries(resourceVersions, prefix));
                state.addProperty("resourceTab", resourceTabs.getOrDefault(panel.id(), "preview"));
                state.add("objectSearch", viewEntries(objectSearch, prefix));
                state.add("objectCollapsed", viewEntries(collapsedObjects, prefix));
                state.add("inspectorPages", viewEntries(inspectorPages, prefix));
                state.add("inspectorCollapsed", viewEntries(inspectorCollapsed, prefix));
                state.addProperty("objectScroll", objectScrollByPanel.getOrDefault(panel.id(), 0));
                state.addProperty("fieldScroll", fieldScrollByPanel.getOrDefault(panel.id(), 0));
                state.addProperty("inspectorSearch", inspectorSearch.getOrDefault(panel.id(), ""));
                state.addProperty("resourceSearch", resourceSearch.getOrDefault(panel.id(), ""));
                state.addProperty("resourceScroll", resourceScroll.getOrDefault(panel.id(), 0));
                state.addProperty("resourceDetailScroll", resourceDetailScroll.getOrDefault(panel.id(), 0));
                state.addProperty("task", selectedTasks.getOrDefault(panel.id(), "project"));
                state.addProperty("taskScroll", taskScroll.getOrDefault(panel.id(), 0));
                state.addProperty("projectSearch", projectSearch.getOrDefault(panel.id(), ""));
                state.addProperty("projectScroll", projectScroll.getOrDefault(panel.id(), 0));
                state.remove("resource");
                if (selectedResources.containsKey(panel.id())) state.addProperty("resource", selectedResources.get(panel.id()).toString());
                state.addProperty("active", panel.id().equals(dockTabs.get(dockId(workspace.id(), tabGroupName(panel)))));
                String encoded = state.toString();
                var chunks = new ArrayList<String>();
                for (int offset = 0; offset < encoded.length();) {
                    int end = Math.min(encoded.length(), offset + 60000);
                    if (end < encoded.length() && Character.isHighSurrogate(encoded.charAt(end - 1)) && Character.isLowSurrogate(encoded.charAt(end))) end--;
                    chunks.add(encoded.substring(offset, end));
                    offset = end;
                }
                // Preserve extension keys and the prior usable snapshot if this cache exceeds its budget.
                if (chunks.size() > 128) {
                    invalidPanelViews.add(panel.id());
                    continue;
                }
                var saved = new HashMap<>(panel.viewState());
                saved.keySet().removeIf(key -> key.equals("ui.parts") || key.matches("ui[.][0-9]+"));
                saved.put("ui.parts", Integer.toString(chunks.size()));
                for (int i = 0; i < chunks.size(); i++) saved.put("ui." + i, chunks.get(i));
                if (saved.size() > 256) {
                    invalidPanelViews.add(panel.id());
                    continue;
                }
                if (!saved.equals(panel.viewState())) workspaces.setPanelState(workspace.id(), panel.id(), saved);
            }
        });
        if (layouts != null) layouts.capture();
    }

    private void viewportPanel(int x, int y, int w, int h) {
        var toolbar = ui.view.layoutContent(viewportToolbar(), x, y, Math.max(0, w));
        ui.emit(toolbar, layer);
        y += toolbar.contentHeight();
        h -= toolbar.contentHeight();
        viewportBounds = new ControlTree.Bounds(x, y, w, Math.max(0, h));
        ui.label("world/viewport-input", Component.empty(), x, y, w, layer);
        if (worldSession.selecting()) {
            worldSelection(x, y, w, h);
            return;
        }
        if (!worldEditingReady() && !worldSession.hasNative()) {
            int cy = y + Math.max(12, h / 2 - 36);
            ui.heading(c("viewport_offline"), x + 12, cy, w - 24, layer);
            paragraph(worldSession.failure().orElse(c("viewport_offline_hint")), x + 12, cy + 20, w - 24, 3);
            action("world/select-center", c(projectWorldActionKey()), x + 12, cy + 65, Math.min(130, w - 24), this::openAuthoringWorld, !projectWorld.busy(), "world_session_busy");
        }
    }

    private StudioView.Element viewportToolbar() {
        var actions = new ArrayList<StudioView.Element>();
        boolean worldReady = worldEditingReady();
        actions.add(ui.view.button(new StudioComponents.Action("world/select", c(projectWorldActionKey()).getString(), Icon.NONE, Variant.DEFAULT, !projectWorld.busy(), false,
                projectWorld.busy() ? c("world_session_busy").getString() : "", () -> run(this::openAuthoringWorld))));
        if (worldSession.canCancel()) actions.add(ui.view.button(studioAction("world/cancel-load", c("world_cancel_load"), Icon.NONE, Variant.DEFAULT, true, false, worldSession::requestCancel)));
        else if (worldReady) actions.add(ui.view.button(studioAction("world/leave", c("world_leave"), Icon.NONE, Variant.DEFAULT, true, false, () -> {
            cancelWorldInput();
            worldTarget.disconnect();
            worldSession.leave();
        })));
        else if (worldSession.loading()) actions.add(ui.view.paragraph("world/loading", c(worldSession.cancellationRequested() ? "world_cancel_pending" : "world_loading").getString(), TextSize.SMALL, Variant.MUTED).style(Style.content()));
        var rows = new ArrayList<StudioView.Element>();
        rows.add(ui.view.wrap("world/actions", Style.defaults().gap(6), actions));
        if (worldReady) {
            var blocker = spatialEditBlocker();
            boolean active = pointPlacement.active(), selected = selectedSpatialPoint(), enabled = active || selected && blocker.isEmpty();
            var area = selectedSpatialArea();
            if (area.isPresent()) {
                var selectedArea = area.get();
                var areaName = model.nodes().stream().filter(node -> node.id().equals(selectedArea.node())).map(node -> component(node.label()).getString()).findFirst().orElse(selectedArea.node());
                rows.add(ui.view.paragraph("world/area-name", areaName, TextSize.SMALL, Variant.DEFAULT));
                var tools = new ArrayList<StudioView.Element>();
                for (var mode : WorkbenchAreaPlacement.Mode.values()) {
                    boolean chosen = areaPlacement.active() && areaPlacement.mode() == mode;
                    tools.add(ui.view.button(new StudioComponents.Action("world/area-" + mode.name().toLowerCase(Locale.ROOT),
                            c(chosen ? "area_edit_cancel" : areaModeKey(mode)).getString(), Icon.NONE, chosen ? Variant.PRIMARY : Variant.DEFAULT, chosen || blocker.isEmpty(), false, chosen ? "" : blocker.map(Component::getString).orElse(""),
                            () -> run(() -> {
                                if (chosen) cancelAreaPlacement();
                                else beginAreaPlacement(mode);
                            }))));
                }
                rows.add(ui.view.wrap("world/area-tools", Style.defaults().gap(6), tools));
                rows.add(ui.view.paragraph("world/area-semantics", areaRules(area.get()).getString(), TextSize.SMALL, Variant.MUTED));
                if (WorkbenchAreaPlacement.emptyPositionVolume(area.get()))
                    rows.add(ui.view.paragraph("world/area-empty", c("area_empty_position").getString(), TextSize.SMALL, Variant.DANGER));
            } else rows.add(ui.view.button(new StudioComponents.Action("world/move-point", c(active ? "point_move_cancel" : "point_move").getString(), Icon.NONE, active ? Variant.PRIMARY : Variant.DEFAULT, enabled, false,
                    enabled ? "" : selected ? blocker.orElseThrow().getString() : c("point_move_select").getString(), () -> run(active ? this::cancelPointPlacement : this::togglePointPlacement))));
            if (selected || area.isPresent()) blocker.ifPresent(reason -> rows.add(ui.view.paragraph("world/edit-blocker", reason.getString(), TextSize.SMALL, Variant.DANGER)));
        }
        return ui.view.column("world/toolbar", Style.defaults().padding(new Insets(0, 6, 6, 6)).gap(6), rows);
    }

    private Optional<Component> spatialEditBlocker() {
        if (model.busy()) return Optional.of(c("busy"));
        if (model.protectedMode() || model.unresolvedSave()) return Optional.of(c("protected_status"));
        var document = model.activeDocument();
        if (document.isEmpty()) return Optional.of(c("no_document"));
        return model.documentHasPendingInputs(document.get()) ? Optional.of(c("point_move_pending")) : Optional.empty();
    }

    private boolean selectedSpatialPoint() {
        UUID document = model.activeDocument().orElse(null);
        if (document == null) return false;
        String node = model.selectedNode(document).orElse(null);
        return node != null && model.spatialPoints(document).stream().anyMatch(point -> point.node().equals(node));
    }

    private void togglePointPlacement() {
        if (pointPlacement.active()) {
            cancelPointPlacement();
            return;
        }
        cancelAreaPlacement();
        ui.focus(null);
        if (!worldEditingReady() || !selectedSpatialPoint()) return;
        UUID document = model.activeDocument().orElseThrow();
        if (spatialEditBlocker().isPresent()) {
            request();
            return;
        }
        if (worldTarget.state() != WorldTargetWorkbench.State.BOUND || !worldTarget.available()) {
            openWorldTarget();
            return;
        }
        pointPlacement.begin(connectedWorldRoot().orElseThrow(), document, model.selectedNode(document).orElseThrow());
        request();
    }

    private Optional<net.ptcrys.fpsmatch.editor.core.editing.SpatialDocument.Area> selectedSpatialArea() {
        var document = model.activeDocument().orElse(null);
        if (document == null) return Optional.empty();
        var node = model.selectedNode(document).orElse(null);
        return model.spatialAreas(document).stream().filter(area -> area.node().equals(node)).findFirst();
    }

    private static String areaModeKey(WorkbenchAreaPlacement.Mode mode) {
        return switch (mode) {
            case FIRST -> "area_edit_first";
            case SECOND -> "area_edit_second";
            case TRANSLATE -> "area_edit_translate";
        };
    }

    private static Component areaRules(net.ptcrys.fpsmatch.editor.core.editing.SpatialDocument.Area area) {
        var result = c("area_endpoint_rule").copy();
        for (var use : net.ptcrys.fpsmatch.editor.core.editing.SpatialDocument.AreaUse.values())
            if (area.semantics().uses().contains(use)) result.append(" · ").append(c("area_use_" + use.name().toLowerCase(Locale.ROOT)));
        return result;
    }

    private void beginAreaPlacement(WorkbenchAreaPlacement.Mode mode) {
        cancelPointPlacement();
        cancelAreaPlacement();
        ui.focus(null);
        if (!worldEditingReady() || selectedSpatialArea().isEmpty()) return;
        var document = model.activeDocument().orElseThrow();
        if (spatialEditBlocker().isPresent()) {
            request();
            return;
        }
        if (worldTarget.state() != WorldTargetWorkbench.State.BOUND || !worldTarget.available()) {
            openWorldTarget();
            return;
        }
        areaPlacement.begin(connectedWorldRoot().orElseThrow(), document, model.selectedNode(document).orElseThrow(), mode);
        request();
    }

    private void renderAreaPlacement(GuiGraphics graphics, int mouseX, int mouseY) {
        if (!areaPlacement.active()) return;
        if (!worldPointSceneExposed() || !areaPlacement.current()) {
            cancelAreaPlacement();
            return;
        }
        double x = mouseX / DENSITY, y = mouseY / DENSITY;
        try {
            areaPlacement.preview(viewport, x, y, placementPointerExposed(x, y));
        } catch (RuntimeException failure) {
            cancelAreaPlacement();
            model.report(failure);
            request();
            return;
        }
        if (!areaPlacement.active()) return;
        var preview = areaPlacement.previewArea();
        var hint = c(areaModeKey(areaPlacement.mode())).copy().append(" · ").append(c(areaPlacement.hint()));
        preview.ifPresent(area -> {
            var a = area.bounds().first();
            var b = area.bounds().second();
            hint.append("  ").append(c("area_edit_coordinates", placementNumber(a.x()), placementNumber(a.y()), placementNumber(a.z()),
                    placementNumber(b.x()), placementNumber(b.y()), placementNumber(b.z())));
            areaPlacement.origin().ifPresent(origin -> {
                var old = areaPlacement.mode() == WorkbenchAreaPlacement.Mode.SECOND ? origin.bounds().second() : origin.bounds().first();
                var now = areaPlacement.mode() == WorkbenchAreaPlacement.Mode.SECOND ? b : a;
                hint.append("  ").append(c("area_edit_delta", placementNumber(now.x().subtract(old.x())), placementNumber(now.y().subtract(old.y())), placementNumber(now.z().subtract(old.z()))));
            });
            if (WorkbenchAreaPlacement.emptyPositionVolume(area)) hint.append("  ").append(c("area_empty_position"));
        });
        ui.renderPointPlacement(graphics, viewportBounds, hint, (int) x, (int) y, preview.isPresent());
    }

    private boolean placementPointerExposed(double x, double y) {
        return page == Page.EDITOR && drawer < 0 && !panelDocking.pending() && !documentTabs.dragging() && !layouts.loading() && draggingDock == null && viewportBounds != null && viewportBounds.contains(x, y) && ui.tree.topLayer() == 0 && ui.tree.hit(x, y).isEmpty() && ui.tree.pointerExposed("world/viewport-input", x, y);
    }

    private void renderPointPlacement(GuiGraphics graphics, int mouseX, int mouseY) {
        if (!pointPlacement.active()) return;
        if (page != Page.EDITOR || drawer >= 0 || viewportBounds == null || !worldEditingReady() || !pointPlacement.current()) {
            cancelPointPlacement();
            return;
        }
        double x = mouseX / DENSITY, y = mouseY / DENSITY;
        try {
            pointPlacement.preview(viewport, x, y, placementPointerExposed(x, y));
        } catch (RuntimeException failure) {
            cancelPointPlacement();
            model.report(failure);
            return;
        }
        if (!pointPlacement.active()) return;
        var preview = pointPlacement.preview();
        Component hint = c(pointPlacement.hint());
        if (preview.isPresent()) {
            var point = preview.get();
            hint = hint.copy().append("  ").append(c("point_move_coordinates", placementNumber(point.x()), placementNumber(point.y()), placementNumber(point.z())));
        }
        ui.renderPointPlacement(graphics, viewportBounds, hint, (int) x, (int) y, preview.isPresent());
    }

    private boolean worldPointSceneExposed() {
        return minecraft.screen == this && minecraft.isWindowActive() && !uiBuildFailed && ui != null && viewport != null && viewportBounds != null && page == Page.EDITOR && drawer < 0 && worldEditingReady() && !worldSession.hasNative() && !worldSession.nativeRendering() && !layouts.loading() && !panelDocking.pending() && !documentTabs.dragging() && draggingDock == null && dragWorkspace == null && ui.tree.topLayer() == 0;
    }

    private WorkbenchWorldViewport.Scene prepareWorldPointScene() {
        if (!worldPointSceneExposed()) {
            pointOverlay.invalidate();
            return null;
        }
        try {
            int normalTint = ui.theme.text(), selectedTint = ui.theme.accent(), previewTint = ui.theme.warning();
            var scene = pointOverlay.prepare(connectedWorldRoot().orElse(null),
                    () -> worldPointSceneExposed() && ui.theme.text() == normalTint && ui.theme.accent() == selectedTint && ui.theme.warning() == previewTint,
                    normalTint, selectedTint, areaPlacement.previewArea(), previewTint);
            pointOverlayFailureReported = false;
            return scene;
        } catch (RuntimeException failure) {
            pointOverlay.invalidate();
            if (!pointOverlayFailureReported) {
                pointOverlayFailureReported = true;
                model.report(failure);
                request();
            }
            return null;
        }
    }

    private static String placementNumber(java.math.BigDecimal value) {
        return value.setScale(3, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    private void worldSelection(int x, int y, int w, int h) {
        ui.heading(c(projectWorldActionKey()), x + 10, y + 6, w - 20, layer);
        ui.edit("world-save/search", c("world_save_search"), worldSession.search(), x + 8, y + 25, Math.max(30, w - 82), layer, true, value -> {
            worldSession.search(value);
            worldListScroll = 0;
        });
        action("world-save/refresh", c("world_refresh"), x + w - 69, y + 25, 61, worldSession::refresh, !worldSession.reading(), "world_loading");
        var worlds = worldSession.worlds().stream().filter(level -> projectWorld.entryAllowed(minecraft.getLevelSource().getBaseDir(), level.getLevelId())).filter(level -> (level.getLevelName() + " " + level.getLevelId()).toLowerCase(Locale.ROOT).contains(worldSession.search().toLowerCase(Locale.ROOT))).toList();
        int listWidth = w >= 400 ? w / 2 : w, rows = Math.max(1, (h - 112) / 40), top = y + 53;
        worldListScroll = Math.max(0, Math.min(worldListScroll, Math.max(0, worlds.size() - rows)));
        if (worlds.isEmpty()) paragraph(c(worldSession.reading() ? "world_save_loading" : "world_save_empty"), x + 10, top + 8, w - 20, 3);
        for (int i = worldListScroll; i < Math.min(worlds.size(), worldListScroll + rows); i++) {
            var level = worlds.get(i);
            int yy = top + (i - worldListScroll) * 40;
            tab("world-save/" + level.getLevelId(), Component.literal(level.getLevelName()), x + 8, yy, listWidth - 16, level.getLevelId().equals(worldSession.selected()), () -> worldSession.select(level.getLevelId()));
            label(Component.literal(level.getLevelId()).append(" · ").append(level.getInfo()), x + 14, yy + 20, listWidth - 28);
        }
        var selected = worlds.stream().filter(level -> level.getLevelId().equals(worldSession.selected())).findFirst().orElse(null);
        if (w >= 400 && selected != null) {
            ui.heading(Component.literal(selected.getLevelName()), x + listWidth + 10, top + 3, w - listWidth - 20, layer);
            paragraph(Component.literal(selected.getLevelId()), x + listWidth + 10, top + 24, w - listWidth - 20, 3);
            paragraph(selected.getInfo(), x + listWidth + 10, top + 65, w - listWidth - 20, 3);
            if (selected.getLastPlayed() > 0) label(Component.literal(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(java.time.ZoneId.systemDefault()).format(java.time.Instant.ofEpochMilli(selected.getLastPlayed()))), x + listWidth + 10, top + 108, w - listWidth - 20);
        }
        if (worldSession.failure().isPresent()) ui.error(worldSession.failure().orElseThrow(), x + 8, y + h - 50, w - 16, layer);
        int bw = Math.max(40, Math.min(105, (w - 28) / 2));
        action("world-save/enter", c(projectWorld.requiresWorld() ? "project_world_open" : "world_enter_save"), x + w - 2 * bw - 18, y + h - 26, bw, () -> {
            if (!projectWorld.requiresWorld() && projectWorld.binding().isEmpty()) worldSession.enterSelected();
            else openAuthoringWorld();
        }, selected != null && !selected.isDisabled() && !worldSession.reading(), "world_save_unavailable");
        ui.style("world-save/enter", WorkbenchSurface.Style.PRIMARY);
        action("world-save/cancel", c("cancel"), x + w - bw - 8, y + h - 26, bw, worldSession::cancelSelection);
        if (worlds.size() > rows) {
            action("world-save/previous", Component.literal("↑"), x + 8, y + h - 26, 22, () -> worldListScroll -= rows);
            action("world-save/next", Component.literal("↓"), x + 34, y + h - 26, 22, () -> worldListScroll += rows);
        }
    }

    private Component resourceCaption(net.ptcrys.fpsmatch.editor.core.resources.ResourceCatalog catalog, UUID id) {
        return catalog.resources().containsKey(id) ? component(model.resourceLabel(id)) : Component.literal(id.toString());
    }

    private void resourceBrowser(UUID panel, int x, int y, int w, int h) {
        var view = model.resources();
        if (view.isEmpty()) {
            paragraph(c("missing_project"), x + 8, y + 10, w - 16, 3);
            return;
        }
        ui.edit("resources/search/" + panel, c("search_resources"), resourceSearch.getOrDefault(panel, ""), x + 6, y + 3, Math.max(24, w - 72), layer, true, value -> {
            resourceSearch.put(panel, value);
            resourceScroll.put(panel, 0);
        });
        action("resources/import/" + panel, c("resource_import"), x + w - 62, y + 3, 56, this::openResourceImport, !model.busy() && !model.protectedMode(), "protected_status");
        String query = resourceSearch.getOrDefault(panel, "").strip().toLowerCase(Locale.ROOT);
        var resources = view.get().content().resources().values().stream().filter(resource -> query.isEmpty() || component(model.resourceLabel(resource.id())).getString().toLowerCase(Locale.ROOT).contains(query)).sorted(Comparator.comparing(net.ptcrys.fpsmatch.editor.core.resources.LogicalResource::name)).toList();
        int rows = Math.max(0, (h - 30) / 24), offset = Math.max(0, Math.min(resourceScroll.getOrDefault(panel, 0), Math.max(0, resources.size() - Math.max(1, rows))));
        resourceScroll.put(panel, offset);
        for (int i = offset; i < Math.min(resources.size(), offset + rows); i++) {
            var resource = resources.get(i);
            tab("resources/" + panel + "/" + resource.id(), component(model.resourceLabel(resource.id())), x + 6, y + 29 + (i - offset) * 24, w - 12, resource.id().equals(selectedResources.get(centralResources)), () -> guardCreation(() -> {
                selectedResources.put(panel, resource.id());
                selectedResources.put(centralResources, resource.id());
                activateDocumentView(StudioDocumentTabs.Key.resources());
                activeWorkspace().flatMap(workspace -> workspace.panels().stream().filter(p -> p.type().equals(WorkspaceTemplates.VIEWPORT)).findFirst()).ifPresent(p -> focusPanel(p.id()));
                drawer = -1;
            }));
        }
        if (resources.isEmpty()) paragraph(c("no_resources"), x + 8, y + 35, w - 16, 3);
    }

    private void resourcePanel(UUID panel, int x, int y, int w, int h) {
        var view = model.resources();
        if (view.isEmpty()) {
            paragraph(c("missing_project"), x + 8, y + 10, w - 16, 3);
            return;
        }
        var catalog = view.get().content();
        int listWidth = Math.max(100, w * 2 / 5), top = y + 24, bottom = y + h - 23, rows = Math.max(1, (bottom - top) / 21);
        ui.edit("resources/search/" + panel, c("search_resources"), resourceSearch.getOrDefault(panel, ""), x + 6, y + 2, Math.max(32, listWidth - 12), layer, true, value -> {
            resourceSearch.put(panel, value);
            resourceScroll.put(panel, 0);
            request();
        });
        action("resources/import/" + panel, c("resource_import"), x + listWidth + 8, y + 2, Math.min(102, w - listWidth - 16), this::openResourceImport, !model.busy() && !model.protectedMode(), "protected_status");
        if (w - listWidth > 230) {
            action("resources/undo/" + panel, c("undo_short"), x + listWidth + 116, y + 2, 52, model::undoResources, !model.busy() && view.get().canUndo(), "unavailable_history");
            action("resources/redo/" + panel, c("redo_short"), x + listWidth + 172, y + 2, 52, model::redoResources, !model.busy() && view.get().canRedo(), "unavailable_history");
        }
        String query = resourceSearch.getOrDefault(panel, "").strip().toLowerCase(Locale.ROOT);
        var resources = catalog.resources().values().stream().filter(resource -> query.isEmpty() || component(model.resourceLabel(resource.id())).getString().toLowerCase(Locale.ROOT).contains(query)).sorted(Comparator.comparing(net.ptcrys.fpsmatch.editor.core.resources.LogicalResource::name)).toList();
        int offset = Math.max(0, Math.min(resourceScroll.getOrDefault(panel, 0), Math.max(0, resources.size() - rows)));
        resourceScroll.put(panel, offset);
        for (int i = offset; i < Math.min(resources.size(), offset + rows); i++) {
            var resource = resources.get(i);
            tab("resources/" + panel + "/" + resource.id(), component(model.resourceLabel(resource.id())), x + 6, top + (i - offset) * 21, listWidth - 12, resource.id().equals(selectedResources.get(panel)), () -> {
                selectedResources.put(panel, resource.id());
                resourceDetailScroll.put(panel, 0);
            });
        }
        if (resources.isEmpty()) label(c("no_resources"), x + 8, top + 4, listWidth - 16);
        if (resources.size() > rows) scroll("resources/list/" + panel, x + 6, bottom, listWidth - 12, () -> resourceScroll.put(panel, Math.max(0, offset - rows)), () -> resourceScroll.put(panel, offset + rows));
        UUID selected = selectedResources.get(panel);
        var resource = selected == null ? null : catalog.resources().get(selected);
        if (resource == null && !resources.isEmpty()) {
            resource = resources.get(0);
            selectedResources.put(panel, resource.id());
        }
        if (resource == null) return;
        final var fixed = resource;
        String context = panel + "/" + fixed.id();
        var versions = catalog.versions().values().stream().filter(version -> version.resourceId().equals(fixed.id())).toList();
        String chosen = resourceVersions.getOrDefault(context, fixed.currentVersion().toString());
        var version = versions.stream().filter(value -> value.id().toString().equals(chosen)).findFirst().orElse(catalog.versions().get(fixed.currentVersion()));
        if (version != null) resourceVersions.put(context, version.id().toString());
        String active = resourceTabs.getOrDefault(panel, "preview");
        if (!Set.of("preview", "versions", "references", "source").contains(active)) active = "preview";
        int detailX = x + listWidth + 8, detailW = w - listWidth - 16, tabWidth = Math.max(24, detailW / 4), index = 0;
        for (String name : List.of("preview", "versions", "references", "source")) {
            String key = name;
            tab("resources/tab/" + panel + "/" + key, c("resource_tab_" + key), detailX + index++ * tabWidth, top, tabWidth - 2, key.equals(active), () -> {
                resourceTabs.put(panel, key);
                resourceDetailScroll.put(panel, 0);
            });
        }
        int detailTop = top + 22;
        if (active.equals("versions")) {
            int count = Math.max(1, (bottom - detailTop) / 21), first = Math.max(0, Math.min(resourceDetailScroll.getOrDefault(panel, 0), Math.max(0, versions.size() - count)));
            resourceDetailScroll.put(panel, first);
            for (int i = first; i < Math.min(versions.size(), first + count); i++) {
                var item = versions.get(i);
                String id = "resources/version/" + panel + "/" + item.id();
                Component title = Component.literal(String.format(Locale.ROOT, "%02d · ", i + 1)).append(c(item.id().equals(fixed.currentVersion()) ? "resource_version_current" : "resource_version_historical")).append(" · " + item.id());
                tab(id, title, detailX, detailTop + (i - first) * 21, detailW, version != null && item.id().equals(version.id()), () -> resourceVersions.put(context, item.id().toString()));
                ui.detail(id, item.id().toString());
            }
            if (versions.size() > count) scroll("resources/details/" + panel, detailX, bottom, detailW, () -> resourceDetailScroll.put(panel, Math.max(0, first - count)), () -> resourceDetailScroll.put(panel, first + count));
            return;
        }
        List<Component> lines = new ArrayList<>();
        lines.add(component(model.resourceLabel(fixed.id())));
        lines.add(c("resource_locked_version").copy().append(" " + (version == null ? fixed.currentVersion() : version.id())));
        if (version == null) lines.add(c("resource_reference_missing"));
        else if (active.equals("preview")) {
            lines.add(Component.literal(fixed.type().toString()));
            lines.add(c("resource_metadata_preview"));
            lines.add(Component.translatable("editor.workbench.resource_dependencies", version.dependencyVersions().size(), version.outputEntries().size()));
            if (version.sourceEntry() != null) {
                var entry = catalog.byteEntries().get(version.sourceEntry());
                if (entry != null) {
                    lines.add(Component.literal(entry.content().toString()));
                } else lines.add(c("resource_reference_missing"));
            }
        } else if (active.equals("source")) {
            lines.add(c("resource_importer").copy().append(version.importer() + " / " + version.importerVersion()));
            lines.add(c("resource_source_entry").copy().append(version.sourceEntry() == null ? "—" : version.sourceEntry().toString()));
            version.settings().forEach((key, value) -> lines.add(Component.literal(key + " = " + value)));
            version.outputEntries().forEach(id -> lines.add(c("resource_output_entry").copy().append(id.toString())));
        } else {
            UUID versionId = version.id();
            lines.add(c("resource_outgoing_references"));
            version.dependencyVersions().forEach(id -> {
                var dependency = catalog.versions().get(id);
                lines.add(Component.literal("→ ").append(dependency == null ? Component.literal(id.toString()) : resourceCaption(catalog, dependency.resourceId()).copy().append(" · " + id)));
            });
            lines.add(c("resource_incoming_references"));
            int incoming = 0;
            for (var dependent : catalog.versions().values()) if (dependent.dependencyVersions().contains(versionId)) {
                lines.add(resourceCaption(catalog, dependent.resourceId()).copy().append(" · " + dependent.id()));
                incoming++;
            }
            for (var document : listProjection.documents().entrySet()) for (var ref : model.resourcesForDocument(document.getKey()).orElse(List.of())) if (ref.resourceId().equals(fixed.id()) && ref.versionId().equals(versionId)) {
                lines.add(Component.literal(document.getValue().displayName()).append(" · " + document.getKey()));
                incoming++;
                break;
            }
            if (incoming == 0) lines.add(c("resource_no_incoming"));
        }
        List<Component> wrapped = new ArrayList<>();
        for (var line : lines) for (var part : font.split(line, Math.max(20, detailW))) {
            StringBuilder text = new StringBuilder();
            part.accept((at, style, code) -> {
                text.appendCodePoint(code);
                return true;
            });
            wrapped.add(Component.literal(text.toString()));
        }
        int lineRows = Math.max(1, (bottom - detailTop) / 14), lineOffset = Math.max(0, Math.min(resourceDetailScroll.getOrDefault(panel, 0), Math.max(0, wrapped.size() - lineRows)));
        resourceDetailScroll.put(panel, lineOffset);
        for (int i = lineOffset; i < Math.min(wrapped.size(), lineOffset + lineRows); i++) label(wrapped.get(i), detailX, detailTop + 2 + (i - lineOffset) * 14, detailW);
        if (wrapped.size() > lineRows) scroll("resources/details/" + panel, detailX, bottom, detailW, () -> resourceDetailScroll.put(panel, Math.max(0, lineOffset - lineRows)), () -> resourceDetailScroll.put(panel, lineOffset + lineRows));
    }

    private void taskPanel(UUID panel, int x, int y, int w, int h) {
        int list = Math.max(92, w / 3), top = y + 6;
        String selected = selectedTasks.getOrDefault(panel, tests.state() != RuntimeTestWorkbench.State.IDLE || testActionError != null ? "test" : "project");
        var tasks = new LinkedHashMap<String, Component>();
        tasks.put("project", c("project"));
        tasks.put("install", c("install_title"));
        if (tests.state() != RuntimeTestWorkbench.State.IDLE || testActionError != null) tasks.put("test", c("test_title"));
        if (resourceTaskKnown) tasks.put("resource", c("resource_import"));
        if (worldTarget.available() || worldTarget.registrationReceipt().isPresent()) tasks.put("world", c("world_target"));
        if (!tasks.containsKey(selected)) selected = "project";
        selectedTasks.put(panel, selected);
        var entries = new ArrayList<>(tasks.entrySet());
        int rows = Math.max(1, (h - 30) / 20), offset = Math.max(0, Math.min(taskScroll.getOrDefault(panel, 0), Math.max(0, entries.size() - rows)));
        taskScroll.put(panel, offset);
        for (int i = offset; i < Math.min(entries.size(), offset + rows); i++) {
            var task = entries.get(i);
            String key = task.getKey();
            tab("task/" + panel + "/" + key, task.getValue(), x + 6, top + (i - offset) * 20, list - 12, key.equals(selected), () -> selectedTasks.put(panel, key));
        }
        if (entries.size() > rows) scroll("task/list/" + panel, x + 6, y + h - 23, list - 12, () -> taskScroll.put(panel, Math.max(0, offset - rows)), () -> taskScroll.put(panel, offset + rows));
        int tx = x + list + 8, tw = w - list - 16;
        Component status = switch (selected) {
            case "test" -> testStatus();
            case "install" -> component(installations.status());
            case "world" -> worldTarget.registering() ? registrationStatus() : worldStatus();
            case "resource" -> acceptingResource ? c("resource_staging") : inspectingResource ? c("resource_report") : resourceTaskStatus == null ? component(model.status()) : resourceTaskStatus;
            default -> component(model.status());
        };
        ui.heading(status, tx, top, tw, layer);
        if (selected.equals("install")) {
            installations.taskInput().ifPresent(input -> {
                label(Component.literal(input.worldLabel()), tx, top + 18, tw);
                if (h > 90) label(c("install_fixed_commit").copy().append(input.commitId().toString()), tx, top + 34, tw);
            });
            action("task/install-details/" + panel, c("details"), tx, y + h - 23, Math.min(110, tw), this::openInstallationTask);
        } else if (selected.equals("test")) {
            var input = testDisplayInput();
            if (input != null) {
                label(Component.literal(input.worldLabel()), tx, top + 18, tw);
                if (h > 90) label(c("install_fixed_commit").copy().append(input.plan().commitId().toString()), tx, top + 34, tw);
            }
            action("task/test-details/" + panel, c("details"), tx, y + h - 23, Math.min(110, tw), () -> show(Page.TEST));
        } else if (selected.equals("resource")) action("task/resource-details/" + panel, c("details"), tx, y + h - 23, Math.min(110, tw), () -> show(Page.RESOURCE_REPORT));
        else if (selected.equals("world")) {
            label(Component.literal(worldTarget.targetLabel()), tx, top + 18, tw);
            action("task/world-details/" + panel, c("details"), tx, y + h - 23, Math.min(110, tw), worldTarget.registrationReceipt().isPresent() ? this::openRegistrationReceipt : this::openWorldTarget);
        } else {
            label(Component.literal(model.projectName()), tx, top + 18, tw);
            if (model.busy()) action("task/cancel/" + panel, c(model.canCancel() ? "cancel_task" : "publishing"), tx, y + h - 23, Math.min(110, tw), model::cancel, model.canCancel(), "publishing");
            else if (model.unresolvedSave()) action("task/reconcile/" + panel, c("reconcile"), tx, y + h - 23, Math.min(110, tw), model::reconcile);
            else action("task/project-details/" + panel, c("details"), tx, y + h - 23, Math.min(110, tw), () -> show(Page.PROBLEMS));
        }
    }

    private void problemsPanel(UUID panel, int x, int y, int w, int h) {
        var layoutDiagnostic = layoutProblem();
        if (layoutDiagnostic.isPresent()) {
            action("studio/layout-warning/" + panel, layoutDiagnostic.orElseThrow(), x + 8, y + 5, w - 16, () -> {
                menuAnchor = 12;
                menuY = MENU_BOTTOM;
                show(Page.WINDOW_MENU);
            });
            y += 28;
            h -= 28;
        }
        var preferenceDiagnostic = MinecraftWorkbenchPreferences.diagnostic();
        if (preferenceDiagnostic.isPresent()) {
            action("studio/preferences-warning/" + panel, component(preferenceDiagnostic.orElseThrow()), x + 8, y + 5, w - 16, () -> {
                menuAnchor = 12;
                menuY = MENU_BOTTOM;
                show(Page.VIEW_MENU);
            });
            if (model.problems().isEmpty() && fieldErrors.isEmpty()) return;
            y += 28;
            h -= 28;
        }
        if (model.problems().isEmpty() && fieldErrors.isEmpty()) {
            label(c("studio_no_problems"), x + 8, y + 10, w - 16);
            return;
        }
        if (!fieldErrors.isEmpty()) {
            var entries = fieldErrors.entrySet().stream().sorted(Comparator.comparing(entry -> entry.getKey().document() + "/" + entry.getKey().node() + "/" + entry.getKey().property())).toList();
            int rows = Math.max(1, (h - 24) / 25), offset = Math.max(0, Math.min(problemScroll, Math.max(0, entries.size() - rows)));
            problemScroll = offset;
            for (int i = offset; i < Math.min(entries.size(), offset + rows); i++) {
                var entry = entries.get(i);
                var address = entry.getKey();
                String id = "studio/problem/" + panel + "/" + address.document() + "/" + address.pendingKey();
                String location = fieldLocation(address);
                action(id, Component.literal(location + " · ").append(entry.getValue()), x + 8, y + 5 + (i - offset) * 25, w - 16, () -> revealProblemField(address));
            }
        } else showProblems(x + 8, y + 5, w - 16, Math.max(1, (h - 24) / 30));
        action("problems/details/" + panel, c("details"), x + w - 76, y + h - 22, 68, () -> show(Page.PROBLEMS));
    }

    private Component panelLabel(EditorId type) {
        if (type.equals(WorkspaceTemplates.PROJECT)) return c("studio_project_browser");
        if (type.equals(WorkspaceTemplates.OBJECTS)) return c("studio_objects");
        if (type.equals(WorkspaceTemplates.SUMMARY)) return c("document_summary");
        if (type.equals(WorkspaceTemplates.VIEWPORT)) return c("viewport");
        if (type.equals(WorkspaceTemplates.RESOURCES)) return c("studio_resources");
        if (type.equals(WorkspaceTemplates.TASKS)) return c("tasks");
        if (type.equals(WorkspaceTemplates.PROBLEMS)) return fieldErrors.isEmpty() ? c("problems") : c("problems").copy().append(" · " + fieldErrors.size());
        if (type.equals(WorkspaceTemplates.INSPECTOR)) return c("studio_inspector");
        return Component.literal(type.toString());
    }

    private void savePanelContext(PanelInstance panel) {
        String context = displayedPanelContexts.get(panel.id());
        if (context == null) return;
        if (panel.type().equals(WorkspaceTemplates.OBJECTS)) objectContextScroll.put(context, objectScrollByPanel.getOrDefault(panel.id(), 0));
        if (panel.type().equals(WorkspaceTemplates.INSPECTOR)) fieldContextScroll.put(context, fieldScrollByPanel.getOrDefault(panel.id(), 0));
    }

    private void restorePanelContext(PanelInstance panel) {
        UUID document = panelDocument(panel.id()).orElse(null);
        String context = panel.id() + "/" + document;
        if (panel.type().equals(WorkspaceTemplates.INSPECTOR)) context += "/" + (document == null ? "" : model.selectedNode(document).orElse(""));
        if (context.equals(displayedPanelContexts.get(panel.id()))) return;
        savePanelContext(panel);
        displayedPanelContexts.put(panel.id(), context);
        if (panel.type().equals(WorkspaceTemplates.OBJECTS)) objectScrollByPanel.put(panel.id(), objectContextScroll.getOrDefault(context, 0));
        if (panel.type().equals(WorkspaceTemplates.INSPECTOR)) {
            fieldScrollByPanel.put(panel.id(), fieldContextScroll.getOrDefault(context, 0));
            fieldRowOffset.remove(panel.id());
            fieldRowOverflow.remove(panel.id());
        }
    }

    private Optional<UUID> panelDocument(UUID id) {
        var panel = panelInstances.get(id);
        if (panel == null) return Optional.empty();
        return panelDocument(panel);
    }

    private Optional<UUID> panelDocument(PanelInstance panel) {
        return panel.binding().mode() == PanelBinding.Mode.PINNED ? Optional.of(panel.binding().targetDocument()) : model.activeDocument();
    }

    private void togglePanelBinding(UUID id) {
        activeWorkspace().ifPresent(workspace -> {
            var panel = workspace.panels().stream().filter(p -> p.id().equals(id)).findFirst().orElseThrow();
            if (panel.binding().mode() == PanelBinding.Mode.PINNED) workspaces.bind(workspace.id(), id, PanelBinding.followActive());
            else model.activeDocument().ifPresent(document -> workspaces.bind(workspace.id(), id, PanelBinding.pinned(document)));
        });
        request();
    }

    private void panel(PanelInstance panel, int x, int y, int w, int h, boolean heading) {
        ui.clip(new ControlTree.Bounds(x, y, Math.max(0, w), Math.max(0, h)));
        try {
            panelContent(panel, x, y, w, h, heading);
        } finally {
            ui.clip(null);
        }
    }

    private void panelContent(PanelInstance panel, int x, int y, int w, int h, boolean heading) {
        panelInstances.put(panel.id(), panel);
        restorePanelContext(panel);
        panelBounds.put(panel.id(), new ControlTree.Bounds(x, y, w, h));
        panelTypes.put(panel.id(), panel.type());
        if (!panel.type().equals(WorkspaceTemplates.VIEWPORT)) ui.panel("panel/" + panel.id() + "/" + layer, x, y, w, h, layer);
        if (heading && !panel.type().equals(WorkspaceTemplates.VIEWPORT)) {
            var body = ui.components.panel("heading/" + panel.id() + "/" + layer, panelLabel(panel.type()).getString(), new ControlTree.Bounds(x, y, w, Math.max(0, h)), layer, panelActions(panel, x, y, w));
            y = body.y();
            h = body.height();
        }
        if (objectCreation != null && panel.id().equals(creationPanelTarget)) {
            creationPanel(panel.id(), x, y, w, h);
            return;
        }
        if (panel.type().equals(WorkspaceTemplates.OBJECTS) || panel.type().equals(WorkspaceTemplates.INSPECTOR)) {
            boolean pinned = panel.binding().mode() == PanelBinding.Mode.PINNED;
            boolean missing = panelDocument(panel.id()).filter(listProjection.documents()::containsKey).isEmpty();
            if (missing) {
                panelBinding(panel, x, y, w);
                y += 23;
                h -= 23;
            }
            if (missing) {
                paragraph(c(pinned ? "panel_document_closed" : "no_document"), x + 8, y + 8, w - 16, 4);
                return;
            }
        }
        if (panel.type().equals(WorkspaceTemplates.PROJECT)) projectBrowser(panel.id(), x, y, w, h);
        else if (panel.type().equals(WorkspaceTemplates.OBJECTS)) {
            objectScroll = objectScrollByPanel.getOrDefault(panel.id(), 0);
            objects(panel.id(), x, y, w, h);
            objectScrollByPanel.put(panel.id(), objectScroll);
        } else if (panel.type().equals(WorkspaceTemplates.INSPECTOR)) {
            fieldScroll = fieldScrollByPanel.getOrDefault(panel.id(), 0);
            inspector(panel.id(), x, y, w, h);
            fieldScrollByPanel.put(panel.id(), fieldScroll);
        } else if (panel.type().equals(WorkspaceTemplates.VIEWPORT)) centralDocument(panel, x, y, w, h, heading);
        else if (panel.type().equals(WorkspaceTemplates.RESOURCES)) {
            if (w < 300) resourceBrowser(panel.id(), x, y, w, h);
            else resourcePanel(panel.id(), x, y, w, h);
        } else if (panel.type().equals(WorkspaceTemplates.TASKS)) taskPanel(panel.id(), x, y, w, h);
        else if (panel.type().equals(WorkspaceTemplates.PROBLEMS)) problemsPanel(panel.id(), x, y, w, h);
        else if (panel.type().equals(WorkspaceTemplates.SUMMARY)) summary(x, y, w, h);
        else {
            label(c("missing_panel"), x + 10, y + 34, w - 20);
            label(Component.literal(panel.type().toString()), x + 10, y + 52, w - 20);
        }
    }

    private void panelBinding(PanelInstance panel, int x, int y, int w) {
        boolean pinned = panel.binding().mode() == PanelBinding.Mode.PINNED;
        action("panel-binding/" + panel.id(), pinned ? c("panel_pinned").copy().append(" · ").append(Optional.ofNullable(listProjection.documents().get(panel.binding().targetDocument())).map(hd -> hd.displayName()).orElse(panel.binding().targetDocument().toString())) : c("follow_active"), x + 6, y, w - 12, () -> guardCreation(() -> togglePanelBinding(panel.id())), pinned || model.activeDocument().isPresent(), "no_document");
    }

    private void centralDocument(PanelInstance panel, int x, int y, int w, int h, boolean heading) {
        var current = documentTabs.active();
        var rows = new ArrayList<StudioView.Element>();
        rows.add(ui.view.placeholder("studio/central/bar", Style.defaults().height(Length.fixed(studioUnits(35)))));
        if (current.filter(key -> key.kind() == StudioDocumentTabs.Kind.SCENE).isPresent()) rows.add(documentTools());
        else if (current.filter(key -> key.kind() == StudioDocumentTabs.Kind.RESOURCES).isPresent()) rows.add(ui.view.button(studioAction("studio/resource-back", c("studio_return_scene"), Icon.PREVIOUS, Variant.DEFAULT, model.activeDocument().isPresent(), false, () -> model.activeDocument().ifPresent(this::openSceneDocument))));
        rows.add(ui.view.placeholder("studio/central/body", Style.fill()));
        var arranged = ui.view.layout(ui.view.column("studio/central", Style.fill(), rows), new ControlTree.Bounds(x, y, w, Math.max(0, h)));
        ui.emit(arranged, layer);
        var bar = arranged.bounds("studio/central/bar");
        documentBar(panel, bar.x(), bar.y(), bar.width(), heading);
        var body = arranged.bounds("studio/central/body");
        if (current.isEmpty()) {
            ui.emit(ui.view.layoutContent(ui.view.paragraph("studio/empty-document", c("studio_no_open_documents").getString(), TextSize.BODY, Variant.MUTED).style(Style.defaults().padding(Insets.all(12))), body.x(), body.y(), body.width()), layer);
            return;
        }
        var key = current.orElseThrow();
        if (key.kind() == StudioDocumentTabs.Kind.RESOURCES) resourcePanel(centralResources, body.x(), body.y(), body.width(), body.height());
        else if (key.kind() == StudioDocumentTabs.Kind.FORM) formDocument(new StudioFormDocument.Key(key.document(), key.target()), body.x(), body.y(), body.width(), body.height());
        else if (key.kind() == StudioDocumentTabs.Kind.GRID) currentStudioGrid(key.document()).ifPresent(grid -> studioGrid(key.document(), grid, body.x(), body.y(), body.width(), body.height()));
        else viewportPanel(body.x(), body.y(), body.width(), body.height());
    }

    private StudioView.Element documentTools() {
        var actions = List.of(
                studioAction("action/add", c("add_object"), Icon.PLUS, Variant.DEFAULT, model.activeDocument().isPresent(), false, () -> {
                    var bounds = ui.tree.snapshot().stream().filter(node -> node.id().equals("action/add")).findFirst().map(ControlTree.Node::bounds).orElse(new ControlTree.Bounds(TOOL_RAIL, BODY_TOP, 0, 0));
                    openAdd(bounds.x(), bounds.y() + bounds.height());
                }),
                studioAction("action/undo", c("undo"), Icon.UNDO, Variant.DEFAULT, model.canUndo(), false, model::undo),
                studioAction("action/redo", c("redo"), Icon.REDO, Variant.DEFAULT, model.canRedo(), false, model::redo),
                studioAction("action/save", c("save_all"), Icon.SAVE, Variant.DEFAULT, model.hasProject() && !model.busy() && !model.unresolvedSave(), false, model::saveAll));
        return ui.view.surface("studio/document-tools", Kind.TOOLBAR, FlowLayout.Kind.WRAP, Style.defaults().padding(Insets.all(3)).gap(3), actions.stream().map(ui.view::button).toList());
    }

    private Optional<StudioDocument.Grid> currentStudioGrid(UUID document) {
        return documentTabs.active().filter(key -> key.kind() == StudioDocumentTabs.Kind.GRID && document.equals(key.document())).flatMap(key -> projectedGrid(document, key.target()));
    }

    private Optional<StudioDocument.Grid> studioGridForSelection(UUID document, String node) {
        var direct = projectedGrid(document, node);
        if (direct.isPresent()) return direct;
        var sections = model.studioSections(document, node).stream().filter(section -> section.target().id().equals(node) || section.children().stream().anyMatch(child -> child.id().equals(node))).toList();
        if (sections.isEmpty()) return Optional.empty();
        for (var section : sections) for (var reference : section.referencedBy()) {
            var referenced = projectedGrid(document, reference.id());
            if (referenced.isPresent()) return referenced;
        }
        String last = lastGridOwners.get(document);
        if (last != null) {
            var prior = projectedGrid(document, last);
            if (prior.isPresent()) return prior;
        }
        for (var candidate : model.sceneNodes(document)) {
            var grid = projectedGrid(document, candidate.id());
            if (grid.isPresent()) return grid;
        }
        return Optional.empty();
    }

    private Optional<StudioDocument.Grid> projectedGrid(UUID document, String node) {
        buildingContentDocuments.add(document);
        return contentProjection.grid(document, node);
    }

    private List<ProjectWorkbench.CreationChoice> activeCreationChoices() {
        return model.activeDocument().map(document -> projectedCreationChoices(document, model.selectedNode(document).orElse(null))).orElseGet(model::creationChoices);
    }

    private List<ProjectWorkbench.CreationChoice> projectedCreationChoices(UUID document, String node) {
        buildingContentDocuments.add(document);
        return contentProjection.creationChoices(document, node);
    }

    private List<ProjectWorkbench.CreationChoice> projectedReferenceChoices(ProjectWorkbench.Address address) {
        buildingContentDocuments.add(address.document());
        return contentProjection.creationChoicesForReference(address);
    }

    private List<StudioDocument.Grid> documentGrids(UUID document) {
        buildingContentDocuments.add(document);
        return contentProjection.grids(document);
    }

    private Component gridOwnerLabel(UUID document, StudioDocument.Grid grid) {
        buildingContentDocuments.add(document);
        var owner = contentProjection.node(document, grid.owner());
        return owner.flatMap(DocumentEditor.Node::parent).flatMap(parent -> contentProjection.node(document, parent)).map(node -> component(node.label())).orElseGet(() -> owner.map(node -> component(node.label())).orElse(component(grid.label())));
    }

    private Component gridCaption(UUID document, StudioDocument.Grid grid) {
        return gridOwnerLabel(document, grid).copy().append(" · ").append(c("studio_shop"));
    }

    private List<String> objectPath(UUID document, String node) {
        buildingContentDocuments.add(document);
        return contentProjection.path(document, node).stream().map(item -> component(item.label()).getString()).toList();
    }

    private String fieldLocation(ProjectWorkbench.Address address) {
        var labels = new ArrayList<String>();
        var document = listProjection.documents().get(address.document());
        if (document != null) labels.add(document.displayName());
        if (document != null && model.nodes(address.document()).stream().anyMatch(node -> node.id().equals(address.node()))) {
            labels.addAll(objectPath(address.document(), address.node()));
            model.fields(address.document(), address.node()).stream().filter(field -> field.property().equals(address.property())).findFirst().ifPresent(field -> labels.add(component(field.label()).getString()));
        }
        return String.join(" › ", labels);
    }

    private void studioGrid(UUID document, StudioDocument.Grid grid, int x, int y, int w, int h) {
        gridBounds = new ControlTree.Bounds(x, y, w, h);
        String context = document + "/" + grid.owner();
        Set<String> chosenNodes = gridSelections.computeIfAbsent(context, ignored -> new LinkedHashSet<>());
        chosenNodes.retainAll(grid.cells().stream().flatMap(cell -> cell.node().stream()).collect(java.util.stream.Collectors.toSet()));
        boolean grouping = gridGroupModes.contains(context);
        String selected = model.selectedNode(document).orElse("");
        var selectedCell = grid.cells().stream().filter(cell -> cell.node().filter(selected::equals).isPresent()).findFirst();
        ui.surface("studio/grid/page", x, y, w, h, layer, Kind.PANEL);
        ui.rectangle("studio/grid/paper", x, y, w, h, layer, ui.theme.raised());
        var stack = new StudioLayout.Stack(new ControlTree.Bounds(x + 10, y + 8, Math.max(0, w - 20), Math.max(0, h - 10)), 2);
        ui.components.breadcrumb("studio/grid/path", objectPath(document, grid.owner()), stack.take(14), layer);
        var ownerActions = new ArrayList<StudioComponents.Action>();
        for (var shop : documentGrids(document)) ownerActions.add(studioAction("studio/grid/owner/" + shop.owner(), gridOwnerLabel(document, shop), Icon.NONE, Variant.DEFAULT, true, shop.owner().equals(grid.owner()), () -> {
            gridScroll.put(document, 0);
            selectObject(document, shop.owner(), true);
        }));
        ownerActions.add(studioAction("studio/grid/settings", c("studio_grid_settings"), Icon.SETTINGS, Variant.DEFAULT, true, false, () -> selectObject(document, grid.owner(), true)));
        ui.components.titleBar("studio/grid", c("studio_shop_editor").getString(), stack.take(22), layer, ownerActions);
        ui.components.text("studio/grid/summary", component(grid.label()).getString(), stack.take(14), layer, TextSize.SMALL, Variant.MUTED);
        var tools = stack.take(22);
        var toolbar = List.of(
                studioAction("studio/grid/slots", c("studio_slots"), Icon.NONE, Variant.DEFAULT, true, !grouping, () -> gridGroupModes.remove(context)),
                studioAction("studio/grid/groups", c("studio_groups"), Icon.NONE, Variant.DEFAULT, true, grouping, () -> gridGroupModes.add(context)));
        // Presentation can change with state without replacing the focused command identity.
        String[] commandIds = { "COPY", "PASTE", "CLEAR", "SWAP" };
        Icon[] icons = { Icon.COPY, Icon.PASTE, Icon.ERASER, gridSwapSources.containsKey(context) ? Icon.CLOSE : Icon.SWAP };
        String[] names = { "studio_copy", "studio_paste", "studio_clear_item", gridSwapSources.containsKey(context) ? "cancel" : "studio_swap" };
        Runnable[] commands = { () -> model.copyStudioCell(document, selected), () -> model.applyStudioCommand(document, new StudioDocument.Command(StudioDocument.Action.PASTE, List.of(selected), Optional.empty())), () -> model.applyStudioCommand(document, new StudioDocument.Command(StudioDocument.Action.CLEAR, List.of(selected), Optional.empty())), () -> {
            if (gridSwapSources.containsKey(context)) gridSwapSources.remove(context);
            else gridSwapSources.put(context, selected);
        } };
        var commandActions = new ArrayList<StudioComponents.Action>();
        for (int i = 0; i < icons.length; i++) {
            boolean enabled = selectedCell.isPresent() && (i == 0 || editableFields()) && (i != 1 || model.canPasteStudioCell(document));
            commandActions.add(studioAction("studio/grid/command/" + commandIds[i], c(names[i]), icons[i], Variant.DEFAULT, enabled, false, commands[i]));
        }
        ui.components.commandBar("studio/grid/modes", tools, layer, toolbar, commandActions);
        var search = stack.take(20);
        ui.components.input("studio/grid/search/" + document, c("studio_grid_search").getString(), gridSearch.getOrDefault(document, ""), new ControlTree.Bounds(search.x(), search.y(), Math.min(190, search.width()), 20), layer, true, false, value -> {
            gridSearch.put(document, value);
            request();
        });
        if (grouping) {
            var groupRow = stack.take(23);
            int inputWidth = Math.min(64, w / 4);
            ui.components.input("studio/grid/group/" + context, c("studio_group_number").getString(), gridGroupRaw.getOrDefault(context, "-1"), new ControlTree.Bounds(groupRow.x(), groupRow.y(), inputWidth, 20), layer, true, false, value -> {
                gridGroupRaw.put(context, value);
                request();
            });
            ui.components.button(studioAction("studio/grid/group-apply", c("studio_apply_group").copy().append(" · " + chosenNodes.size()), Icon.NONE, Variant.OUTLINED, !chosenNodes.isEmpty() && editableFields(), false, () -> model.applyStudioCommand(document, new StudioDocument.Command(StudioDocument.Action.GROUP, List.copyOf(chosenNodes), Optional.of(gridGroupRaw.getOrDefault(context, "-1"))))), new ControlTree.Bounds(groupRow.x() + inputWidth + 4, groupRow.y(), Math.max(0, groupRow.width() - inputWidth - 75), 20), layer, Kind.BUTTON);
            ui.components.button(studioAction("studio/grid/group-clear", c("studio_clear_selection"), Icon.NONE, Variant.DEFAULT, true, false, chosenNodes::clear), new ControlTree.Bounds(x + w - 76, groupRow.y(), 66, 20), layer, Kind.BUTTON);
            var selectGroup = stack.take(20);
            ui.components.button(studioAction("studio/grid/select-group", c("studio_select_group"), Icon.NONE, Variant.DEFAULT, selectedCell.flatMap(StudioDocument.Cell::group).isPresent(), false, () -> selectedCell.flatMap(StudioDocument.Cell::group).ifPresent(group -> grid.cells().stream().filter(cell -> cell.group().filter(group::equals).isPresent()).flatMap(cell -> cell.node().stream()).forEach(chosenNodes::add))), selectGroup, layer, Kind.BUTTON);
        }
        if (gridSwapSources.containsKey(context)) {
            var hint = stack.take(14);
            ui.components.text("studio/grid/swap-hint", c("studio_swap_target").getString(), hint, layer, TextSize.SMALL, Variant.MUTED);
        }
        String query = gridSearch.getOrDefault(document, "").strip().toLowerCase(Locale.ROOT), selectedGroup = selectedCell.flatMap(StudioDocument.Cell::group).orElse(null);
        var columns = grid.columns().stream().map(column -> new StudioComponents.Column(column.id(), component(column.label()).getString(), column.rows(), grouping ? () -> run(() -> grid.cells().stream().filter(cell -> cell.column().equals(column.id())).flatMap(cell -> cell.node().stream()).forEach(chosenNodes::add)) : null)).toList();
        var slots = new ArrayList<StudioComponents.Slot>();
        for (var cell : grid.cells()) {
            String id = "studio/grid/" + document + "/" + grid.owner() + "/" + cell.column() + "/" + cell.row();
            var detail = cell.details().stream().map(WorkbenchScreen::component).map(Component::getString).toList();
            boolean chosen = cell.node().filter(grouping ? chosenNodes::contains : selected::equals).isPresent(), matches = query.isEmpty() || (component(cell.label()).getString() + " " + String.join(" ", detail)).toLowerCase(Locale.ROOT).contains(query);
            boolean enabled = cell.node().isPresent() || editableFields() && !grouping && !gridSwapSources.containsKey(context), peer = !grouping && !chosen && selectedGroup != null && cell.group().filter(selectedGroup::equals).isPresent();
            slots.add(new StudioComponents.Slot(id, cell.column(), cell.row(), component(cell.label()).getString(), detail, cell.empty(), chosen, peer, !matches, enabled, enabled ? "" : c("studio_prepare_empty").getString(), () -> run(() -> guardCreation(() -> {
                String target = cell.node().orElseGet(() -> model.createStudioCell(document, grid.owner(), cell.column(), cell.row()));
                if (grouping) {
                    if (!chosenNodes.add(target)) chosenNodes.remove(target);
                    return;
                }
                String source = gridSwapSources.get(context);
                if (source != null) {
                    if (!source.equals(target)) model.applyStudioCommand(document, new StudioDocument.Command(StudioDocument.Action.SWAP, List.of(source, target), Optional.empty()));
                    gridSwapSources.remove(context);
                }
                selectObject(document, target, true);
            })), cell.badge().map(WorkbenchScreen::component).map(Component::getString).orElse("")));
        }
        var result = ui.components.slotGrid("studio/grid/view", columns, slots, stack.rest(), layer, gridColumn.getOrDefault(document, 0), gridScroll.getOrDefault(document, 0), value -> {
            gridColumn.put(document, value);
            request();
        }, value -> {
            gridScroll.put(document, value);
            request();
        }, (first, last, total) -> c("studio_row_range", first, last, total).getString());
        gridColumn.put(document, result.firstColumn());
        gridScroll.put(document, result.rows().offset());
    }

    private StudioComponents.Action studioAction(String id, Component label, Icon icon, Variant variant, boolean enabled, boolean selected, Runnable action) {
        return new StudioComponents.Action(id, label.getString(), icon, variant, enabled, selected, "", () -> run(action));
    }

    private StudioComponents.Action menuAction(String id, String label, Icon icon, boolean enabled, String reason, Runnable action) {
        return new StudioComponents.Action(id, c(label).getString(), icon, Variant.DEFAULT, enabled, false, enabled || reason.isEmpty() ? "" : c(reason).getString(), () -> run(action));
    }

    private int listRowHeight() {
        return Math.max(StudioMetrics.compact().row(), ui.textLayout.lineHeight(TextSize.BODY) + 8);
    }

    private int anchoredListOffset(String id, StudioRowSource<?> source, String query, int requested, int contentY, int rowHeight) {
        var previous = listStates.get(id);
        if (previous == null || previous.source() == source || previous.offset() != requested || !previous.query().equals(query)) return requested;
        return StudioListScrollAnchor.capture(previous.source(), previous.window())
                .map(anchor -> StudioListScrollAnchor.restore(anchor, source, contentY, rowHeight, 2)).orElse(requested);
    }

    private <R> StudioVirtualList.Window emitVirtualRows(String id, StudioRowSource<R> source, String query,
                                                         ControlTree.Bounds list, ControlTree.Bounds body, int contentY, int rowHeight, java.util.function.IntConsumer change,
                                                         StudioVirtualList.RowRenderer<R> renderer) {
        var aperture = Objects.requireNonNull(ui.currentClip());
        int offset = aperture.y() - body.y();
        var window = StudioVirtualList.window(list, aperture, source.size(), rowHeight, 2, 2);
        ui.virtualRange(new StudioFocusNavigation.VirtualRange(id, layer, aperture, source, reveal -> {
            if (!ui.currentReveal(reveal)) return;
            change.accept(StudioVirtualList.revealOffset(reveal.rowIndex(), source.size(), contentY, rowHeight, 2, offset, aperture.height()));
            request();
        }));
        StudioVirtualList.render(source, window, renderer);
        buildingListStates.put(id, new ListState(source, window, offset, query));
        return window;
    }

    private void projectBrowser(UUID panel, int x, int y, int w, int h) {
        String id = "project-browser/" + panel, rangeId = id + "/documents";
        var content = new ArrayList<StudioView.Element>();
        if (!model.hasProject()) {
            content.add(ui.view.paragraph(id + "/intro", c("project_intro").getString(), TextSize.BODY, Variant.MUTED));
            content.add(ui.view.button(menuAction("browser/new/" + panel, "new_project", Icon.PLUS, !model.pendingCreation(), "unknown_close", () -> {
                creating = true;
                show(Page.PROJECT);
            })).style(Style.defaults()));
            content.add(ui.view.button(menuAction("browser/open/" + panel, "open_project", Icon.FOLDER, !model.pendingCreation(), "unknown_close", () -> {
                creating = false;
                show(Page.PROJECT);
            })).style(Style.defaults()));
        } else {
            content.add(ui.view.row(id + "/heading", Style.defaults().gap(4), List.of(ui.view.text(id + "/name", model.projectName()), ui.view.button(studioAction("browser/add/" + panel, c("new_document"), Icon.PLUS, Variant.DEFAULT, true, false, () -> show(Page.NEW_DOCUMENT)), Kind.ICON))));
            content.add(ui.view.input(new StudioView.Input("project-search/" + panel, c("studio_search_documents").getString(), projectSearch.getOrDefault(panel, ""), true, false, false, "", null, value -> {
                projectSearch.put(panel, value);
                projectScroll.put(panel, 0);
                request();
            }, null)));
        }
        String query = projectSearch.getOrDefault(panel, "");
        int rowHeight = listRowHeight();
        buildingListViews.add(rangeId);
        var source = listProjection.projects(rangeId, query, StudioClientResources.generation());
        if (model.hasProject()) {
            if (source.size() == 0) content.add(ui.view.paragraph(id + "/empty", c(listProjection.documents().isEmpty() ? "no_document" : "studio_no_documents").getString(), TextSize.BODY, Variant.MUTED));
            else content.add(ui.view.placeholder(rangeId, Style.defaults().height(Length.fixed(StudioVirtualList.contentHeight(source.size(), rowHeight, 2)))));
        }
        var arranged = ui.view.layoutContent(ui.view.column(id + "/content", Style.defaults().padding(Insets.all(6)).gap(4), content), 0, 0, Math.max(0, w - 8));
        int requested = projectScroll.getOrDefault(panel, 0);
        if (source.size() > 0) requested = anchoredListOffset(rangeId, source, query, requested, arranged.bounds(rangeId).y(), rowHeight);
        UUID selected = model.activeDocument().orElse(null);
        int offset = ui.components.scrollContent(id, new ControlTree.Bounds(x, y, w, Math.max(0, h)), arranged.contentHeight(), layer, requested, value -> {
            projectScroll.put(panel, value);
            request();
        }, body -> {
            var placed = arranged.translated(body.x(), body.y());
            ui.emit(placed, layer);
            if (source.size() == 0) return;
            emitVirtualRows(rangeId, source, query, placed.bounds(rangeId), body, arranged.bounds(rangeId).y(), rowHeight, value -> projectScroll.put(panel, value), (index, key, item, bounds) -> {
                UUID document = item.id();
                var handle = listProjection.documents().get(document);
                String row = "project-document/" + panel + "/" + document;
                var parts = new ArrayList<StudioView.Element>();
                parts.add(ui.view.treeRow(new StudioComponents.TreeRow(row, item.displayName() + (handle.hasUnpublishedChanges() ? " •" : ""), Icon.MAP, 0, false, false, !resourceDocument && document.equals(selected), () -> run(() -> openSceneDocument(document)), () -> {})));
                var forms = item.forms();
                if (!forms.isEmpty()) parts.add(ui.view.button(studioAction(row + "/forms", forms.size() == 1 ? component(forms.get(0).label()) : c("studio_form_documents"), Icon.SLIDERS, Variant.DEFAULT, true, false, () -> openDocumentFormsAt(document, row + "/forms")), Kind.ICON));
                ui.emit(ui.view.layout(ui.view.row(row + "/row", Style.fill().gap(4), parts), bounds), layer);
                ui.virtualTarget(row, rangeId, key, "activate");
                if (!forms.isEmpty()) ui.virtualTarget(row + "/forms", rangeId, key, "forms");
            });
        });
        projectScroll.put(panel, offset);
    }

    private void openDocumentFormsAt(UUID document, String source) {
        ui.tree.snapshot().stream().filter(node -> node.id().equals(source) && node.focusable()).map(ControlTree.Node::visibleBounds).findFirst().ifPresent(bounds -> openDocumentForms(document, bounds.x(), bounds.y() + bounds.height()));
    }

    private void objects(UUID panel, int x, int y, int w, int h) {
        UUID document = panelDocument(panel).orElseThrow();
        String state = panel + "/" + document, id = "objects/" + state, rangeId = id + "/rows";
        Set<String> collapsed = collapsedObjects.computeIfAbsent(state, key -> listProjection.initialCollapsed(document));
        String query = objectSearch.getOrDefault(state, "");
        buildingListViews.add(rangeId);
        var source = listProjection.objects(rangeId, document, collapsed, query, StudioClientResources.generation());
        if (model.selectedNode(document).isEmpty() && source.size() > 0) model.selectNode(document, source.keyAt(0));
        String selected = model.selectedNode(document).orElse("");
        int rowHeight = listRowHeight();
        var content = new ArrayList<StudioView.Element>();
        content.add(panelBindingView(panelInstances.get(panel)));
        content.add(ui.view.row(id + "/tools", Style.defaults().gap(4), List.of(
                ui.view.button(studioAction(id + "/add", c("add_object"), Icon.PLUS, Variant.DEFAULT, true, false, () -> openAddAt(document, id + "/add"))).style(Style.defaults()),
                ui.view.button(menuAction(id + "/menu", "view_menu", Icon.MORE, !selected.isEmpty(), "missing_target", () -> openObjectMenuAt(document, model.selectedNode(document).orElse(null), id + "/menu")), Kind.ICON))));
        content.add(ui.view.input(new StudioView.Input("object-search/" + panel + "/" + document, c("search_objects").getString(), query, true, false, false, "", null, value -> {
            objectSearch.put(state, value);
            objectScroll = 0;
            objectScrollByPanel.put(panel, 0);
            request();
        }, null)));
        if (source.size() == 0) content.add(ui.view.paragraph(id + "/empty", c("no_matching_objects").getString(), TextSize.BODY, Variant.MUTED));
        else content.add(ui.view.placeholder(rangeId, Style.defaults().height(Length.fixed(StudioVirtualList.contentHeight(source.size(), rowHeight, 2)))));
        var arranged = ui.view.layoutContent(ui.view.column(id + "/content", Style.defaults().padding(Insets.all(6)).gap(4), content), 0, 0, Math.max(0, w - 14));
        var shell = ui.view.layout(ui.view.column(id + "/layout", Style.fill().padding(new Insets(0, 6, 4, 0)).gap(4), List.of(ui.view.placeholder(id + "/viewport", Style.fill()), ui.view.placeholder(id + "/range", Style.defaults().height(Length.fixed(ui.textLayout.lineHeight(TextSize.SMALL)))))), new ControlTree.Bounds(x, y, w, Math.max(0, h)));
        var viewport = shell.bounds(id + "/viewport");
        if (source.size() > 0) {
            int contentY = arranged.bounds(rangeId).y();
            objectScroll = anchoredListOffset(rangeId, source, query, objectScroll, contentY, rowHeight);
            String reveal = revealObjects.get(state);
            int index = reveal == null ? -1 : source.indexOf(reveal);
            if (reveal != null) consumedObjectReveals.put(state, reveal);
            if (index >= 0) objectScroll = StudioVirtualList.revealOffset(index, source.size(), contentY, rowHeight, 2, objectScroll, viewport.height());
        }
        int[] range = { 0, 0 };
        objectScroll = ui.components.scrollContent(id + "/body", viewport, arranged.contentHeight(), layer, objectScroll, value -> {
            objectScrollByPanel.put(panel, value);
            request();
        }, body -> {
            var placed = arranged.translated(body.x(), body.y());
            ui.emit(placed, layer);
            if (source.size() == 0) return;
            var window = emitVirtualRows(rangeId, source, query, placed.bounds(rangeId), body, arranged.bounds(rangeId).y(), rowHeight, value -> objectScrollByPanel.put(panel, value), (index, key, row, bounds) -> {
                var node = row.node();
                String control = "object/" + panel + "/" + document + "/" + node.id();
                ui.emit(ui.view.layout(ui.view.treeRow(new StudioComponents.TreeRow(control, component(node.label()).getString(), nodeIcon(node), row.depth(), row.branch(), row.expanded(), node.id().equals(selected), () -> run(() -> selectTreeObject(rangeId, node.id())), () -> run(() -> {
                    toggleObjectExpansion(state, node.id(), null);
                    revealObjects.put(state, node.id());
                }))), bounds), layer);
                ui.virtualTarget(control, rangeId, key, "activate");
                objectControls.put(control, node.id());
                objectDocuments.put(control, document);
                objectPanels.put(control, panel);
                if (row.branch()) {
                    ui.virtualTarget(control + "/expand", rangeId, key, "expand");
                    objectControls.put(control + "/expand", node.id());
                    objectDocuments.put(control + "/expand", document);
                    objectPanels.put(control + "/expand", panel);
                }
            });
            if (window.visibleCount() > 0) {
                range[0] = window.firstVisible() + 1;
                range[1] = window.endVisible();
            }
            buildingObjectLists.put(rangeId, new ObjectListContext(panel, document, source));
        });
        ui.emit(ui.view.layout(ui.view.text(id + "/range-label", c("studio_object_range", range[0], range[1], source.size()).getString(), TextSize.SMALL, Variant.MUTED).style(Style.defaults().padding(new Insets(0, 0, 0, 6))), shell.bounds(id + "/range")), layer);
    }

    private void toggleObjectExpansion(String state, String node, Boolean expanded) {
        var next = new HashSet<>(collapsedObjects.getOrDefault(state, Set.of()));
        if (expanded == null) {
            if (!next.add(node)) next.remove(node);
        } else if (expanded) next.remove(node);
        else next.add(node);
        collapsedObjects.put(state, Set.copyOf(next));
    }

    private void revealObject(UUID document, String node) {
        activeWorkspace().ifPresent(workspace -> workspace.panels().stream()
                .filter(panel -> panel.type().equals(WorkspaceTemplates.OBJECTS) && panelDocument(panel).filter(document::equals).isPresent())
                .forEach(panel -> revealObjects.put(panel.id() + "/" + document, node)));
    }

    private void openAddAt(UUID document, String source) {
        ui.tree.snapshot().stream().filter(node -> node.id().equals(source) && node.focusable()).map(ControlTree.Node::visibleBounds).findFirst().ifPresent(bounds -> openAdd(document, model.selectedNode(document).orElse(null), bounds.x(), bounds.y() + bounds.height()));
    }

    private void selectObject(String id, boolean inspect) {
        selectObject(model.activeDocument().orElseThrow(), id, inspect);
    }

    private Icon nodeIcon(DocumentEditor.Node node) {
        return switch (node.symbol()) {
            case MAP -> Icon.MAP;
            case TEAM -> Icon.TEAM;
            case POINT -> Icon.POINT;
            case AREA -> Icon.AREA;
            case SHOP -> Icon.SHOP;
            case RULE -> Icon.RULE;
            default -> Icon.BOX;
        };
    }

    private void selectObject(UUID document, String id, boolean inspect) {
        guardCreation(() -> selectObjectNow(document, id, inspect, null));
    }

    private void selectObjectNow(UUID document, String id, boolean inspect, TreeFocusOrigin origin) {
        model.selectNode(document, id);
        if (model.activeDocument().filter(document::equals).isPresent()) activateDocumentView(studioGridForSelection(document, id).map(grid -> StudioDocumentTabs.Key.grid(document, grid.owner())).orElseGet(() -> StudioDocumentTabs.Key.scene(document)), origin);
        if (inspect) openInspector(document);
    }

    private boolean treeFocusCurrent(TreeFocusOrigin origin) {
        if (origin == null || page != Page.EDITOR || !Objects.equals(origin.focus(), ui.logicalFocus())) return false;
        var focus = origin.focus();
        var context = origin.context();
        return focus != null && focus.virtual() && objectLists.get(focus.rangeId()) == context && context.source().indexOf(focus.rowKey()) >= 0 && panelDocument(context.panel()).filter(context.document()::equals).isPresent() && Objects.equals(origin.activeDocument(), model.activeDocument().orElse(null));
    }

    private void selectTreeObject(String rangeId, String target) {
        var context = objectLists.get(rangeId);
        var focus = ui.logicalFocus();
        if (context == null || focus == null || !focus.virtual() || !rangeId.equals(focus.rangeId()) || context.source().indexOf(target) < 0) return;
        var origin = new TreeFocusOrigin(context, focus, model.activeDocument().orElse(null));
        if (!treeFocusCurrent(origin)) return;
        // A creation confirmation outlives this input event; it must not inherit its focus claim.
        if (objectCreation != null) {
            selectObject(context.document(), target, false);
            return;
        }
        selectObjectNow(context.document(), target, false, origin);
        if (treeFocusCurrent(origin)) ui.requestFocus(new StudioFocusNavigation.FocusRef(rangeId, target, "activate", null, Role.TREE_ITEM));
        request();
    }

    private boolean hasInspector(UUID document) {
        return document != null && activeWorkspace().stream().flatMap(workspace -> workspace.panels().stream()).anyMatch(panel -> panel.type().equals(WorkspaceTemplates.INSPECTOR) && panelDocument(panel).filter(document::equals).isPresent());
    }

    private void openInspector(UUID document) {
        if (document == null) return;
        activeWorkspace().ifPresent(workspace -> {
            for (int i = 0; i < workspace.panels().size(); i++) {
                var panel = workspace.panels().get(i);
                if (panel.type().equals(WorkspaceTemplates.INSPECTOR) && panelDocument(panel).filter(document::equals).isPresent()) {
                    focusPanel(panel.id());
                    return;
                }
            }
        });
    }

    private void openAdd(int x, int y) {
        openAdd(model.activeDocument().orElse(null), model.selectedNode().orElse(null), x, y);
    }

    private void openAdd(UUID document, String node, int x, int y) {
        catalogDocument = document;
        catalogNode = node;
        menuAnchor = x;
        menuY = y;
        show(Page.ADD_MENU);
    }

    private void openObjectMenu(int x, int y) {
        openObjectMenu(model.activeDocument().orElse(null), model.selectedNode().orElse(null), x, y);
    }

    private void openObjectMenu(UUID document, String node, int x, int y) {
        objectMenuDocument = document;
        objectMenuNode = node;
        menuAnchor = x;
        menuY = y;
        show(Page.OBJECT_MENU);
    }

    private void openObjectMenuAt(UUID document, String node, String source) {
        // Resolve after scrolling and layout: the published frame owns the visible trigger.
        ui.tree.snapshot().stream().filter(control -> control.id().equals(source) && control.focusable())
                .map(ControlTree.Node::visibleBounds).findFirst()
                .ifPresent(bounds -> openObjectMenu(document, node, bounds.x(), bounds.y() + bounds.height()));
    }

    private void objectMenu() {
        boolean target = objectMenuDocument != null && objectMenuNode != null && model.nodes(objectMenuDocument).stream().anyMatch(node -> node.id().equals(objectMenuNode));
        var entries = new ArrayList<StudioComponents.Action>();
        for (var form : objectForms()) entries.add(studioAction("object-menu/form/" + form.id(), component(form.label()), Icon.SLIDERS, Variant.DEFAULT, true, false, () -> {
            hideOverlay();
            openFormDocument(objectMenuDocument, form);
        }));
        entries.addAll(List.of(menuAction("object-menu/edit", "edit_object", Icon.SLIDERS, target && hasInspector(objectMenuDocument), "inspector_target_missing", () -> {
            hideOverlay();
            openInspector(objectMenuDocument);
        }), menuAction("object-menu/add", "add_object", Icon.NONE, target, "no_document", () -> openAdd(objectMenuDocument, objectMenuNode, dx, dy)), menuAction("object-menu/identity", "identity_details", Icon.NONE, target, "missing_target", () -> show(Page.OBJECT_INFO)), menuAction("object-menu/remove", "delete_object", Icon.DELETE, target && !model.protectedMode() && !model.unresolvedSave(), "protected_status", () -> openRemoval(objectMenuDocument, objectMenuNode))));
        ui.components.menu("object-menu", entries, new ControlTree.Bounds(dx + 8, dy + 40, dw - 16, entries.size() * 25), layer);
    }

    private void openRemoval() {
        openRemoval(model.activeDocument().orElse(null), model.selectedNode().orElse(null));
    }

    private void openRemoval(UUID document, String node) {
        removalDraft = model.beginRemoval(document, node);
        show(Page.OBJECT_REMOVE);
    }

    private void removalDialog() {
        if (removalDraft == null) return;
        var plan = removalDraft.plan();
        label(component(removalDraft.target().label()), dx + 14, dy + 40, dw - 28);
        label(plan.unavailable().map(WorkbenchScreen::component).orElse(c(plan.references().isEmpty() ? "delete_confirm_hint" : "delete_reference_hint")), dx + 14, dy + 62, dw - 28);
        label(c("delete_count").copy().append(" " + plan.objects().size()).append(" · ").append(c("delete_references")).append(" " + plan.references().size()), dx + 14, dy + 84, dw - 28);
        int cy = dy + 106, top = dy + dialogScroll + 34, bottom = top + Math.max(0, dh - 106);
        for (var node : plan.objects()) {
            if (cy + 22 > top && cy < bottom) label(component(node.label()), dx + 24, cy, dw - 48);
            cy += 22;
        }
        label(c("delete_references").copy().append(" " + plan.references().size()), dx + 14, cy + 8, dw - 28);
        cy += 32;
        for (var reference : plan.references()) {
            if (cy + 44 > top && cy < bottom) {
                label(component(reference.source().label()).copy().append(" → ").append(component(reference.target().label())), dx + 24, cy, dw - 48);
                label(Component.literal(reference.property()), dx + 24, cy + 17, dw - 48);
            }
            cy += 44;
        }
    }

    private void confirmRemoval() {
        UUID document = removalDraft.document();
        model.removeObject(removalDraft);
        removalDraft = null;
        model.selectedNode(document).ifPresent(id -> revealObject(document, id));
        hideOverlay();
    }

    private void objectInfo() {
        var node = model.nodes(objectMenuDocument).stream().filter(n -> Optional.ofNullable(objectMenuNode).filter(n.id()::equals).isPresent()).findFirst().orElse(null);
        if (node == null) {
            label(c("missing_target"), dx + 14, dy + 44, dw - 28);
            return;
        }
        label(component(node.label()), dx + 14, dy + 44, dw - 28);
        label(c("stable_identity"), dx + 14, dy + 72, dw - 28);
        label(Component.literal(node.id()), dx + 14, dy + 88, dw - 28);
        label(c("creation_parent"), dx + 14, dy + 116, dw - 28);
        label(Component.literal(node.parent().orElse("—")), dx + 14, dy + 132, dw - 28);
        label(c("document_type"), dx + 14, dy + 160, dw - 28);
        label(Component.literal(Optional.ofNullable(objectMenuDocument).map(id -> listProjection.documents().get(id).typeId().toString()).orElse("")), dx + 14, dy + 176, dw - 28);
        int cy = dy + 204;
        for (var field : identityChoices()) {
            label(component(field.label()).copy().append(" · ").append(c("identity_applied")), dx + 14, cy, dw - 28);
            int lines = font.split(Component.literal(field.raw()), Math.max(20, dw - 28)).size();
            paragraph(Component.literal(field.raw()), dx + 14, cy + 16, dw - 28, Math.max(1, lines));
            cy += 28 + 13 * lines;
        }
    }

    /** Applied values only; pending text must never masquerade as the stored object identity. */
    private List<DocumentEditor.Field> identityChoices() {
        return (objectMenuDocument == null || objectMenuNode == null ? List.<DocumentEditor.Field>of() : model.fields(objectMenuDocument, objectMenuNode)).stream().filter(field -> field.kind() == DocumentEditor.Kind.CHOICE).toList();
    }

    private int objectInfoHeight() {
        int height = 204;
        for (var field : identityChoices()) height += 28 + 13 * font.split(Component.literal(field.raw()), Math.max(20, dw - 28)).size();
        return height;
    }

    private void addCatalog() {
        catalogPopup();
    }

    private void catalogPopup() {
        dw = Math.max(220, Math.min(680, width - 16));
        dh = Math.max(180, Math.min(350, height - 16));
        dx = Math.max(8, Math.min(menuAnchor, width - dw - 8));
        dy = Math.max(8, Math.min(menuY, height - dh - 8));
        ui.panel("add/catalog", dx, dy, dw, dh, layer);
        ui.heading(c("add_object"), dx + 10, dy + 10, dw - 48, layer);
        action("add/close", Component.literal("×"), dx + dw - 28, dy + 5, 22, this::cancelOverlay);
        ui.edit("add-search", c("search_creators"), addSearch, dx + 10, dy + 31, dw - 20, layer, true, value -> {
            addSearch = value;
            addScroll = 0;
            request();
        });
        var all = catalogDocument == null ? activeCreationChoices() : projectedCreationChoices(catalogDocument, catalogNode);
        var categories = new LinkedHashMap<String, Component>();
        all.forEach(choice -> categories.putIfAbsent(component(choice.category()).getString(), component(choice.category())));
        if (!addCategory.isEmpty() && !categories.containsKey(addCategory)) addCategory = "";
        int categoryWidth = dw >= 560 ? 120 : 0, listWidth = dw >= 560 ? 220 : Math.max(90, (dw - 30) / 2), listX = dx + 10 + categoryWidth, detailX = listX + listWidth + 10, detailWidth = dx + dw - 12 - detailX;
        int top = dy + 60, bottom = dy + dh - 57, rows = Math.max(1, (bottom - top - 24) / 38);
        ui.clip(new ControlTree.Bounds(dx + 6, top, dw - 12, Math.max(0, bottom - top)));
        if (categoryWidth > 0) {
            tab("add/category/all", c("catalog_all"), dx + 8, top, categoryWidth - 12, addCategory.isEmpty(), () -> {
                addCategory = "";
                addScroll = 0;
            });
            var categoryList = new ArrayList<>(categories.entrySet());
            int categoryRows = Math.max(1, (bottom - top - 49) / 25);
            addCategoryScroll = Math.max(0, Math.min(addCategoryScroll, Math.max(0, categoryList.size() - categoryRows)));
            for (int i = addCategoryScroll; i < Math.min(categoryList.size(), addCategoryScroll + categoryRows); i++) {
                var category = categoryList.get(i);
                String key = category.getKey();
                tab("add/category/" + key, category.getValue(), dx + 8, top + 25 + (i - addCategoryScroll) * 25, categoryWidth - 12, key.equals(addCategory), () -> {
                    addCategory = key;
                    addScroll = 0;
                });
            }
            if (categoryList.size() > categoryRows) scroll("add/categories", dx + 8, bottom - 22, categoryWidth - 12, () -> addCategoryScroll -= categoryRows, () -> addCategoryScroll += categoryRows);
        }
        String query = addSearch.strip().toLowerCase(Locale.ROOT);
        var choices = all.stream().filter(choice -> addCategory.isEmpty() || component(choice.category()).getString().equals(addCategory)).filter(choice -> (component(choice.label()).getString() + " " + component(choice.category()).getString() + " " + choice.owner()).toLowerCase(Locale.ROOT).contains(query)).toList();
        if (choices.stream().noneMatch(choice -> choice.id().equals(selectedCreator))) selectedCreator = choices.isEmpty() ? null : choices.get(0).id();
        addScroll = Math.max(0, Math.min(addScroll, Math.max(0, choices.size() - rows)));
        for (int i = addScroll; i < Math.min(choices.size(), addScroll + rows); i++) {
            var choice = choices.get(i);
            int y = top + (i - addScroll) * 38;
            tab("object-type/" + choice.id(), component(choice.label()), listX, y, listWidth, choice.id().equals(selectedCreator), () -> selectedCreator = choice.id());
            label(Component.literal(choice.owner().toString()), listX + 5, y + 21, listWidth - 10);
        }
        if (choices.isEmpty()) paragraph(c("no_creators"), listX, top + 8, listWidth, 4);
        if (choices.size() > rows) scroll("add/list", listX, bottom - 22, listWidth, () -> addScroll -= rows, () -> addScroll += rows);
        var choice = choices.stream().filter(value -> value.id().equals(selectedCreator)).findFirst().orElse(null);
        boolean enabled = choice != null && choice.target().parent().isPresent() && !model.protectedMode() && !model.unresolvedSave();
        if (choice != null) {
            ui.heading(component(choice.label()), detailX, top + 2, detailWidth, layer);
            paragraph(component(choice.category()), detailX, top + 25, detailWidth, 2);
            paragraph(c("catalog_configure_hint"), detailX, top + 58, detailWidth, 4);
            Component target = choice.target().parent().map(parent -> model.nodes(catalogDocument).stream().filter(node -> node.id().equals(parent)).map(node -> component(node.label())).findFirst().orElse(Component.literal(parent))).orElseGet(() -> component(choice.target().unavailable().orElse(ProjectWorkbench.text("missing_target"))));
            paragraph(c("creation_parent").copy().append(" ").append(target), detailX, top + 116, detailWidth, 4);
        }
        ui.clip(null);
        label(Optional.ofNullable(catalogDocument).map(listProjection.documents()::get).<Component>map(handle -> Component.literal(handle.displayName())).orElse(c("no_document")), dx + 10, dy + dh - 47, dw - 20);
        action("add/configure", c("configure_object"), dx + dw - 142, dy + dh - 27, 130, () -> configureObject(choice), enabled, choice == null ? "no_creators" : choice.target().parent().isEmpty() ? "missing_target" : "protected_status");
    }

    private void configureObject(ProjectWorkbench.CreationChoice choice) {
        if (objectCreation != null) {
            guardCreation(() -> configureObject(choice));
            return;
        }
        creationAttachTo = null;
        objectCreation = model.beginObjectCreation(catalogDocument, catalogNode, choice.id());
        creationError = null;
        creationLabel = component(choice.label());
        creationRaw.clear();
        objectCreation.fields().forEach(f -> creationRaw.put(f.property(), f.raw()));
        creationScroll = 0;
        hideOverlay();
        openCreationPanel();
    }

    private void openCreationPanel() {
        if (objectCreation == null) return;
        var workspace = activeWorkspace().orElse(null);
        if (workspace != null && workspace.panels().stream().noneMatch(panel -> panel.type().equals(WorkspaceTemplates.INSPECTOR)) && canAddPanel(WorkspaceTemplates.INSPECTOR)) {
            workspaces.addPanel(workspace.id(), WorkspaceTemplates.INSPECTOR);
            workspace = activeWorkspace().orElseThrow();
        }
        var candidates = workspace == null ? List.<PanelInstance>of() : workspace.panels().stream().filter(panel -> panel.type().equals(WorkspaceTemplates.INSPECTOR)).toList();
        if (candidates.isEmpty()) {
            creationPanelTarget = null;
            show(Page.OBJECT_CREATE);
            return;
        }
        var selected = candidates.stream().filter(panel -> panelDocument(panel).filter(objectCreation.document()::equals).isPresent()).findFirst().orElse(candidates.get(0));
        creationPanelTarget = selected.id();
        focusPanel(selected.id());
        request();
    }

    private void creationPanel(UUID panel, int x, int y, int w, int h) {
        var draft = objectCreation;
        Component document = Optional.ofNullable(listProjection.documents().get(draft.document())).<Component>map(handle -> Component.literal(handle.displayName())).orElseGet(() -> c("missing_target").copy().append(" " + draft.document()));
        Component parent = model.nodes(draft.document()).stream().filter(node -> node.id().equals(draft.parent())).map(node -> component(node.label())).findFirst().orElseGet(() -> c("missing_target").copy().append(" " + draft.parent()));
        String prefix = "object-create/" + panel + "/" + draft.identity();
        var content = new ArrayList<StudioView.Element>();
        content.add(ui.view.paragraph(prefix + "/title", c("create_object").copy().append(" · ").append(creationLabel).getString(), TextSize.HEADING, Variant.DEFAULT));
        content.add(ui.view.paragraph("object-create/context/" + panel + "/" + draft.identity(), document.copy().append(" · ").append(c("creation_parent")).append(" ").append(parent).getString(), TextSize.SMALL, Variant.MUTED));
        for (var row : creationFieldRows(prefix)) if (!row.isEmpty()) content.add(ui.view.fields(row.get(0).id() + "/row", row, null));
        if (creationError != null) content.add(ui.view.paragraph(prefix + "/error", creationError.getString(), TextSize.SMALL, Variant.DANGER));
        var actions = ui.view.wrap(prefix + "/actions", Style.defaults().padding(Insets.all(6)).gap(6), List.of(
                ui.view.button(studioAction("object/create/" + panel, c("create_object"), Icon.PLUS, Variant.PRIMARY, editableFields(), false, this::confirmObjectCreation)),
                ui.view.button(studioAction("object/create-cancel/" + panel, c("cancel"), Icon.NONE, Variant.DEFAULT, true, false, this::clearObjectCreation))));
        var shell = ui.view.layout(ui.view.column(prefix + "/panel", Style.fill(), List.of(ui.view.placeholder(prefix + "/viewport", Style.fill()), actions)), new ControlTree.Bounds(x, y, w, Math.max(0, h)));
        ui.emit(shell, layer);
        var body = shell.bounds(prefix + "/viewport");
        var arranged = ui.view.layoutContent(ui.view.column(prefix + "/content", Style.defaults().padding(Insets.all(8)).gap(8), content), 0, 0, Math.max(0, body.width() - 8));
        creationScroll = ui.components.scrollContent(prefix + "/body", body, arranged.contentHeight(), layer, creationScroll, value -> {
            creationScroll = value;
            request();
        }, area -> ui.emit(arranged.translated(area.x(), area.y()), layer));
    }

    private void objectCreateForm() {
        if (objectCreation == null) return;
        label(creationLabel.copy().append(" · ").append(Optional.ofNullable(listProjection.documents().get(objectCreation.document())).map(handle -> handle.displayName()).orElse(objectCreation.document().toString())), dx + 14, dy + 42, dw - 28);
        String parent = model.nodes(objectCreation.document()).stream().filter(n -> n.id().equals(objectCreation.parent())).map(n -> component(n.label()).getString()).findFirst().orElse(objectCreation.parent());
        label(c("creation_parent").copy().append(" " + parent), dx + 14, dy + 61, dw - 28);
        creationFields("object-create/" + objectCreation.identity(), new ControlTree.Bounds(dx + 8, dy + 84, Math.max(0, dw - 16), Math.max(0, dh - 133)));
    }

    private void creationFields(String prefix, ControlTree.Bounds bounds) {
        creationScroll = ui.components.formRows(prefix, creationFieldRows(prefix), bounds, layer, creationScroll, value -> {
            creationScroll = value;
            request();
        });
    }

    private List<List<StudioComponents.Field>> creationFieldRows(String prefix) {
        var rows = new ArrayList<List<StudioComponents.Field>>();
        for (var page : InspectorPresentation.pages(objectCreation.fields())) for (var group : page.groups()) for (var row : group.rows()) {
            var fields = new ArrayList<StudioComponents.Field>();
            for (var field : row.fields()) {
                String key = prefix + "/" + field.property(), raw = creationRaw.getOrDefault(field.property(), field.raw());
                var kind = switch (field.kind()) {
                    case CHOICE -> StudioComponents.FieldKind.CHOICE;
                    case BOOLEAN -> StudioComponents.FieldKind.TOGGLE;
                    default -> StudioComponents.FieldKind.TEXT;
                };
                String display = kind == StudioComponents.FieldKind.CHOICE ? field.choices().stream().filter(choice -> choice.raw().equals(raw)).map(choice -> component(choice.label()).getString()).findFirst().orElse(raw) : kind == StudioComponents.FieldKind.TOGGLE ? c(Boolean.parseBoolean(raw) ? "boolean_on" : "boolean_off").getString() : raw;
                String label = component(row.fields().size() > 1 ? field.presentation().map(DocumentEditor.Presentation::compactLabel).orElse(field.label()) : field.label()).getString();
                fields.add(new StudioComponents.Field(key, label, raw, display, component(field.unit()).getString(), kind, ui.theme.muted(), editableFields(), false, value -> creationRaw.put(field.property(), value), () -> run(() -> {
                    if (kind == StudioComponents.FieldKind.CHOICE) openCreationChoices(field, key);
                    else creationRaw.put(field.property(), Boolean.toString(!Boolean.parseBoolean(raw)));
                }), () -> {}));
            }
            rows.add(fields);
        }
        return rows;
    }

    private void guardCreation(Runnable continuation) {
        if (objectCreation == null) {
            continuation.run();
            return;
        }
        creationContinuation = continuation;
        show(Page.CREATION_SWITCH);
    }

    private void finishCreationSwitch(boolean apply) {
        Runnable continuation = creationContinuation;
        if (apply) confirmObjectCreation();
        else {
            clearObjectCreation();
            hideOverlay();
        }
        creationContinuation = null;
        if (continuation != null) continuation.run();
    }

    private void stayWithCreation() {
        creationContinuation = null;
        hideOverlay();
        if (objectCreation != null) openCreationPanel();
    }

    private void clearObjectCreation() {
        model.cancelObjectCreation();
        objectCreation = null;
        creationAttachTo = null;
        creationError = null;
        creationPanelTarget = null;
        creationRaw.clear();
        ui.releaseText("object-create/");
    }

    private void confirmObjectCreation() {
        var draft = objectCreation;
        var attach = creationAttachTo;
        try {
            if (attach == null) model.createObject(draft, creationRaw);
            else model.createObject(draft, creationRaw, attach);
        } catch (RuntimeException failure) {
            creationError = failure instanceof SessionRejection rejected && !rejected.problems().isEmpty() ? component(rejected.problems().get(0).message()) : c("failed");
            throw failure;
        }
        objectCreation = null;
        creationAttachTo = null;
        creationError = null;
        creationPanelTarget = null;
        creationRaw.clear();
        ui.releaseText("object-create/");
        if (attach != null) {
            model.selectNode(draft.document(), attach.node());
            for (var panel : activeWorkspace().stream().flatMap(workspace -> workspace.panels().stream()).filter(p -> p.type().equals(WorkspaceTemplates.INSPECTOR)).toList()) inspectorCollapsed.computeIfAbsent(panel.id() + "/" + draft.document() + "/" + attach.node(), ignored -> new HashSet<>()).remove("studio-section/" + draft.identity());
            fieldScroll = 0;
            hideOverlay();
            openInspector(draft.document());
            return;
        }
        UUID doc = draft.document();
        var hierarchy = new ObjectHierarchy(model.nodes(doc));
        for (var panel : panelInstances.values()) if (panel.type().equals(WorkspaceTemplates.OBJECTS) && panelDocument(panel.id()).filter(doc::equals).isPresent()) {
            String state = panel.id() + "/" + doc;
            Set<String> collapsed = new HashSet<>(collapsedObjects.getOrDefault(state, Set.of()));
            collapsed.removeAll(hierarchy.ancestors(draft.identity().toString()));
            collapsedObjects.put(state, Set.copyOf(collapsed));
            objectSearch.put(state, "");
        }
        ui.releaseText("object-search/");
        revealObject(doc, draft.identity().toString());
        fieldScroll = 0;
        hideOverlay();
        openInspector(doc);
    }

    private void inspector(UUID panel, int x, int y, int w, int h) {
        UUID document = panelDocument(panel).orElseThrow();
        var handle = listProjection.documents().get(document);
        var state = handle.view();
        String selected = model.selectedNode(document).orElse("");
        // Only transform-only requests preserve this revision. Every ordinary request wins,
        // including a blur commit triggered by pressing the scrollbar in the same event.
        var revision = List.of(contentEpoch, model.version(), worldTarget.version(), projectWorld.version(),
                installations.version(), tests.version(), handle, state.token(), state.pendingInputs(),
                panelInstances.get(panel).binding(), selected, themeName, ui.textLayout.generation(),
                net.minecraft.locale.Language.getInstance(), w, page, drawer, worldSession.hasNative(),
                Optional.ofNullable(revealField), model.resources().map(value -> value.token()));
        buildingContentDocuments.add(document);
        if (!selected.isEmpty()) buildingInspectorViews.add("inspector/" + panel);
        var content = inspectorPublication.content(panel.toString(), revision, () -> buildInspectorContent(panel, document));
        inspectorContent(panel, content.id(), new ControlTree.Bounds(x, y, w, Math.max(0, h)), content.elements(), revealField);
    }

    private InspectorContent buildInspectorContent(UUID panel, UUID document) {
        if (model.selectedNode(document).isEmpty()) {
            return new InspectorContent("inspector/" + panel + "/" + document + "/empty", List.of(panelBindingView(panelInstances.get(panel)), ui.view.paragraph("inspector/" + panel + "/empty", c("no_fields").getString())));
        }
        String node = model.selectedNode(document).orElseThrow(), state = panel + "/" + document + "/" + node;
        String viewKey = "inspector/" + panel;
        var projection = inspectorProjection.selection(viewKey, document, node);
        buildingInspectorViews.add(viewKey);
        var path = projection.path().stream().map(value -> component(value.label()).getString()).toList();
        var sections = projection.sections();
        boolean inlineSelection = sections.stream().anyMatch(section -> section.target().id().equals(node) || section.children().stream().anyMatch(child -> child.id().equals(node)));
        var references = projection.references();
        var pages = inlineSelection ? List.<InspectorPresentation.Page>of() : projection.pages(node);
        String firstPage = pages.isEmpty() ? "studio-inline" : pages.get(0).id();
        String active = inspectorPages.getOrDefault(state, pages.isEmpty() && sections.isEmpty() ? "resources" : firstPage);
        if (revealField != null && revealField.document().equals(document) && revealField.node().equals(node)) for (var p : pages) for (var g : p.groups()) for (var r : g.rows())
            if (r.fields().stream().anyMatch(f -> f.property().equals(revealField.property()))) active = p.id();
        if (revealField != null && revealField.document().equals(document) && sections.stream().anyMatch(section -> section.target().id().equals(revealField.node()) || section.children().stream().anyMatch(child -> child.id().equals(revealField.node())))) active = firstPage;
        inspectorPages.put(state, active);
        var pageActions = new ArrayList<StudioComponents.Action>();
        if (pages.isEmpty() && !sections.isEmpty()) pageActions.add(studioAction("inspector/" + panel + "/page/studio-inline", c("studio_inline_properties"), Icon.NONE, Variant.DEFAULT, true, firstPage.equals(active), () -> {
            inspectorPages.put(state, firstPage);
            resetInspectorScroll(panel);
        }));
        for (var p : pages) {
            String target = p.id();
            pageActions.add(studioAction("inspector/" + panel + "/page/" + target, component(p.label()), Icon.NONE, Variant.DEFAULT, true, target.equals(active), () -> {
                inspectorPages.put(state, target);
                resetInspectorScroll(panel);
            }));
        }
        pageActions.add(studioAction("inspector/" + panel + "/page/resources", c("studio_resources"), Icon.NONE, Variant.DEFAULT, true, active.equals("resources"), () -> {
            inspectorPages.put(state, "resources");
            resetInspectorScroll(panel);
        }));
        var header = inspectorHeader(panel, document, node, component(projection.node().label()), path, pageActions);
        String scrollContext = "inspector/" + panel + "/" + document + "/" + node + "/" + active;
        if (active.equals("resources")) return inspectorResources(scrollContext, panel, document, header);
        String query = inspectorSearch.getOrDefault(panel, "").strip().toLowerCase(Locale.ROOT);
        Set<String> collapsed = inspectorCollapsed.computeIfAbsent(state, ignored -> {
            Set<String> initial = new HashSet<>();
            for (var section : sections) if (section.library()) initial.add("studio-section/" + section.id());
            return initial;
        });
        List<InspectorItem> items = new ArrayList<>();
        for (var p : pages) if (p.id().equals(active)) for (var group : p.groups()) {
            var rows = group.rows().stream().filter(row -> query.isEmpty() || component(group.label()).getString().toLowerCase(Locale.ROOT).contains(query) || row.fields().stream().anyMatch(f -> component(f.label()).getString().toLowerCase(Locale.ROOT).contains(query))).toList();
            if (rows.isEmpty()) continue;
            if (!component(group.label()).getString().isBlank()) items.add(new InspectorItem(group, null, null));
            if (query.isEmpty() && collapsed.contains(group.id())) continue;
            for (var row : rows) items.add(new InspectorItem(group, row, null));
        }
        if (active.equals(firstPage)) {
            var itemId = projectedGrid(document, node).stream().flatMap(grid -> grid.cells().stream()).filter(cell -> cell.node().filter(node::equals).isPresent()).flatMap(cell -> cell.item().stream()).findFirst();
            if (itemId.isPresent()) {
                String target = itemId.orElseThrow();
                String groupId = "studio-product/" + target;
                var itemNode = projection.node(target);
                if (itemNode.isPresent()) {
                    items.add(new InspectorItem(new InspectorPresentation.Group(groupId, itemNode.orElseThrow().label(), List.of()), null, null, target, null));
                    if (!query.isEmpty() || !collapsed.contains(groupId)) for (var itemPage : projection.pages(target)) for (var group : itemPage.groups()) for (var row : group.rows()) items.add(new InspectorItem(group, row, null, target, null));
                }
            }
        }
        if (active.equals(firstPage)) for (var reference : references)
            if (query.isEmpty() || component(reference.label()).getString().toLowerCase(Locale.ROOT).contains(query)) items.add(new InspectorItem(null, null, reference));
        if (active.equals(firstPage)) for (var section : sections) {
            String sectionKey = "studio-section/" + section.id();
            if (revealField != null && revealField.document().equals(document) && (revealField.node().equals(section.target().id()) || section.children().stream().anyMatch(child -> child.id().equals(revealField.node())))) collapsed.remove(sectionKey);
            items.add(new InspectorItem(null, null, null, section.target().id(), section));
            if (query.isEmpty() && collapsed.contains(sectionKey)) continue;
            var targets = new ArrayList<DocumentEditor.Node>();
            targets.add(section.target());
            targets.addAll(section.children());
            for (var target : targets) {
                if (!target.id().equals(section.target().id())) {
                    String targetGroup = "studio-child/" + section.id() + "/" + target.id();
                    if (revealField != null && revealField.document().equals(document) && revealField.node().equals(target.id())) collapsed.remove(targetGroup);
                    items.add(new InspectorItem(new InspectorPresentation.Group(targetGroup, target.label(), List.of()), null, null, target.id(), null));
                    if (query.isEmpty() && collapsed.contains(targetGroup)) continue;
                }
                for (var inlinePage : projection.pages(target.id())) for (var group : inlinePage.groups()) for (var row : group.rows()) if (query.isEmpty() || component(target.label()).getString().toLowerCase(Locale.ROOT).contains(query) || row.fields().stream().anyMatch(field -> component(field.label()).getString().toLowerCase(Locale.ROOT).contains(query))) items.add(new InspectorItem(group, row, null, target.id(), null));
            }
        }
        if (active.equals(firstPage)) {
            int insert = items.size();
            for (int i = 0; i < items.size(); i++) if (items.get(i).section() != null && items.get(i).section().library()) {
                insert = i;
                break;
            }
            for (var reference : references) for (var creator : projectedReferenceChoices(new ProjectWorkbench.Address(document, node, reference.property()))) items.add(insert++, new InspectorItem(null, null, reference, node, null, creator));
            if (sections.stream().anyMatch(StudioDocument.Section::library)) items.add(insert, new InspectorItem(null, null, null, null, null, null, new StudioComponents.SectionIntro(c("studio_reuse_modules").getString(), c("studio_reuse_modules_hint").getString(), "")));
        }
        var content = new ArrayList<StudioView.Element>();
        content.add(header);
        for (int i = 0; i < items.size(); i++) {
            var item = items.get(i);
            if (item.section() != null) {
                var section = item.section();
                var members = new ArrayList<StudioView.Element>();
                members.add(moduleHeaderView("inspector/" + panel + "/studio-section/" + section.id(), moduleHeader(panel, document, node, state, section, references, collapsed)));
                while (i + 1 < items.size() && continuesModule(items.get(i + 1))) members.add(inspectorItem(panel, document, node, items.get(++i), collapsed, projection));
                content.add(ui.view.surface("inspector/" + panel + "/card/" + section.id(), section.library() ? Kind.SECTION : Kind.INLINE_CARD, Style.defaults().padding(Insets.all(6)).gap(6), members));
            } else content.add(inspectorItem(panel, document, node, item, collapsed, projection));
        }
        if (items.isEmpty()) content.add(ui.view.paragraph("inspector/" + panel + "/no-fields", c("no_fields").getString(), TextSize.BODY, Variant.MUTED));
        return new InspectorContent(scrollContext, content);
    }

    private StudioView.Element panelBindingView(PanelInstance panel) {
        boolean pinned = panel.binding().mode() == PanelBinding.Mode.PINNED;
        var title = pinned ? c("panel_pinned").copy().append(" · ").append(Optional.ofNullable(listProjection.documents().get(panel.binding().targetDocument())).map(handle -> handle.displayName()).orElse(panel.binding().targetDocument().toString())) : c("follow_active");
        return ui.view.button(studioAction("panel-binding/" + panel.id(), title, Icon.NONE, Variant.MUTED, pinned || model.activeDocument().isPresent(), false, () -> guardCreation(() -> togglePanelBinding(panel.id())))).style(Style.defaults());
    }

    private StudioView.Element inspectorHeader(UUID panel, UUID document, String node, Component title, List<String> path, List<StudioComponents.Action> pages) {
        String id = "inspector/" + panel;
        return ui.view.column(id + "/header", Style.defaults().gap(6), List.of(
                panelBindingView(panelInstances.get(panel)),
                ui.view.row(id + "/identity-row", Style.defaults().gap(4), List.of(ui.view.paragraph(id + "/title", title.getString(), TextSize.HEADING, Variant.DEFAULT), ui.view.button(studioAction(id + "/identity", c("studio_section_actions"), Icon.MORE, Variant.DEFAULT, true, false, () -> openObjectMenuAt(document, node, id + "/identity")), Kind.ICON))),
                ui.view.paragraph(id + "/path", String.join(" › ", path), TextSize.SMALL, Variant.MUTED),
                ui.view.wrap(id + "/pages", Style.defaults().gap(3), pages.stream().map(action -> ui.view.button(action, Kind.TAB)).toList()),
                ui.view.input(new StudioView.Input("inspector-search/" + panel, c("search_fields").getString(), inspectorSearch.getOrDefault(panel, ""), true, false, false, "", null, value -> {
                    inspectorSearch.put(panel, value);
                    resetInspectorScroll(panel);
                }, null))));
    }

    private void inspectorContent(UUID panel, String id, ControlTree.Bounds viewport, List<StudioView.Element> content, ProjectWorkbench.Address reveal) {
        var arranged = ui.view.layoutContent(ui.view.column(id + "/content", Style.defaults().padding(Insets.all(6)).gap(8), content), 0, 0, Math.max(0, viewport.width() - 8));
        var children = arranged.geometry().root().children();
        int total = arranged.contentHeight();
        int[] heights = new int[children.size()];
        for (int i = 0; i < heights.length; i++) heights[i] = (i + 1 < heights.length ? children.get(i + 1).bounds().y() : total) - (i == 0 ? 0 : children.get(i).bounds().y());
        int offset = inspectorContentOffset(panel, heights, -1, viewport.height());
        if (reveal != null) {
            String control = "field/" + panel + "/" + reveal.document() + "/" + reveal.pendingKey();
            if (arranged.geometry().byId().containsKey(control)) {
                var bounds = arranged.bounds(control);
                offset = StudioFormDocument.revealOffset(offset, bounds.y(), bounds.height(), viewport.height(), total);
                revealField = null;
            }
        }
        int remaining = offset;
        fieldScroll = 0;
        while (fieldScroll < heights.length - 1 && remaining >= heights[fieldScroll]) remaining -= heights[fieldScroll++];
        fieldRowOffset.put(panel, remaining);
        fieldScrollByPanel.put(panel, fieldScroll);
        ui.components.scrollContent(id, viewport, total, layer, offset, value -> scrollInspectorTo(panel, heights, value), body -> ui.emit(arranged.translated(body.x(), body.y()), layer));
    }

    private StudioView.Element moduleHeaderView(String id, StudioComponents.CardHeader header) {
        var children = new ArrayList<StudioView.Element>();
        children.add(ui.view.button(new StudioComponents.Action(id, header.title(), header.expanded() ? Icon.CHEVRON_DOWN : Icon.CHEVRON_RIGHT, Variant.DEFAULT, true, false, "", header.toggle())).style(Style.defaults()));
        if (!header.description().isBlank()) children.add(ui.view.paragraph(id + "/description", header.description(), TextSize.SMALL, Variant.MUTED));
        for (int row = 0; row < header.rows().size(); row++) children.add(ui.view.wrap(id + "/actions/" + row, Style.defaults().gap(3), header.rows().get(row).stream().map(action -> ui.view.button(action, action.icon() == Icon.MORE ? Kind.ICON : Kind.BUTTON)).toList()));
        return ui.view.column(id + "/header", Style.defaults().gap(5), children);
    }

    private StudioView.Element sectionIntroView(String id, StudioComponents.SectionIntro intro) {
        var children = new ArrayList<StudioView.Element>();
        children.add(ui.view.paragraph(id + "/title", intro.title(), TextSize.HEADING, Variant.DEFAULT));
        if (!intro.note().isBlank()) children.add(ui.view.paragraph(id + "/note", intro.note(), TextSize.SMALL, Variant.MUTED));
        if (!intro.badge().isBlank()) children.add(ui.view.paragraph(id + "/badge", intro.badge(), TextSize.SMALL, Variant.MUTED));
        return ui.view.column(id, Style.defaults().gap(4), children);
    }

    private StudioView.Element inspectorItem(UUID panel, UUID document, String node, InspectorItem item, Set<String> collapsed, WorkbenchInspectorProjection.Selection projection) {
        if (item.intro() != null) return sectionIntroView("inspector/" + panel + "/library", item.intro());
        if (item.creator() != null) {
            var address = new ProjectWorkbench.Address(document, node, item.reference().property());
            return ui.view.button(studioAction("studio/module-create/" + panel + "/" + item.reference().property() + "/" + item.creator().id(), component(item.creator().label()), Icon.PLUS, Variant.OUTLINED, editableFields() && item.creator().target().parent().isPresent(), false, () -> guardCreation(() -> {
                objectCreation = model.beginObjectCreation(document, node, item.creator().id());
                creationAttachTo = address;
                creationLabel = component(item.creator().label());
                creationError = null;
                creationRaw.clear();
                objectCreation.fields().forEach(field -> creationRaw.put(field.property(), field.raw()));
                creationScroll = 0;
                openCreationPanel();
            }))).style(Style.defaults());
        }
        if (item.reference() != null) {
            var reference = item.reference();
            String id = "inspector/" + panel + "/reference/" + reference.property();
            if (projectedGrid(document, node).isPresent()) return sectionIntroView(id, moduleIntro(reference));
            return ui.view.column(id, Style.defaults().gap(4), List.of(
                    ui.view.paragraph(id + "/title", component(reference.label()).getString() + " · " + reference.selected().size(), TextSize.SMALL, Variant.MUTED),
                    ui.view.button(studioAction("references/" + panel + "/" + reference.property(), c("manage_references"), Icon.NONE, Variant.OUTLINED, editableFields(), false, () -> openReferences(new ProjectWorkbench.Address(document, node, reference.property())))).style(Style.defaults())));
        }
        if (item.row() == null) {
            String group = item.group().id();
            return ui.view.button(studioAction("inspector/" + panel + "/group/" + group, component(item.group().label()), collapsed.contains(group) ? Icon.CHEVRON_RIGHT : Icon.CHEVRON_DOWN, Variant.DEFAULT, true, false, () -> { if (!collapsed.add(group)) collapsed.remove(group); })).style(Style.defaults());
        }
        String target = item.target() == null ? node : item.target();
        var prepared = prepareFieldRow(panel.toString(), item.row(), document, target, address -> revealField = address, projection.fields(target));
        return ui.view.fields(prepared.id(), prepared.fields(), prepared.actions());
    }

    private void resetInspectorScroll(UUID panel) {
        fieldScroll = 0;
        fieldScrollByPanel.put(panel, 0);
        fieldRowOffset.remove(panel);
        request();
    }

    private int inspectorContentOffset(UUID panel, int[] heights, int anchor, int viewportHeight) {
        int index = Math.max(0, Math.min(fieldScroll, heights.length - 1));
        long requested = Math.max(0, fieldRowOffset.getOrDefault(panel, 0));
        for (int i = 0; i < index; i++) requested += heights[i];
        int total = Arrays.stream(heights).sum(), offset = (int) Math.min(requested, total);
        if (anchor >= 0) {
            int top = 0;
            for (int i = 0; i < anchor; i++) top += heights[i];
            int bottom = top + heights[anchor];
            if (top < offset || heights[anchor] > viewportHeight) offset = top;
            else if (bottom > offset + viewportHeight) offset = bottom - viewportHeight;
        }
        int maximum = Math.max(0, total - Math.max(0, viewportHeight));
        offset = Math.max(0, Math.min(offset, maximum));
        int remaining = offset;
        fieldScroll = 0;
        while (fieldScroll < heights.length - 1 && remaining >= heights[fieldScroll]) remaining -= heights[fieldScroll++];
        fieldRowOffset.put(panel, remaining);
        fieldRowOverflow.put(panel, maximum);
        return offset;
    }

    private boolean continuesModule(InspectorItem item) {
        return item.section() == null && item.creator() == null && item.reference() == null && item.intro() == null;
    }

    private StudioComponents.SectionIntro moduleIntro(DocumentEditor.ReferenceList reference) {
        return new StudioComponents.SectionIntro(c("studio_modules").getString(), c("studio_modules_shared").getString(), c("studio_modules_count", reference.selected().size()).getString());
    }

    private StudioComponents.CardHeader moduleHeader(UUID panel, UUID document, String node, String state, StudioDocument.Section section, List<DocumentEditor.ReferenceList> references, Set<String> collapsed) {
        String key = "studio-section/" + section.id(), menuId = "studio/section-target/" + panel + "/" + section.id();
        var rows = new ArrayList<List<StudioComponents.Action>>();
        var commands = new ArrayList<StudioComponents.Action>();
        commands.add(studioAction("studio/section-references/" + panel + "/" + section.id(), c("studio_usage").copy().append(" · " + section.referencedBy().size()), Icon.NONE, Variant.DEFAULT, true, false, () -> {
            String usage = state + "/" + section.id();
            if (!inlineUsageExpanded.add(usage)) inlineUsageExpanded.remove(usage);
        }));
        commands.add(studioAction(menuId, c(section.library() ? "studio_library_actions" : "studio_section_actions"), Icon.MORE, Variant.DEFAULT, true, false, () -> openObjectMenuAt(document, section.target().id(), menuId)));
        references.stream().filter(reference -> reference.candidates().stream().anyMatch(choice -> choice.raw().equals(section.target().id()))).findFirst().ifPresent(reference -> {
            String target = section.target().id();
            boolean attached = reference.selected().contains(target);
            int index = reference.selected().indexOf(target);
            commands.add(studioAction("studio/module-toggle/" + panel + "/" + section.id(), c(attached ? "studio_module_detach" : "studio_module_attach"), attached ? Icon.MINUS : Icon.PLUS, Variant.DEFAULT, editableFields(), false, () -> changeInlineReference(document, node, reference.property(), target, 0)));
            commands.add(studioAction("studio/module-up/" + panel + "/" + section.id(), c("move_up"), Icon.UP, Variant.DEFAULT, editableFields() && index > 0, false, () -> changeInlineReference(document, node, reference.property(), target, -1)));
            commands.add(studioAction("studio/module-down/" + panel + "/" + section.id(), c("move_down"), Icon.DOWN, Variant.DEFAULT, editableFields() && index >= 0 && index < reference.selected().size() - 1, false, () -> changeInlineReference(document, node, reference.property(), target, 1)));
        });
        rows.add(commands);
        if (inlineUsageExpanded.contains(state + "/" + section.id())) for (var reference : section.referencedBy()) rows.add(List.of(studioAction("studio/usage/" + panel + "/" + section.id() + "/" + reference.id(), component(reference.label()), Icon.NONE, Variant.DEFAULT, true, false, () -> selectObject(document, reference.id(), true))));
        return new StudioComponents.CardHeader(component(section.target().label()).getString(), component(section.context()).getString(), !collapsed.contains(key), !section.library(), rows, () -> run(() -> { if (!collapsed.add(key)) collapsed.remove(key); }));
    }

    private void scrollInspectorTo(UUID panel, int[] heights, int pixel) {
        int index = 0, remaining = Math.max(0, pixel);
        while (index < heights.length - 1 && remaining >= heights[index]) remaining -= heights[index++];
        fieldScrollByPanel.put(panel, index);
        fieldRowOffset.put(panel, remaining);
        requestFrame();
    }

    private void changeInlineReference(UUID document, String node, String property, String target, int direction) {
        var draft = model.beginReferences(new ProjectWorkbench.Address(document, node, property));
        try {
            var selected = new ArrayList<>(draft.list().selected());
            int index = selected.indexOf(target);
            if (direction == 0) {
                if (index >= 0) selected.remove(index);
                else selected.add(target);
            } else if (index >= 0 && index + direction >= 0 && index + direction < selected.size()) Collections.swap(selected, index, index + direction);
            model.replaceReferences(draft, selected);
        } finally {
            model.cancelReferences();
        }
    }

    private Optional<TextContent> fieldDisabled(DocumentEditor.Field field, List<DocumentEditor.Field> appliedFields) {
        return field.presentation().flatMap(DocumentEditor.Presentation::dependency).filter(dependency -> appliedFields.stream().noneMatch(f -> f.property().equals(dependency.property()) && f.raw().equals(dependency.expectedRaw()))).map(DocumentEditor.Dependency::disabledReason);
    }

    private boolean editableFields() {
        return !model.protectedMode() && !model.unresolvedSave();
    }

    private record PreparedFieldRow(String id, List<StudioComponents.Field> fields, StudioComponents.FieldActions actions) {}

    private PreparedFieldRow prepareFieldRow(String view, InspectorPresentation.Row row, UUID document, String node, java.util.function.Consumer<ProjectWorkbench.Address> reveal) {
        var appliedFields = row.fields().stream().anyMatch(field -> field.presentation().flatMap(DocumentEditor.Presentation::dependency).isPresent()) ? model.fields(document, node) : List.<DocumentEditor.Field>of();
        return prepareFieldRow(view, row, document, node, reveal, appliedFields);
    }

    private PreparedFieldRow prepareFieldRow(String view, InspectorPresentation.Row row, UUID document, String node, java.util.function.Consumer<ProjectWorkbench.Address> reveal, List<DocumentEditor.Field> appliedFields) {
        var fields = row.fields();
        var addresses = fields.stream().map(field -> new ProjectWorkbench.Address(document, node, field.property())).toList();
        boolean pending = addresses.stream().anyMatch(model::pending);
        String rowId = "field-row/" + view + "/" + document + "/" + node + "/" + row.id();
        var controls = new ArrayList<StudioComponents.Field>();
        for (int i = 0; i < fields.size(); i++) {
            var field = fields.get(i);
            var address = addresses.get(i);
            String key = "field/" + view + "/" + document + "/" + address.pendingKey(), raw = model.raw(address, field.raw());
            if (view.startsWith("form/")) formFieldControls.put(key, address);
            String title = component(fields.size() > 1 ? field.presentation().map(DocumentEditor.Presentation::compactLabel).orElse(field.label()) : field.label()).getString();
            int color = field.presentation().map(p -> switch (p.accent()) {
                case X -> themeName.equals("light") ? 0xffa13645 : 0xffef8d8d;
                case Y -> themeName.equals("light") ? 0xff317244 : 0xff9dd49e;
                case Z -> ui.theme.accent();
                default -> ui.theme.muted();
            }).orElse(ui.theme.muted());
            var disabled = fieldDisabled(field, appliedFields);
            boolean enabled = editableFields() && disabled.isEmpty();
            var kind = field.kind() == DocumentEditor.Kind.BOOLEAN ? StudioComponents.FieldKind.TOGGLE : field.kind() == DocumentEditor.Kind.CHOICE ? StudioComponents.FieldKind.CHOICE : StudioComponents.FieldKind.TEXT;
            String display = kind == StudioComponents.FieldKind.TOGGLE ? c(Boolean.parseBoolean(raw) ? "boolean_on" : "boolean_off").getString() : field.choices().stream().filter(choice -> choice.raw().equals(raw)).findFirst().map(choice -> component(choice.label()).getString()).orElse(raw);
            String hint = disabled.map(WorkbenchScreen::component).map(Component::getString).orElse(component(field.unit()).getString());
            controls.add(new StudioComponents.Field(key, title, raw, display, hint, kind, color, enabled, fieldErrors.containsKey(address), value -> run(() -> {
                model.input(address, value);
                fieldErrors.remove(address);
                reveal.accept(address);
            }), () -> run(() -> {
                if (kind == StudioComponents.FieldKind.TOGGLE) {
                    model.input(address, Boolean.toString(!Boolean.parseBoolean(raw)));
                    applyField(address);
                    if (fieldErrors.containsKey(address)) reveal.accept(address);
                } else openChoices(address, field, key);
            }), () -> { if (model.pending(address) && editableFields()) applyField(address, false); }));
        }
        String error = addresses.stream().map(fieldErrors::get).filter(Objects::nonNull).findFirst().map(Component::getString).orElse("");
        var actions = new StudioComponents.FieldActions(rowId, c("apply").getString(), c("discard").getString(), editableFields() && fields.stream().allMatch(field -> fieldDisabled(field, appliedFields).isEmpty()), pending ? () -> run(() -> {
            applyFieldRow(addresses);
            reveal.accept(addresses.get(0));
        }) : null, () -> run(() -> {
            for (var address : addresses) {
                model.discardInput(address);
                fieldErrors.remove(address);
            }
        }), error);
        return new PreparedFieldRow(rowId, List.copyOf(controls), actions);
    }

    private void applyFieldRow(List<ProjectWorkbench.Address> addresses) {
        try {
            model.applyFields(addresses);
            addresses.forEach(fieldErrors::remove);
        } catch (RuntimeException failure) {
            model.reportFieldFailure(addresses, failure);
            fieldErrors.put(addresses.get(0), model.problems().isEmpty() ? c("failed") : component(model.problems().get(0).message()));
            revealField = addresses.get(0);
        }
    }

    private InspectorContent inspectorResources(String scrollContext, UUID panel, UUID document, StudioView.Element header) {
        var resources = model.resources();
        var references = model.resourcesForDocument(document);
        var content = new ArrayList<StudioView.Element>();
        content.add(header);
        if (resources.isEmpty() || references.isEmpty()) content.add(ui.view.paragraph(scrollContext + "/empty", c("no_document").getString()));
        else {
            content.add(ui.view.paragraph(scrollContext + "/caption", c("resource_locked_references").getString(), TextSize.HEADING, Variant.DEFAULT));
            String query = inspectorSearch.getOrDefault(panel, "").strip().toLowerCase(Locale.ROOT);
            var filtered = references.orElseThrow().stream().filter(ref -> {
                var resource = resources.orElseThrow().content().resources().get(ref.resourceId());
                return query.isEmpty() || (resource == null ? ref.resourceId().toString() : resource.name()).toLowerCase(Locale.ROOT).contains(query);
            }).toList();
            for (int index = 0; index < filtered.size(); index++) {
                var ref = filtered.get(index);
                String id = scrollContext + "/reference/" + index;
                var resource = resources.orElseThrow().content().resources().get(ref.resourceId());
                var version = resources.orElseThrow().content().versions().get(ref.versionId());
                content.add(ui.view.column(id, Style.defaults().gap(4), List.of(
                        ui.view.paragraph(id + "/name", resource == null ? ref.resourceId().toString() : resource.name(), TextSize.HEADING, Variant.DEFAULT),
                        ui.view.paragraph(id + "/version", c("resource_locked_version").getString() + " " + ref.versionId(), TextSize.SMALL, Variant.MUTED),
                        ui.view.paragraph(id + "/status", c(version == null ? "resource_reference_missing" : resource != null && !resource.currentVersion().equals(ref.versionId()) ? "resource_newer_available" : "resource_reference_available").getString(), TextSize.SMALL, Variant.MUTED))));
            }
            if (filtered.isEmpty()) content.add(ui.view.paragraph(scrollContext + "/empty", c("no_resource_references").getString()));
            content.add(ui.view.button(studioAction("inspector/" + panel + "/resources", c("resource_browser"), Icon.NONE, Variant.OUTLINED, true, false, () -> show(Page.RESOURCES))));
        }
        return new InspectorContent(scrollContext, content);
    }

    private void applyField(ProjectWorkbench.Address address) {
        applyField(address, true);
    }

    private void applyField(ProjectWorkbench.Address address, boolean revealFailure) {
        try {
            model.apply(address);
            fieldErrors.remove(address);
        } catch (RuntimeException failure) {
            model.reportFieldFailure(List.of(address), failure);
            fieldErrors.put(address, model.problems().isEmpty() ? c("failed") : component(model.problems().get(0).message()));
            if (revealFailure) revealField = address;
        } finally {
            request();
        }
    }

    private void openChoices(ProjectWorkbench.Address address, DocumentEditor.Field field, String key) {
        choiceAnchor = ui.tree.snapshot().stream().filter(n -> n.id().equals(key)).map(ControlTree.Node::visibleBounds).findFirst().orElse(null);
        choiceDocumentForm = activeFormKey().filter(form -> key.startsWith("field/" + form.controlId() + "/")).orElse(null);
        choiceCreationProperty = null;
        choiceAddress = address;
        choiceField = field;
        choiceSearch = "";
        choiceScroll = 0;
        ui.releaseText("choice/search");
        show(Page.FIELD_CHOICES);
    }

    private void openCreationChoices(DocumentEditor.Field field, String key) {
        choiceAnchor = ui.tree.snapshot().stream().filter(n -> n.id().equals(key)).map(ControlTree.Node::visibleBounds).findFirst().orElse(null);
        choiceDocumentForm = null;
        choiceAddress = null;
        choiceCreationProperty = field.property();
        choiceFormScroll = creationScroll;
        choiceFormFocus = key;
        choiceField = field;
        choiceSearch = "";
        choiceScroll = 0;
        ui.releaseText("choice/search");
        show(Page.FIELD_CHOICES);
    }

    private void returnCreationChoices() {
        int savedScroll = choiceFormScroll;
        String savedFocus = choiceFormFocus;
        hideOverlay();
        creationScroll = savedScroll;
        choiceFormFocus = savedFocus;
        restoreChoiceFocus = true;
        openCreationPanel();
        request();
    }

    private void returnFieldChoices() {
        if (choiceDocumentForm != null && Objects.equals(choiceDocumentForm, activeFormDocument)) revealFormField = choiceAddress;
        hideOverlay();
    }

    private List<DocumentEditor.Choice> filteredChoices() {
        if (choiceField == null) return List.of();
        String query = choiceSearch.strip().toLowerCase(Locale.ROOT);
        return choiceField.choices().stream().filter(c -> query.isEmpty() || component(c.label()).getString().toLowerCase(Locale.ROOT).contains(query) || c.raw().toLowerCase(Locale.ROOT).contains(query)).toList();
    }

    private int choiceRows() {
        return Math.max(1, (dh - 92) / 24);
    }

    private void choicePopup() {
        dw = Math.min(330, Math.max(160, width - 16));
        dh = Math.min(280, Math.max(116, height - 16));
        int anchorX = choiceAnchor == null ? (width - dw) / 2 : choiceAnchor.x(), anchorY = choiceAnchor == null ? (height - dh) / 2 : choiceAnchor.y() + choiceAnchor.height() + 3;
        dx = Math.max(8, Math.min(anchorX, width - dw - 8));
        dy = Math.max(8, Math.min(anchorY, height - dh - 8));
        if (choiceAnchor != null && anchorY + dh > height - 8 && choiceAnchor.y() - dh - 3 >= 8) dy = choiceAnchor.y() - dh - 3;
        ui.panel("choice/popup", dx, dy, dw, dh, layer);
        if (choiceField != null) ui.heading(component(choiceField.label()), dx + 10, dy + 9, dw - 44, layer);
        action("choice/close", Component.literal("×"), dx + dw - 28, dy + 4, 22, this::cancelOverlay);
        fieldChoices();
        int bw = (dw - 28) / 2;
        action("choice/previous", c("previous"), dx + 10, dy + dh - 25, bw, () -> choiceScroll -= choiceRows(), choiceScroll > 0, "");
        action("choice/next", c("next"), dx + 18 + bw, dy + dh - 25, bw, () -> choiceScroll += choiceRows(), choiceScroll + choiceRows() < filteredChoices().size(), "");
    }

    private void fieldChoices() {
        if (choiceField == null || (choiceAddress == null && (choiceCreationProperty == null || objectCreation == null))) return;
        ui.edit("choice/search", c("search_choices"), choiceSearch, dx + 10, dy + 29, dw - 20, layer, true, value -> {
            choiceSearch = value;
            choiceScroll = 0;
            request();
        });
        List<DocumentEditor.Choice> choices = filteredChoices();
        int rows = choiceRows();
        choiceScroll = Math.max(0, Math.min(choiceScroll, Math.max(0, choices.size() - rows)));
        if (choices.isEmpty()) label(c("no_choices"), dx + 10, dy + 57, dw - 20);
        String raw = choiceCreationProperty == null ? model.raw(choiceAddress, choiceField.raw()) : creationRaw.get(choiceCreationProperty);
        for (int i = choiceScroll; i < Math.min(choices.size(), choiceScroll + rows); i++) {
            var choice = choices.get(i);
            String id = "choice/value/" + i;
            Component caption = Component.literal(choice.raw().equals(raw) ? "✓ " : "").append(component(choice.label()));
            action(id, caption, dx + 10, dy + 55 + (i - choiceScroll) * 24, dw - 20, () -> {
                if (choiceCreationProperty != null) {
                    creationRaw.put(choiceCreationProperty, choice.raw());
                    returnCreationChoices();
                    return;
                }
                var address = choiceAddress;
                model.input(address, choice.raw());
                applyField(address);
                returnFieldChoices();
            }, !model.protectedMode() && !model.unresolvedSave(), "protected_status");
            ui.detail(id, choice.raw());
        }
    }

    private void openReferences(ProjectWorkbench.Address address) {
        var draft = model.beginReferences(address);
        referenceDraft = draft;
        referenceSelection.clear();
        referenceSelection.addAll(draft.list().selected());
        referenceSearch = "";
        referenceScroll = 0;
        referenceAvailable = false;
        ui.releaseText("reference/search");
        show(Page.REFERENCES);
    }

    private List<DocumentEditor.Choice> filteredReferences() {
        if (referenceDraft == null) return List.of();
        var byId = new HashMap<String, DocumentEditor.Choice>();
        referenceDraft.list().candidates().forEach(c -> byId.put(c.raw(), c));
        Set<String> selected = new HashSet<>(referenceSelection);
        var choices = referenceAvailable ? referenceDraft.list().candidates().stream().filter(c -> !selected.contains(c.raw())) :
                referenceSelection.stream().map(byId::get);
        String query = referenceSearch.strip().toLowerCase(Locale.ROOT);
        return choices.filter(c -> query.isEmpty() || component(c.label()).getString().toLowerCase(Locale.ROOT).contains(query) || c.raw().toLowerCase(Locale.ROOT).contains(query)).toList();
    }

    private int referenceRows() {
        return Math.max(1, (dh - 160) / 22);
    }

    private void moveReference(String id, int delta) {
        int at = referenceSelection.indexOf(id), to = at + delta;
        if (at >= 0 && to >= 0 && to < referenceSelection.size()) Collections.swap(referenceSelection, at, to);
    }

    private void referenceDialog() {
        if (referenceDraft == null) return;
        int tabWidth = (dw - 34) / 2;
        tab("reference/selected", c("references_selected").copy().append(" · " + referenceSelection.size()), dx + 14, dy + 36, tabWidth, !referenceAvailable, () -> {
            referenceAvailable = false;
            referenceScroll = 0;
        });
        tab("reference/available", c("references_available").copy().append(" · " + (referenceDraft.list().candidates().size() - referenceSelection.size())), dx + 20 + tabWidth, dy + 36, tabWidth, referenceAvailable, () -> {
            referenceAvailable = true;
            referenceScroll = 0;
        });
        ui.edit("reference/search", c("search_choices"), referenceSearch, dx + 14, dy + 60, dw - 28, layer, true, value -> {
            referenceSearch = value;
            referenceScroll = 0;
            request();
        });
        var choices = filteredReferences();
        int rows = referenceRows();
        referenceScroll = Math.max(0, Math.min(referenceScroll, Math.max(0, choices.size() - rows)));
        if (choices.isEmpty()) label(c(referenceDraft.list().candidates().isEmpty() ? "references_create_hint" : "no_choices"), dx + 14, dy + 87, dw - 28);
        for (int i = referenceScroll; i < Math.min(choices.size(), referenceScroll + rows); i++) {
            var choice = choices.get(i);
            int rowY = dy + 84 + (i - referenceScroll) * 22;
            String key = "reference/row/" + choice.raw();
            if (referenceAvailable) action(key, Component.literal("＋ ").append(component(choice.label())), dx + 14, rowY, dw - 28, () -> referenceSelection.add(choice.raw()));
            else {
                int index = referenceSelection.indexOf(choice.raw());
                label(Component.literal((index + 1) + " · ").append(component(choice.label())), dx + 14, rowY + 5, dw - 128);
                action(key + "/up", Component.literal("↑"), dx + dw - 108, rowY, 28, () -> moveReference(choice.raw(), -1), index > 0, "");
                action(key + "/down", Component.literal("↓"), dx + dw - 76, rowY, 28, () -> moveReference(choice.raw(), 1), index + 1 < referenceSelection.size(), "");
                action(key + "/remove", Component.literal("−"), dx + dw - 44, rowY, 28, () -> referenceSelection.remove(choice.raw()));
            }
        }
    }

    private void summary(int x, int y, int w, int h) {
        if (model.activeDocument().isEmpty()) {
            label(c("no_document"), x + 12, y + 8, w - 24);
            label(c("new_document_hint"), x + 12, y + 32, w - 24);
            return;
        }
        var handle = listProjection.documents().get(model.activeDocument().orElseThrow());
        label(Component.literal(handle.displayName()), x + 12, y + 8, w - 24);
        label(c(handle.hasUnpublishedChanges() ? "unsaved_document" : "saved_document"), x + 12, y + 28, w - 24);
        paragraph(c("viewport_unavailable"), x + 12, y + 52, w - 24, Math.max(1, (h - 52) / 13));
    }

    private void projectMenu() {
        ui.components.menu("project-menu", List.of(
                menuAction("project/new-menu", "new_project", Icon.PLUS, !model.hasProject() && !model.pendingCreation(), "close_first", () -> {
                    creating = true;
                    show(Page.PROJECT);
                }),
                menuAction("project/open-menu", "open_project", Icon.FOLDER, !model.hasProject() && !model.pendingCreation(), "close_first", () -> {
                    creating = false;
                    show(Page.PROJECT);
                }),
                menuAction("project/document-menu", "new_document", Icon.MAP, model.hasProject(), "missing_project", () -> show(Page.NEW_DOCUMENT)),
                menuAction("project/import-menu", "import_legacy", Icon.NONE, model.hasProject(), "missing_project", this::openImportTask),
                menuAction("project/resources-menu", "studio_resources", Icon.RESOURCES, model.hasProject(), "missing_project", () -> show(Page.RESOURCES)),
                menuAction("project/save-document-menu", "save_document", Icon.SAVE, canSaveCurrentDocument(), currentOwnedDocument().isEmpty() ? "studio_view_has_no_document" : model.busy() ? "busy" : model.unresolvedSave() ? "unknown_close" : "protected_status", this::saveCurrentDocument),
                menuAction("project/save-menu", "save_all", Icon.SAVE, model.hasProject() && !model.busy() && !model.unresolvedSave(), "unknown_close", model::saveAll),
                menuAction("project/details-menu", "project_details", Icon.SETTINGS, model.hasProject() || model.pendingCreation(), "missing_project", () -> show(Page.PROJECT)),
                menuAction("project/close-menu", "close_project", Icon.CLOSE, model.hasProject() || model.pendingCreation(), "missing_project", () -> show(Page.CLOSE))), new ControlTree.Bounds(dx + 8, dy + 40, dw - 16, 225), layer);
        if (canSaveCurrentDocument()) currentOwnedDocument().ifPresent(document -> ui.detail("project/save-document-menu", c("studio_save_document_target", listProjection.documents().get(document).displayName()).getString()));
    }

    private void editMenu() {
        ui.components.menu("edit-menu", List.of(menuAction("edit/undo", "undo", Icon.UNDO, model.canUndo(), "unavailable_history", model::undo), menuAction("edit/redo", "redo", Icon.REDO, model.canRedo(), "unavailable_history", model::redo)), new ControlTree.Bounds(dx + 8, dy + 40, dw - 16, 50), layer);
        paragraph(c("text_priority"), dx + 14, dy + 108, dw - 28, 5);
    }

    private void viewMenu() {
        int y = dy + 40;
        tab("view/theme/light", c("studio_theme_light"), dx + 12, y, (dw - 28) / 2, themeName.equals("light"), () -> setTheme("light"));
        tab("view/theme/dark", c("studio_theme_dark"), dx + 14 + (dw - 28) / 2, y, (dw - 28) / 2, themeName.equals("dark"), () -> setTheme("dark"));
        y += 28;
        var workspace = activeWorkspace().orElse(null);
        if (workspace == null) return;
        for (var panel : workspace.panels()) {
            Component caption = Component.literal(panelHidden(panel) ? "○ " : "✓ ").append(panelLabel(panel.type()));
            action("view/panel/" + panel.id(), caption, dx + 12, y, dw - 24, () -> {
                hideOverlay();
                focusPanel(panel.id());
            });
            y += 26;
        }
        action("view/layout", c("layout_restore"), dx + 12, y + 8, dw - 24, () -> guardCreation(this::restoreLayout));
    }

    private void setTheme(String name) {
        MinecraftWorkbenchPreferences.saveTheme(name);
        themeName = name;
        ui.theme(name);
        request();
    }

    private void windowMenu() {
        action("window/workspaces", c("workspace_manage"), dx + 14, dy + 42, dw - 28, this::manageWorkspaces);
        action("window/create", c("workspace_create"), dx + 14, dy + 70, dw - 28, () -> show(Page.WORKSPACE_NEW));
        action("window/panel-add", c("panel_add"), dx + 14, dy + 98, dw - 28, () -> show(Page.PANEL_ADD));
        action("window/layout-save", c("layout_save"), dx + 14, dy + 126, dw - 28, () -> {
            persistPanelViews();
            activeWorkspace().ifPresent(workspace -> layouts.saveLayout(workspace.id()));
        }, layouts.canSave(), layouts.statusKey());
        action("window/layout-restore", c("layout_restore"), dx + 14, dy + 154, dw - 28, () -> guardCreation(this::restoreLayout), !layouts.loading() && !layouts.saving(), layouts.statusKey());
        action("window/layout-retry", c("layout_storage_retry"), dx + 14, dy + 182, dw - 28, () -> {
            persistPanelViews();
            layouts.retry();
        }, layouts.canSave() && !layouts.saving(), layouts.statusKey());
        action("window/layout-reload", c("layout_storage_reload"), dx + 14, dy + 210, dw - 28, () -> guardCreation(() -> layouts.reload(defaultWorkspaceState())), !layouts.loading() && !layouts.saving(), layouts.statusKey());
        ui.detail("window/layout-reload", c("layout_storage_reload_hint").getString());
        paragraph(c(layouts.statusKey()), dx + 14, dy + 242, dw - 28, 5);
    }

    private List<PanelSpec> availablePanelTypes() {
        Map<EditorId, PanelSpec> types = new LinkedHashMap<>();
        activeWorkspace().map(workspace -> templates.get(workspace.templateId())).ifPresent(template -> template.panels().forEach(spec -> types.put(spec.type(), spec)));
        templates.values().forEach(template -> template.panels().forEach(spec -> types.putIfAbsent(spec.type(), spec)));
        return List.copyOf(types.values());
    }

    private boolean canAddPanel(EditorId type) {
        return activeWorkspace().filter(workspace -> workspace.panels().size() < 1024).map(workspace -> availablePanelTypes().stream().filter(spec -> spec.type().equals(type)).findFirst().map(spec -> spec.multipleInstances() || workspace.panels().stream().noneMatch(panel -> panel.type().equals(type))).orElse(false)).orElse(false);
    }

    private void openPanelMenu(UUID panel, int x, int y) {
        menuPanel = panel;
        menuAnchor = x;
        menuY = y;
        show(Page.PANEL_MENU);
    }

    private void focusPanel(UUID panelId) {
        activeWorkspace().ifPresent(workspace -> {
            for (var panel : workspace.panels()) {
                if (panel.id().equals(panelId)) panelState(workspace.id(), panel, "studio.hidden", false);
                else if (Boolean.parseBoolean(panel.viewState().getOrDefault("studio.maximized", "false"))) panelState(workspace.id(), panel, "studio.maximized", false);
            }
            var visible = activeWorkspace().orElseThrow().panels().stream().filter(panel -> !panelHidden(panel)).toList();
            for (int i = 0; i < visible.size(); i++) {
                var panel = visible.get(i);
                if (panel.id().equals(panelId)) {
                    dockTabs.put(dockId(workspace.id(), tabGroupName(panel)), panel.id());
                    if (!widePanels()) {
                        drawer = i;
                        drawerPanelIdentity = panel.id();
                        compactPanels.put(workspace.id(), i);
                    }
                    break;
                }
            }
        });
        request();
    }

    private void panelMenu() {
        var workspace = activeWorkspace().orElse(null);
        if (workspace == null) return;
        var panel = workspace.panels().stream().filter(value -> value.id().equals(menuPanel)).findFirst().orElse(null);
        if (panel == null) return;
        int x = dx + 12, y = dy + 40, w = dw - 24;
        boolean viewportPanel = panel.type().equals(WorkspaceTemplates.VIEWPORT);
        action("panel/duplicate", c("panel_duplicate"), x, y, w, () -> guardCreation(() -> {
            persistPanelViews();
            var added = workspaces.duplicatePanel(workspace.id(), panel.id());
            hideOverlay();
            focusPanel(added.id());
        }), !viewportPanel && canAddPanel(panel.type()), "panel_single_instance");
        y += 27;
        for (var zone : StudioDocking.Zone.values()) {
            String group = zone.key();
            action("panel/move/" + group, c("panel_move_" + group), x, y, w, () -> {
                hideOverlay();
                moveDockedPanel(workspace.id(), panel.id(), group);
            }, !viewportPanel && !group.equals(dockGroupName(panel)), viewportPanel ? "panel_keep_viewport" : "panel_current_group");
            y += 27;
        }
        action("panel/maximize-menu", c("studio_panel_maximize"), x, y, w, () -> {
            hideOverlay();
            maximizePanel(panel.id());
        });
        y += 27;
        action("panel/hide-menu", c("studio_panel_hide"), x, y, w, () -> {
            hideOverlay();
            hidePanel(panel.id());
        });
        y += 27;
        action("panel/close", c("panel_close"), x, y, w, () -> guardCreation(() -> {
            workspaces.removePanel(workspace.id(), panel.id());
            hideOverlay();
            drawer = -1;
            request();
        }), !viewportPanel, "panel_keep_viewport");
    }

    private void panelAddMenu() {
        int y = dy + 40;
        for (var spec : availablePanelTypes()) {
            action("panel/add/" + spec.type(), panelLabel(spec.type()), dx + 12, y, dw - 24, () -> guardCreation(() -> {
                var workspace = activeWorkspace().orElseThrow();
                var panel = workspaces.addPanel(workspace.id(), spec.type());
                hideOverlay();
                focusPanel(panel.id());
            }), canAddPanel(spec.type()), "panel_single_instance");
            y += 27;
        }
    }

    private void restoreLayout() {
        activeWorkspace().ifPresent(workspace -> {
            Set<UUID> reset = new HashSet<>();
            workspace.panels().forEach(panel -> reset.add(panel.id()));
            workspaces.restoreLayout(workspace.id());
            activeWorkspace().orElseThrow().panels().forEach(panel -> reset.add(panel.id()));
            reset.forEach(id -> {
                hydratedPanels.remove(id);
                invalidPanelViews.remove(id);
                panelViewExtras.remove(id);
                projectSearch.remove(id);
                projectScroll.remove(id);
                displayedPanelContexts.remove(id);
                objectScrollByPanel.remove(id);
                fieldScrollByPanel.remove(id);
                fieldRowOffset.remove(id);
                fieldRowOverflow.remove(id);
                resourceSearch.remove(id);
                resourceTabs.remove(id);
                resourceScroll.remove(id);
                resourceDetailScroll.remove(id);
                selectedResources.remove(id);
                selectedTasks.remove(id);
                taskScroll.remove(id);
                inspectorSearch.remove(id);
            });
            for (String group : List.of("left", "leftTop", "leftBottom", "right", "center", "bottom")) dockTabs.remove(dockId(workspace.id(), group));
            java.util.function.Predicate<String> matches = key -> reset.stream().anyMatch(id -> key.startsWith(id + "/"));
            resourceVersions.keySet().removeIf(matches);
            objectContextScroll.keySet().removeIf(matches);
            fieldContextScroll.keySet().removeIf(matches);
            objectSearch.keySet().removeIf(matches);
            collapsedObjects.keySet().removeIf(matches);
            inspectorPages.keySet().removeIf(matches);
            inspectorCollapsed.keySet().removeIf(matches);
        });
        hideOverlay();
        drawer = -1;
        request();
    }

    private Component worldStatus() {
        var status = worldTarget.status();
        if (status.equals(WorldTargetWorkbench.text("world_enter")) || status.equals(WorldTargetWorkbench.text("world_unverified"))) {
            if (worldSession.state() == WorkbenchWorldSession.State.CONNECTED) {
                if (projectWorld.requiresWorld()) {
                    if (projectWorld.state() != ProjectWorldWorkbench.State.READY) return component(projectWorld.status());
                    if (wrongCurrentProjectWorld()) return c("project_world_wrong_current");
                    return c("project_world_connected_unverified");
                }
                return c("world_connected_unverified");
            }
            if (worldSession.state() == WorkbenchWorldSession.State.LEAVING) return c("world_leaving_status");
            if (worldSession.loading()) return c(worldSession.cancellationRequested() ? "world_cancel_pending" : "world_loading");
            if (projectWorld.requiresWorld() && projectWorld.state() != ProjectWorldWorkbench.State.READY) return component(projectWorld.status());
            if (projectWorld.binding().isPresent()) return c("project_world_bound_offline");
            return c("world_enter");
        }
        return component(status);
    }

    private void toolsMenu() {
        action("tools/world", c("world_target"), dx + 14, dy + 42, dw - 28, this::openWorldTarget);
        action("tools/install", c("test_workflow"), dx + 14, dy + 70, dw - 28, this::openTestWorkflow);
        paragraph(c("world_tools_pending"), dx + 14, dy + 106, dw - 28, 6);
    }

    private Optional<RuntimeInstallationWorkbench.Receipt> successfulInstallation() {
        return installations.receipt().filter(receipt -> receipt.result() != null && receipt.result().state() == RuntimeInstallationWorkbench.State.INSTALLED);
    }

    private void openTestWorkflow() {
        if (!tests.canPrepare()) {
            show(Page.TEST);
            return;
        }
        if (!worldEditingReady()) {
            openAuthoringWorld();
            return;
        }
        if (installations.receiptBelongsToCurrentProject() && successfulInstallation().filter(receipt -> model.activeDocument().filter(receipt.plan().documentId()::equals).isPresent() && model.savedVersion().filter(receipt.plan().commitId()::equals).isPresent()).isPresent()) {
            installationDraft = false;
            openInstallationTask();
            return;
        }
        openNewInstallation();
    }

    private void beginTest(RuntimeInstallationWorkbench.Receipt receipt) {
        beginTest(new RuntimeTestWorkbench.Input(receipt.worldLabel(), receipt.worldId(), receipt.plan()));
    }

    private void beginTest(RuntimeTestWorkbench.Input input) {
        // This captured plan/world pair is a candidate only; the host checks its real installed handle.
        show(Page.TEST);
        testCancellationRequested = false;
        testActionError = null;
        rejectedTestInput = null;
        if (!tests.canPrepare()) {
            request();
            return;
        }
        try {
            tests.prepare(input.worldId(), input.plan());
        } catch (RuntimeException failure) {
            rejectedTestInput = input;
            recordTestActionError(failure);
        }
        request();
    }

    private void testAction(Runnable action) {
        testActionError = null;
        try {
            action.run();
        } catch (RuntimeException failure) {
            recordTestActionError(failure);
        }
        request();
    }

    private void recordTestActionError(RuntimeException failure) {
        testActionError = typedTestDiagnostic(failure);
        testActionErrorVersion = tests.version();
        net.ptcrys.fpsmatch.FPSMatch.LOGGER.error("Runtime test action failed", failure);
    }

    private Component typedTestDiagnostic(Throwable failure) {
        if (failure instanceof SessionRejection rejected && !rejected.problems().isEmpty()) return component(rejected.problems().get(0).message());
        if (failure instanceof DocumentCodecException codec && !codec.problems().isEmpty()) return component(codec.problems().get(0).message());
        if (failure instanceof RuntimeMapLeaseException lease) return component(lease.problem().message());
        return c("test_diagnostic");
    }

    private RuntimeTestWorkbench.Input testDisplayInput() {
        return rejectedTestInput == null ? tests.input().orElse(null) : rejectedTestInput;
    }

    private RuntimeTestWorkbench.State testDisplayState() {
        return rejectedTestInput == null ? tests.state() : RuntimeTestWorkbench.State.FAILED;
    }

    private Component testStatus() {
        if (testActionError != null && testActionErrorVersion == tests.version()) return testActionError;
        if (testCancellationRequested && (tests.state() == RuntimeTestWorkbench.State.PREPARING || tests.state() == RuntimeTestWorkbench.State.STARTING)) return c("test_cancel_requested");
        if (tests.state() == RuntimeTestWorkbench.State.READY && !tests.canConfirm()) return c("test_blocked");
        return component(tests.status());
    }

    private void cancelTest() {
        testCancellationRequested = true;
        testAction(tests::cancel);
    }

    private void dismissTest() {
        var state = tests.state();
        if (state == RuntimeTestWorkbench.State.PREPARING || state == RuntimeTestWorkbench.State.READY || state == RuntimeTestWorkbench.State.STARTING) cancelTest();
        hideTestTask();
    }

    private void hideTestTask() {
        hideOverlay();
        selectedTasks.replaceAll((panel, selected) -> "test");
        activateBottom(WorkspaceTemplates.TASKS);
    }

    private void testWorkflowSteps(int step) {
        ui.components.tabs("workflow", new ControlTree.Bounds(dx + 14, dy + 36, dw - 28, 22), layer, taskSteps("workflow", new String[] { "install_step_source", "install_step_check", "install_step_result", "test_step" }, step), false, 0);
    }

    private List<StudioComponents.Action> taskSteps(String id, String[] keys, int active) {
        var steps = new ArrayList<StudioComponents.Action>();
        for (int i = 0; i < keys.length; i++) steps.add(studioAction(id + "/step/" + i, Component.literal((i + 1) + "  ").append(c(keys[i])), Icon.NONE, Variant.DEFAULT, false, i == active, () -> {}));
        return steps;
    }

    private List<Component> testLines() {
        List<Component> lines = new ArrayList<>();
        var input = testDisplayInput();
        if (input != null) {
            var plan = input.plan();
            lines.add(c("install_fixed_target").copy().append(input.worldLabel()));
            lines.add(c("world_identity").copy().append(input.worldId().toString()));
            lines.add(c("install_fixed_name").copy().append(plan.key().type() + " / " + plan.key().name()));
            lines.add(c("install_fixed_project").copy().append(plan.projectId().toString()));
            lines.add(c("install_fixed_commit").copy().append(plan.commitId().toString()));
            lines.add(c("install_fixed_document").copy().append(plan.documentId().toString()));
        }
        lines.add(c("test_fixed_hint"));
        if (rejectedTestInput == null) {
            if (tests.canConfirm() && tests.problems().isEmpty()) lines.add(c("test_preflight_clear"));
            for (var problem : tests.problems()) lines.add(c("test_severity_" + problem.severity().name().toLowerCase(Locale.ROOT)).copy().append(" · ").append(component(problem.message())));
            tests.diagnostic().ifPresent(failure -> lines.add(typedTestDiagnostic(failure)));
        }
        if (testDisplayState() == RuntimeTestWorkbench.State.FAILED && !tests.canPrepare()) lines.add(c("test_release_required"));
        if (testDisplayState() == RuntimeTestWorkbench.State.STOPPED) lines.add(c("test_stopped_hint"));
        if (model.hasProject()) {
            lines.add(c("test_editing_project").copy().append(model.projectName()));
            lines.add(c("test_editing_commit").copy().append(model.savedVersion().map(UUID::toString).orElse("—")));
            lines.add(c(model.unsaved() ? "test_editing_dirty" : "test_editing_saved"));
        }
        return lines;
    }

    private void testTaskDialog() {
        dw = Math.max(220, Math.min(640, width - 24));
        dh = Math.max(180, Math.min(430, height - 24));
        dx = (width - dw) / 2;
        dy = (height - dh) / 2;
        ui.dimOverlay = true;
        ui.panel("test/task", dx, dy, dw, dh, layer);
        ui.heading(c("test_title"), dx + 14, dy + 12, dw - 54, layer);
        action("test/close", Component.literal("×"), dx + dw - 30, dy + 7, 22, this::dismissTest);
        testWorkflowSteps(3);
        label(testStatus(), dx + 14, dy + 65, dw - 28);
        int bodyTop = dy + 87, bodyHeight = Math.max(24, dh - 133), bodyWidth = dw - 46;
        var lines = testLines();
        dialogMaxScroll = Math.max(0, installationParagraphsHeight(lines, bodyWidth) - bodyHeight);
        dialogScroll = Math.max(0, Math.min(dialogScroll, dialogMaxScroll));
        ui.clip(new ControlTree.Bounds(dx + 8, bodyTop, dw - 16, bodyHeight));
        int y = bodyTop - dialogScroll;
        int lineIndex = 0;
        for (var line : lines) y += workflowParagraph("test/report/" + lineIndex++, line, dx + 14, y, bodyWidth);
        ui.clip(null);
        if (dialogMaxScroll > 0) {
            action("test/scroll-up", Component.literal("↑"), dx + dw - 25, bodyTop, 18, () -> dialogScroll -= 44);
            action("test/scroll-down", Component.literal("↓"), dx + dw - 25, bodyTop + bodyHeight - 20, 18, () -> dialogScroll += 44);
        }
        int bw = (dw - 40) / 3, left = dx + 14, middle = left + bw + 6, right = middle + bw + 6, footer = dy + dh - 31;
        var state = testDisplayState();
        if (state == RuntimeTestWorkbench.State.READY) {
            action("test/cancel", c("cancel"), middle, footer, bw, this::cancelTest);
            action("test/start", c("test_start"), right, footer, bw, () -> testAction(tests::confirm), tests.canConfirm(), "test_blocked");
        } else if (state == RuntimeTestWorkbench.State.PREPARING || state == RuntimeTestWorkbench.State.STARTING) {
            action("test/cancel", c("cancel_task"), middle, footer, bw, this::cancelTest, !testCancellationRequested, "test_cancel_requested");
            action("test/hide", c("hide_to_tasks"), right, footer, bw, this::hideTestTask);
        } else if (state == RuntimeTestWorkbench.State.RUNNING) {
            action("test/edit", c("test_continue_editing"), left, footer, bw, this::hideOverlay);
            action("test/enter", c("test_enter_game"), middle, footer, bw, () -> {
                hideOverlay();
                returnToHost();
            }, worldEditingReady() && worldSession.mayReturn(), "world_loading");
            action("test/stop", c("test_stop"), right, footer, bw, () -> testAction(tests::stop), tests.canStop(), "test_owned");
        } else {
            action("test/edit", c("test_continue_editing"), left, footer, bw, this::hideOverlay);
            if (tests.canPrepare()) {
                var input = testDisplayInput();
                action("test/install", c("install_new"), middle, footer, bw, this::openNewInstallation, installations.canStart(), "install_source_required");
                action("test/recheck", c("test_recheck"), right, footer, bw, () -> beginTest(input), input != null, "test_install_required");
            } else if (state == RuntimeTestWorkbench.State.STOPPING) action("test/hide", c("hide_to_tasks"), right, footer, bw, this::hideTestTask);
        }
    }

    private void openInstallation() {
        var profiles = installationSourceProfiles();
        if (installationProfile == null || !profiles.containsKey(installationProfile)) installationProfile = profiles.keySet().stream().findFirst().orElse(null);
        if (!form.containsKey("install_name")) form.put("install_name", model.activeDocument().map(listProjection.documents()::get).map(h -> h.displayName()).orElse(""));
        if (installations.busy() || installations.state() == RuntimeInstallationWorkbench.State.READY || !installations.canStart()) installationDraft = false;
        show(Page.INSTALL);
    }

    private void openInstallationTask() {
        if (installations.state() != RuntimeInstallationWorkbench.State.IDLE) installationDraft = false;
        openInstallation();
    }

    private void installationAction(Runnable action) {
        installationActionError = null;
        try {
            action.run();
        } catch (RuntimeException failure) {
            installationActionError = boundedInstallationDiagnostic(failure);
            installationActionErrorVersion = installations.version();
            installationActionErrorPage = page;
            installationActionErrorDraft = installationDraft;
            throw failure;
        }
    }

    private Component installationStatus() {
        if (page == Page.INSTALL_RECORDS) return component(installations.recordsStatus());
        return installationDraft ? c("install_idle") : component(installations.status());
    }

    private Component installationError() {
        if (installationActionError != null && installationActionErrorVersion == installations.version() && installationActionErrorPage == page && (page != Page.INSTALL || installationActionErrorDraft == installationDraft)) return installationActionError;
        if (page == Page.INSTALL && installationDraft) return null;
        var diagnostic = page == Page.INSTALL_RECORDS ? installations.recordsDiagnostic() : installations.diagnostic();
        return diagnostic.map(this::boundedInstallationDiagnostic).orElse(null);
    }

    private Component boundedInstallationDiagnostic(Throwable failure) {
        if (failure instanceof SessionRejection rejected && !rejected.problems().isEmpty()) return component(rejected.problems().get(0).message());
        if (failure instanceof DocumentCodecException codec && !codec.problems().isEmpty()) return component(codec.problems().get(0).message());
        if (failure instanceof RuntimeMapLeaseException lease) return component(lease.problem().message());
        if (failure instanceof net.ptcrys.fpsmatch.editor.common.host.minecraft.v1201.item.NativeItemException nativeItem) return component(nativeItem.problem().message());
        String type = failure.getClass().getSimpleName(), message = failure.getMessage();
        if (type.isEmpty()) type = failure.getClass().getName();
        String source = type + (message == null || message.isBlank() ? "" : ": " + message);
        StringBuilder summary = new StringBuilder();
        int count = 0, index = 0;
        while (index < source.length() && count < 160) {
            int cp = source.codePointAt(index);
            index += Character.charCount(cp);
            if (Character.isISOControl(cp) || Character.isWhitespace(cp) || Character.getType(cp) == Character.FORMAT || Character.getType(cp) == Character.SURROGATE || cp == '\u00a7') cp = ' ';
            if (cp == ' ' && (summary.length() == 0 || summary.charAt(summary.length() - 1) == ' ')) continue;
            summary.appendCodePoint(cp);
            count++;
        }
        if (index < source.length()) {
            if (count == 160) summary.setLength(summary.offsetByCodePoints(summary.length(), -1));
            summary.append('\u2026');
        }
        return Component.literal(summary.toString().strip()).append(" · ").append(c("install_diagnostic_log_hint"));
    }

    private void beginInstallation() {
        installationAction(() -> {
            if (installationBlocker().isPresent()) throw new IllegalStateException(installationBlocker().orElseThrow().getString());
            var profile = installationSourceProfiles().get(installationProfile);
            if (replacementDraft == null) installations.prepare(profile, form.getOrDefault("install_name", ""));
            else installations.prepareReplacement(profile, replacementDraft);
        });
        installationDraft = false;
        dialogScroll = 0;
    }

    private void refreshInstallationRecords() {
        explicitInstallationRecord = null;
        replacementDraft = null;
        installationAction(installations::refreshRecords);
    }

    private void openInstallationRecords() {
        refreshInstallationRecords();
        show(Page.INSTALL_RECORDS);
    }

    private void openNewInstallation() {
        replacementDraft = null;
        installationActionError = null;
        installationDraft = true;
        dialogScroll = 0;
        openInstallation();
    }

    private Map<EditorId, RuntimeInstallationProfile> installationSourceProfiles() {
        var profiles = new LinkedHashMap<>(model.runtimeProfiles());
        if (replacementDraft != null) profiles.entrySet().removeIf(entry -> !entry.getValue().gameType().equals(replacementDraft.record().plan().key().type()));
        return profiles;
    }

    private Optional<Component> replacementRecordBlocker(RuntimeInstallationWorkbench.RecordView selected) {
        if (selected == null || selected != explicitInstallationRecord) return Optional.of(c("install_replace_select"));
        var blocker = installations.replacementBlocker(selected);
        return blocker == RuntimeInstallationWorkbench.ReplacementBlocker.NONE ? Optional.empty() : Optional.of(c("install_replace_" + blocker.name().toLowerCase(Locale.ROOT)));
    }

    private void openReplacement(RuntimeInstallationWorkbench.RecordView selected) {
        installationAction(() -> {
            var blocker = replacementRecordBlocker(selected);
            if (blocker.isPresent()) throw new IllegalStateException(blocker.orElseThrow().getString());
            replacementDraft = selected;
            installationDraft = true;
            var profiles = installationSourceProfiles();
            if (!profiles.containsKey(installationProfile)) installationProfile = profiles.keySet().stream().findFirst().orElse(null);
        });
        show(Page.INSTALL);
    }

    private InstallationPlan replacedInstallationPlan() {
        if (installationDraft) return replacementDraft == null ? null : replacementDraft.record().plan();
        return installations.taskInput().map(RuntimeInstallationWorkbench.TaskInput::replacedPlan)
                .orElseGet(() -> installations.receipt().map(RuntimeInstallationWorkbench.Receipt::replacedPlan).orElse(null));
    }

    private Component installationConfirmationLabel() {
        return c(replacedInstallationPlan() == null ? "install_confirm" : "install_replace_confirm");
    }

    private List<Component> installationDraftLines() {
        List<Component> lines = new ArrayList<>();
        if (replacementDraft != null) {
            var old = replacementDraft.record().plan();
            lines.add(c("install_replace_target").copy().append(old.key().type() + " / " + old.key().name()));
            lines.add(c("install_fixed_installation").copy().append(old.installationId().toString()));
            lines.add(c("install_replace_new"));
        }
        lines.add(c("install_source").copy().append(model.activeDocument().map(listProjection.documents()::get).map(h -> h.displayName()).orElse("—")));
        if (replacementDraft != null) {
            lines.add(c("install_fixed_project").copy().append(model.projectId().map(UUID::toString).orElse("—")));
            lines.add(c("install_fixed_document").copy().append(model.activeDocument().map(UUID::toString).orElse("—")));
        }
        lines.add(c("install_fixed_commit").copy().append(model.savedVersion().map(UUID::toString).orElse("—")));
        return lines;
    }

    private void installationTaskDialog() {
        dw = Math.max(220, Math.min(640, width - 24));
        dh = Math.max(180, Math.min(430, height - 24));
        dx = (width - dw) / 2;
        dy = (height - dh) / 2;
        var state = installations.state();
        int step = installationDraft ? 0 : state == RuntimeInstallationWorkbench.State.PREPARING || state == RuntimeInstallationWorkbench.State.READY ? 1 : 2;
        ui.dimOverlay = true;
        ui.components.taskDialog("install/task", c("test_workflow").getString(), new ControlTree.Bounds(dx, dy, dw, dh), layer, taskSteps("workflow", new String[] { "install_step_source", "install_step_check", "install_step_result", "test_step" }, step), studioAction("install/close", c("hide_to_tasks"), Icon.CLOSE, Variant.DEFAULT, true, false, () -> {
            installations.dismiss();
            hideOverlay();
            activateBottom(WorkspaceTemplates.TASKS);
        }));
        String target = installationDraft ? worldTarget.targetLabel() : installations.taskInput().map(RuntimeInstallationWorkbench.TaskInput::worldLabel).orElse("—");
        if (installationDraft) {
            ui.components.button(studioAction("install/world", c("install_fixed_target").copy().append(target.isBlank() ? c("studio_world_not_selected") : Component.literal(target)), Icon.MAP, Variant.OUTLINED, true, false, this::openWorldTarget), new ControlTree.Bounds(dx + 14, dy + 62, Math.max(24, dw - 190), 23), layer, Kind.BUTTON);
            ui.detail("install/world", c("project_world_scope").getString());
            ui.components.button(studioAction("install/select-save", c(projectWorldActionKey()), Icon.FOLDER, Variant.OUTLINED, !projectWorld.busy(), false, this::openAuthoringWorld), new ControlTree.Bounds(dx + dw - 166, dy + 62, 152, 23), layer, Kind.BUTTON);
            var blocker = installationBlocker();
            ui.components.paragraph("install/prerequisite", blocker.orElseGet(this::installationStatus).getString(), new ControlTree.Bounds(dx + 14, dy + 89, dw - 28, 26), layer, blocker.isPresent() ? Variant.DEFAULT : Variant.MUTED);
        } else {
            label(c("install_fixed_target").copy().append(target.isBlank() ? c("studio_world_not_selected") : Component.literal(target)), dx + 14, dy + 65, dw - 28);
            label(installationStatus(), dx + 14, dy + 84, dw - 28);
        }
        int bodyTop = dy + (installationDraft ? 119 : 106), bodyHeight = Math.max(24, dh - (installationDraft ? 187 : 174));
        dialogMaxScroll = Math.max(0, (installationDraft ? installationDraftHeight() : installationHeight()) - bodyHeight);
        dialogScroll = Math.max(0, Math.min(dialogScroll, dialogMaxScroll));
        ui.clip(new ControlTree.Bounds(dx + 8, bodyTop, dw - 16, bodyHeight));
        int y = bodyTop - dialogScroll;
        if (installationDraft) {
            int lineIndex = 0;
            for (var line : installationDraftLines()) {
                int h = ui.components.wrap(line.getString(), dw - 44).size() * 13;
                ui.components.paragraph("install/source-identity/" + lineIndex++, line.getString(), new ControlTree.Bounds(dx + 14, y, dw - 44, h), layer, Variant.DEFAULT);
                y += h + 12;
            }
            var profiles = installationSourceProfiles();
            if (installationProfile == null || !profiles.containsKey(installationProfile)) installationProfile = profiles.keySet().stream().findFirst().orElse(null);
            String profileLabel = (installationProfile == null ? c("install_no_profile") : component(profiles.get(installationProfile).label())).getString();
            var fields = new ArrayList<StudioComponents.Field>();
            fields.add(new StudioComponents.Field("install/profile", c("install_profile").getString(), "", profileLabel, profiles.isEmpty() ? c("install_no_profile").getString() : "", StudioComponents.FieldKind.CHOICE, ui.theme.muted(), !profiles.isEmpty(), false, ignored -> {}, () -> run(() -> {
                var ids = new ArrayList<>(profiles.keySet());
                installationProfile = ids.get((ids.indexOf(installationProfile) + 1) % ids.size());
            }), () -> {}));
            if (replacementDraft == null) fields.add(draftField("form/install_name", "install_name", c("install_name"), true));
            int fieldsHeight = replacementDraft == null ? 82 : 41;
            ui.components.form("install/source-fields", fields, new ControlTree.Bounds(dx + 8, y, dw - 32, fieldsHeight), layer, 0, ignored -> {});
            y += fieldsHeight + 8;
            y += ui.components.paragraph("install/saved-note", c("install_saved_only").getString(), new ControlTree.Bounds(dx + 14, y, dw - 44, ui.components.wrap(c("install_saved_only").getString(), dw - 44).size() * 13), layer, Variant.MUTED) + 8;
            var effects = c(replacementDraft == null ? "install_effects" : "install_replace_effects");
            ui.components.paragraph("install/effects-note", effects.getString(), new ControlTree.Bounds(dx + 14, y, dw - 44, ui.components.wrap(effects.getString(), dw - 44).size() * 13), layer, Variant.MUTED);
        } else {
            int index = 0;
            for (var line : installationLines()) {
                int lineHeight = ui.components.wrap(line.getString(), dw - 44).size() * 13;
                ui.components.paragraph("install/report/" + index++, line.getString(), new ControlTree.Bounds(dx + 14, y, dw - 44, lineHeight), layer, Variant.DEFAULT);
                y += lineHeight + 12;
            }
        }
        ui.clip(null);
        if (dialogMaxScroll > 0) {
            action("install/scroll-up", Component.literal("↑"), dx + dw - 25, bodyTop, 18, () -> dialogScroll -= 44);
            action("install/scroll-down", Component.literal("↓"), dx + dw - 25, bodyTop + bodyHeight - 20, 18, () -> dialogScroll += 44);
        }
        ui.components.pixelScroll("install/body-scroll", new ControlTree.Bounds(dx + 8, bodyTop + 22, dw - 16, Math.max(0, bodyHeight - 44)), dialogScroll, Math.max(0, bodyHeight - 44) + dialogMaxScroll, layer, value -> {
            dialogScroll = value;
            request();
        });
        Component error = installationError();
        if (error != null) ui.error(error, dx + 14, dy + dh - 59, dw - 28, layer);
        action("install/records", c("install_records"), dx + 14, dy + dh - 31, Math.min(125, (dw - 40) / 3), this::openInstallationRecords, installations.canStart() && worldTarget.installationTarget().isPresent(), "world_unverified");
        int buttonWidth = Math.min(125, (dw - 40) / 3), primaryX = dx + dw - 14 - buttonWidth, secondaryX = primaryX - buttonWidth - 6;
        if (installationDraft) {
            action("install/cancel", c("cancel"), secondaryX, dy + dh - 31, buttonWidth, this::cancelOverlay);
            action("install/prepare", c("install_prepare"), primaryX, dy + dh - 31, buttonWidth, this::beginInstallation, installationBlocker().isEmpty(), "");
            installationBlocker().ifPresent(reason -> ui.detail("install/prepare", reason.getString()));
        } else if (state == RuntimeInstallationWorkbench.State.READY) {
            action("install/cancel", c("cancel"), secondaryX, dy + dh - 31, buttonWidth, () -> installationAction(installations::cancel));
            action("install/confirm", installationConfirmationLabel(), primaryX, dy + dh - 31, buttonWidth, () -> installationAction(installations::confirm), installations.canConfirm(), "world_unverified");
        } else if (installations.busy()) {
            action("install/cancel", c("install_request_stop"), secondaryX, dy + dh - 31, buttonWidth, () -> installationAction(installations::cancel));
            action("install/hide", c("hide_to_tasks"), primaryX, dy + dh - 31, buttonWidth, () -> {
                hideOverlay();
                activateBottom(WorkspaceTemplates.TASKS);
            });
        } else {
            action("install/back", c("back"), secondaryX, dy + dh - 31, buttonWidth, this::cancelOverlay);
            if (installations.canQuery()) action("install/query", c("install_query"), primaryX, dy + dh - 31, buttonWidth, () -> installationAction(installations::query));
            else if (successfulInstallation().isPresent()) action("test/from-install", c("test_prepare"), primaryX, dy + dh - 31, buttonWidth, () -> beginTest(successfulInstallation().orElseThrow()), tests.canPrepare(), "test_owned");
            else action("install/new", c("install_new"), primaryX, dy + dh - 31, buttonWidth, this::openNewInstallation, installations.canStart(), "install_source_required");
        }
    }

    private Optional<Component> installationBlocker() {
        if (worldTarget.installationTarget().isEmpty()) return Optional.of(c(worldTarget.available() ? "world_unverified" : "studio_world_no_targets"));
        if (model.protectedMode()) return Optional.of(c("protected_status"));
        if (model.busy()) return Optional.of(component(model.status()));
        if (!installations.canStart()) return Optional.of(installationStatus());
        if (replacementDraft != null) {
            var blocker = replacementRecordBlocker(replacementDraft);
            if (blocker.isPresent()) return blocker;
        }
        if (installationProfile == null || !installationSourceProfiles().containsKey(installationProfile)) return Optional.of(c(replacementDraft == null ? "install_source_required" : "install_replace_profile"));
        if (replacementDraft == null && form.getOrDefault("install_name", "").isBlank()) return Optional.of(c("install_source_required"));
        return Optional.empty();
    }

    private int installationDraftHeight() {
        int identities = installationDraftLines().stream().mapToInt(line -> ui.components.wrap(line.getString(), dw - 44).size() * 13 + 12).sum();
        return identities + (replacementDraft == null ? 98 : 57) + (ui.components.wrap(c("install_saved_only").getString(), dw - 44).size() + ui.components.wrap(c(replacementDraft == null ? "install_effects" : "install_replace_effects").getString(), dw - 44).size()) * 13;
    }

    private boolean installationFormVisible() {
        return installationDraft && installations.canStart() && !installations.busy();
    }

    private List<Component> installationLines() {
        List<Component> lines = new ArrayList<>();
        Component error = installationError();
        if (error != null) lines.add(error);
        var plan = installations.preparedPlan().orElseGet(() -> installations.receipt().map(RuntimeInstallationWorkbench.Receipt::plan).orElse(null));
        installations.taskInput().ifPresent(input -> {
            lines.add(c("install_fixed_name").copy().append(input.key().type() + " / " + input.key().name()));
            lines.add(c("install_fixed_target").copy().append(input.worldLabel()));
            lines.add(c("world_identity").copy().append(input.worldId().toString()));
            lines.add(c("install_source").copy().append(input.documentName()));
        });
        var replaced = replacedInstallationPlan();
        if (replaced != null) {
            lines.add(c("install_replace_old"));
            lines.add(c("install_fixed_project").copy().append(replaced.projectId().toString()));
            lines.add(c("install_fixed_document").copy().append(replaced.documentId().toString()));
            lines.add(c("install_fixed_commit").copy().append(replaced.commitId().toString()));
            lines.add(c("install_fixed_installation").copy().append(replaced.installationId().toString()));
            for (var mapping : replaced.dimensions().entrySet()) lines.add(Component.literal(mapping.getKey() + " → " + mapping.getValue()));
            lines.add(c("install_replace_new"));
        }
        if (plan == null) installations.taskInput().ifPresent(input -> {
            lines.add(c("install_fixed_project").copy().append(input.projectId().toString()));
            lines.add(c("install_fixed_commit").copy().append(input.commitId().toString()));
            lines.add(c("install_fixed_document").copy().append(input.documentId().toString()));
        });
        if (plan != null) {
            lines.add(c("install_fixed_project").copy().append(plan.projectId().toString()));
            lines.add(c("install_fixed_commit").copy().append(plan.commitId().toString()));
            lines.add(c("install_fixed_document").copy().append(plan.documentId().toString()));
            lines.add(c("install_fixed_installation").copy().append(plan.installationId().toString()));
            for (var mapping : plan.dimensions().entrySet()) lines.add(Component.literal(mapping.getKey() + " → " + mapping.getValue()));
            installations.receipt().filter(receipt -> receipt.plan().equals(plan)).ifPresent(receipt -> lines.add(c("install_request").copy().append(receipt.requestId().toString())));
        }
        if (replacedInstallationPlan() != null) lines.add(c("install_replace_effects"));
        lines.add(c("install_saved_only"));
        if (installations.state() == RuntimeInstallationWorkbench.State.READY) lines.add(c("install_confirmation_hint"));
        if (installations.state() == RuntimeInstallationWorkbench.State.INSTALLED) lines.add(c("test_install_ready_hint"));
        return lines;
    }

    private int installationHeight() {
        int result = 0;
        for (var line : installationLines()) result += ui.components.wrap(line.getString(), Math.max(20, dw - 44)).size() * 13 + 12;
        return result;
    }

    private void installationForm() {
        int y = dy + 42;
        if (installationFormVisible()) {
            var profiles = installationSourceProfiles();
            if (installationProfile == null || !profiles.containsKey(installationProfile)) installationProfile = profiles.keySet().stream().findFirst().orElse(null);
            for (var line : installationDraftLines()) {
                int count = font.split(line, Math.max(20, dw - 48)).size();
                paragraph(line, dx + 14, y, dw - 48, count);
                y += count * 13 + 12;
            }
            label(c("install_profile"), dx + 14, y, dw - 48);
            y += 17;
            action("install/profile", installationProfile == null ? c("install_no_profile") : component(profiles.get(installationProfile).label()), dx + 14, y, dw - 48, () -> {
                var ids = new ArrayList<>(profiles.keySet());
                installationProfile = ids.get((ids.indexOf(installationProfile) + 1) % ids.size());
            }, !profiles.isEmpty(), "install_no_profile");
            y += 32;
            if (replacementDraft == null) {
                formField("install_name", "install_name", dx + 14, y, dw - 48);
                y += 48;
            }
            action("install/world", c("world_target").copy().append(" · ").append(worldTarget.targetLabel()), dx + 14, y, dw - 48, this::openWorldTarget);
            y += 32;
            paragraph(worldStatus(), dx + 14, y, dw - 48, 3);
            y += 42;
        }
        for (var line : installationFormVisible() ? List.of(c("install_saved_only"), c(replacementDraft == null ? "install_effects" : "install_replace_effects")) : installationLines()) {
            int count = font.split(line, Math.max(20, dw - 48)).size();
            paragraph(line, dx + 14, y, dw - 48, count);
            y += count * 13 + 12;
        }
    }

    private List<Component> installationRecordsHeader() {
        List<Component> lines = new ArrayList<>();
        installations.recordsInput().ifPresent(input -> {
            lines.add(c("install_fixed_target").copy().append(input.worldLabel()));
            lines.add(c("world_identity").copy().append(input.worldId().toString()));
        });
        Component error = installationError();
        if (error != null) lines.add(error);
        return lines;
    }

    private RuntimeInstallationWorkbench.RecordView installationRecordSelection() {
        return installations.records().stream().filter(r -> r.record().plan().installationId().equals(selectedInstallationRecord))
                .findFirst().orElseGet(() -> installations.records().stream().findFirst().orElse(null));
    }

    private List<Component> installationRecordDetails(RuntimeInstallationWorkbench.RecordView selected) {
        var plan = selected.record().plan();
        List<Component> lines = new ArrayList<>(List.of(c("install_fixed_name").copy().append(plan.key().type() + " / " + plan.key().name()),
                c(selected.online() ? "install_online" : "install_offline").copy().append(" · ").append(c("install_record_" + selected.record().state().name().toLowerCase(Locale.ROOT))),
                c("install_fixed_project").copy().append(plan.projectId().toString()),
                c("install_fixed_commit").copy().append(plan.commitId().toString()),
                c("install_fixed_document").copy().append(plan.documentId().toString()),
                c("install_fixed_installation").copy().append(plan.installationId().toString()),
                c(selected.online() ? "test_record_hint" : "install_records_hint")));
        if (selected.record().previous() != null) {
            var previous = selected.record().previous().plan();
            lines.add(c("install_replace_previous").copy().append(previous.commitId().toString()));
            lines.add(c("install_fixed_installation").copy().append(previous.installationId().toString()));
        }
        replacementRecordBlocker(selected).ifPresent(lines::add);
        return lines;
    }

    private int workflowParagraphHeight(Component line, int width) {
        return ui.components.wrap(line.getString(), Math.max(20, width)).size() * 13;
    }

    private int workflowParagraph(String id, Component line, int x, int y, int width) {
        int height = workflowParagraphHeight(line, width);
        ui.components.paragraph(id, line.getString(), new ControlTree.Bounds(x, y, Math.max(20, width), height), layer, Variant.DEFAULT);
        return height + 12;
    }

    private int installationParagraphsHeight(List<Component> lines, int width) {
        return lines.stream().mapToInt(line -> workflowParagraphHeight(line, width) + 12).sum();
    }

    private int installationRecordsHeight() {
        int result = 48 + installationParagraphsHeight(installationRecordsHeader(), dw - 44);
        var selected = installationRecordSelection();
        if (selected == null) return result + installationParagraphsHeight(List.of(c(installations.readingRecords() ? "install_loading" : "install_no_records")), dw - 44);
        int list = Math.max(100, (dw - 56) / 3), detail = installationParagraphsHeight(installationRecordDetails(selected), dw - list - 56);
        detail += 52 + (selected.record().previous() == null ? 0 : 26) + (selected.online() ? 26 : 0);
        return result + Math.max(installations.records().size() * 25, detail);
    }

    private void installationRecords() {
        int list = Math.max(100, (dw - 56) / 3), top = dy + 42;
        int headerIndex = 0;
        for (var line : installationRecordsHeader())
            top += workflowParagraph("install/record/header/" + headerIndex++, line, dx + 14, top, dw - 44);
        var records = installations.records();
        var selected = installationRecordSelection();
        if (selected == null) {
            workflowParagraph("install/record/empty", c(installations.readingRecords() ? "install_loading" : "install_no_records"), dx + 14, top, dw - 44);
            return;
        }
        selectedInstallationRecord = selected.record().plan().installationId();
        int row = 0;
        for (var record : records) {
            var plan = record.record().plan();
            UUID id = plan.installationId();
            tab("install-record/" + id, Component.literal(plan.key().name()), dx + 14, top + row++ * 25, list, id.equals(selectedInstallationRecord), () -> {
                selectedInstallationRecord = id;
                explicitInstallationRecord = record;
            });
        }
        var plan = selected.record().plan();
        int x = dx + list + 26, w = dw - list - 56, y = top;
        int detailIndex = 0;
        for (var line : installationRecordDetails(selected))
            y += workflowParagraph("install/record/detail/" + detailIndex++, line, x, y, w);
        if (selected.online()) {
            var source = installations.recordsInput().orElse(null);
            action("test/from-record", c("test_prepare"), x, y, w, () -> beginTest(new RuntimeTestWorkbench.Input(source.worldLabel(), source.worldId(), plan)),
                    source != null && tests.canPrepare(), "test_owned");
            y += 26;
        }
        action("install/replace/" + plan.installationId(), c("install_replace"), x, y, w, () -> openReplacement(selected), replacementRecordBlocker(selected).isEmpty(), "");
        replacementRecordBlocker(selected).ifPresent(reason -> ui.detail("install/replace/" + plan.installationId(), reason.getString()));
        y += 26;
        action("install/recover/" + plan.installationId(), c("install_recover"), x, y, w, () -> {
            replacementDraft = null;
            installationAction(() -> installations.recover(selected, false));
            installationDraft = false;
            show(Page.INSTALL);
        }, installations.canStart() && !selected.online(), "install_active_record");
        y += 26;
        if (selected.record().previous() != null) action("install/previous/" + plan.installationId(), c("install_previous"), x, y, w, () -> {
            replacementDraft = null;
            installationAction(() -> installations.recover(selected, true));
            installationDraft = false;
            show(Page.INSTALL);
        }, installations.canStart() && !selected.online(), "install_active_record");
    }

    private void openWorldTarget() {
        worldTarget.refresh();
        worldMappingError = null;
        var dimensions = worldTarget.dimensions();
        if (targetDimension == null || !dimensions.contains(targetDimension)) targetDimension = dimensions.isEmpty() ? null : worldTarget.mappings().values().stream().findFirst().orElse(dimensions.get(0));
        if (!form.containsKey("world_source") && !worldTarget.mappings().isEmpty()) form.put("world_source", worldTarget.mappings().keySet().iterator().next().toString());
        worldStep = worldTarget.state() == WorldTargetWorkbench.State.BOUND || worldTarget.canRegister() ? 2 : worldTarget.available() ? 1 : 0;
        show(Page.WORLD_TARGET);
    }

    private StudioComponents.TaskArea taskFrame(String id, Component title, String[] steps, int active) {
        return taskFrame(id, title, steps, active, 64);
    }

    private StudioComponents.TaskArea taskFrame(String id, Component title, String[] steps, int active, int footerHeight) {
        dw = Math.max(240, Math.min(660, width - 24));
        dh = Math.max(180, Math.min(430, height - 24));
        dx = (width - dw) / 2;
        dy = (height - dh) / 2;
        ui.dimOverlay = true;
        return ui.components.taskDialog(id, title.getString(), new ControlTree.Bounds(dx, dy, dw, dh), layer, taskSteps(id, steps, active), studioAction(id + "/close", c("cancel"), Icon.CLOSE, Variant.DEFAULT, true, false, this::cancelOverlay), footerHeight);
    }

    private void worldTargetDialog() {
        taskFrame("world/task", c("world_target"), new String[] { "world_step_target", "world_step_mapping", "world_step_verify" }, worldStep);
        label(c("world_connection").copy().append(worldTarget.targetLabel()), dx + 14, dy + 66, dw - 28);
        int top = dy + 91, bodyHeight = Math.max(25, dh - 157), content = worldStep == 1 ? 218 + worldTarget.mappings().size() * 28 : worldStep == 2 ? 220 + worldTarget.mappings().size() * 24 : 260;
        dialogMaxScroll = Math.max(0, content - bodyHeight);
        dialogScroll = Math.max(0, Math.min(dialogScroll, dialogMaxScroll));
        ui.clip(new ControlTree.Bounds(dx + 8, top, dw - 16, bodyHeight));
        int y = top - dialogScroll;
        if (worldStep == 0) {
            ui.heading(c("world_current_connection"), dx + 14, y, dw - 44, layer);
            y += 26;
            paragraph(worldStatus(), dx + 14, y, dw - 44, 5);
            y += 76;
            action("world/refresh", c("world_refresh"), dx + 14, y, 130, worldTarget::refresh, !worldTarget.busy(), "world_registering");
            y += 32;
            action("world/select-save", c(projectWorldActionKey()), dx + 14, y, 150, this::openAuthoringWorld, !projectWorld.busy(), "world_session_busy");
            y += 34;
            paragraph(c("world_binding_scope"), dx + 14, y, dw - 44, 5);
        } else if (worldStep == 1) {
            ui.heading(c("world_dimension_mapping"), dx + 14, y, dw - 44, layer);
            y += 27;
            for (var mapping : worldTarget.mappings().entrySet()) {
                label(Component.literal(mapping.getKey() + " → " + mapping.getValue()), dx + 14, y + 4, dw - 104);
                action("world/remove/" + mapping.getKey(), c("world_remove_mapping"), dx + dw - 82, y, 54, () -> worldTarget.removeMapping(mapping.getKey()), !worldTarget.busy(), "world_registering");
                y += 28;
            }
            formField("world_source", "world_source", dx + 14, y, dw - 44);
            y += 48;
            label(c("world_destination"), dx + 14, y, dw - 44);
            y += 18;
            var dimensions = worldTarget.dimensions();
            action("world/destination", targetDimension == null ? c("world_unavailable") : Component.literal(targetDimension.toString()), dx + 14, y, dw - 44, () -> targetDimension = dimensions.get((dimensions.indexOf(targetDimension) + 1) % dimensions.size()), !dimensions.isEmpty() && !worldTarget.busy(), "world_unavailable");
            y += 28;
            action("world/map", c("world_set_mapping"), dx + 14, y, 150, () -> {
                try {
                    worldTarget.setMapping(EditorId.parse(form.getOrDefault("world_source", "")), targetDimension);
                    worldMappingError = null;
                } catch (IllegalArgumentException error) {
                    worldMappingError = c("world_mapping_invalid");
                }
            }, worldTarget.available() && targetDimension != null && !worldTarget.busy(), "world_unavailable");
            y += 32;
            paragraph(c("world_coordinates"), dx + 14, y, dw - 44, 5);
        } else {
            ui.heading(worldStatus(), dx + 14, y, dw - 44, layer);
            y += 28;
            if (worldTarget.worldId().isPresent()) {
                paragraph(c("world_identity").copy().append(worldTarget.worldId().orElseThrow().toString()), dx + 14, y, dw - 44, 3);
                y += 47;
            }
            for (var mapping : worldTarget.mappings().entrySet()) {
                label(Component.literal(mapping.getKey() + " → " + mapping.getValue()), dx + 14, y, dw - 44);
                y += 24;
            }
            if (worldTarget.canRegister()) {
                action("world/register", c("world_register"), dx + 14, y, 180, () -> {
                    worldRegistrationPrompt = worldTarget.registrationPrompt();
                    show(Page.WORLD_REGISTER);
                });
                y += 30;
            }
            if (worldTarget.registrationReceipt().isPresent()) {
                action("world/registration-receipt", c("world_registration_receipt"), dx + 14, y, 180, this::openRegistrationReceipt);
                y += 30;
            }
            paragraph(c("world_binding_scope"), dx + 14, y, dw - 44, 5);
        }
        ui.clip(null);
        taskScrollRail(top, bodyHeight);
        label(worldMappingError == null ? worldStatus() : worldMappingError, dx + 14, dy + dh - 52, dw - 28);
        int bw = Math.min(130, (dw - 40) / 3), primary = dx + dw - 14 - bw;
        action("world/back-step", c(worldStep == 0 ? "cancel" : "previous"), primary - bw - 7, dy + dh - 30, bw, () -> {
            if (worldStep == 0) cancelOverlay();
            else {
                worldStep--;
                dialogScroll = 0;
            }
        }, !worldTarget.busy(), "world_registering");
        if (worldStep < 2) action("world/next-step", c("next"), primary, dy + dh - 30, bw, () -> {
            worldStep++;
            dialogScroll = 0;
        }, worldTarget.available() && (worldStep == 0 || !worldTarget.mappings().isEmpty()) && !worldTarget.busy(), "world_unavailable");
        else if (worldTarget.installationTarget().isPresent()) action("world/install", c("install_title"), primary, dy + dh - 30, bw, this::openInstallation);
        else action("world/verify", c("world_verify"), primary, dy + dh - 30, bw, worldTarget::bind, model.hasProject() && worldTarget.available() && !worldTarget.busy() && !worldTarget.mappings().isEmpty() && worldMappingError == null, "world_unavailable");
        action("world/disconnect", c("world_disconnect"), dx + 14, dy + dh - 30, bw, worldTarget::disconnect, !worldTarget.registering() && (worldTarget.state() == WorldTargetWorkbench.State.BOUND || worldTarget.busy()), "world_unverified");
    }

    private void taskScrollRail(int top, int height) {
        if (dialogMaxScroll > 0) {
            action("task/scroll-up", Component.literal("↑"), dx + dw - 25, top, 18, () -> dialogScroll -= 44);
            action("task/scroll-down", Component.literal("↓"), dx + dw - 25, top + height - 20, 18, () -> dialogScroll += 44);
        }
        ui.components.pixelScroll("task/body-scroll", new ControlTree.Bounds(dx + 8, top + 22, dw - 16, Math.max(0, height - 44)), dialogScroll, Math.max(0, height - 44) + dialogMaxScroll, layer, value -> {
            dialogScroll = value;
            request();
        });
    }

    private void cancelResourceTask() {
        model.cancelResourceImport();
        inspectingResource = inspectingResource && model.busy();
        acceptingResource = acceptingResource && model.busy();
        resourceTaskKnown = inspectingResource || acceptingResource;
        resourceTaskFinished = false;
        resourceTaskSummary = List.of();
        resourceTaskSource = null;
        resourceTaskStatus = resourceTaskKnown ? component(model.status()) : null;
    }

    private void openResourceImport() {
        show(acceptingResource || inspectingResource || model.resourceImportReady() ? Page.RESOURCE_REPORT : Page.RESOURCE_IMPORT);
    }

    private Optional<ProjectWorkbench.FailureDiagnostic> resourceFailure() {
        return model.busy() ? Optional.empty() : model.failureDiagnostic().filter(failure -> failure.operation().equals("resource_inspecting") || failure.operation().equals("resource_staging"));
    }

    private Component resourcePageStatus() {
        if (acceptingResource) return c("resource_staging");
        if (inspectingResource) return c(model.status().equals(ProjectWorkbench.text("cancelling")) ? "cancelling" : "resource_inspecting");
        if (model.resourceImportReady()) return c("resource_import_ready");
        if (resourceTaskFinished) return c("resource_imported");
        if (page == Page.RESOURCE_REPORT && resourceTaskKnown && resourceTaskStatus != null && model.failureDiagnostic().isEmpty()) return resourceTaskStatus;
        return c("ready");
    }

    private void resourceTaskDialog() {
        int step = page == Page.RESOURCE_IMPORT ? 0 : resourceTaskFinished ? 2 : 1;
        var failure = resourceFailure();
        int taskWidth = Math.max(240, Math.min(660, width - 24));
        int footerHeight = failure.map(diagnostic -> 40 + ui.components.noticeHeight(component(diagnostic.reason()).getString(), taskWidth - 16)).orElse(64);
        taskFrame("resource/task", c("resource_import"), new String[] { "resource_step_source", "resource_step_check", "resource_step_result" }, step, footerHeight);
        int top = dy + 70, bodyHeight = Math.max(0, dh - 74 - footerHeight), sourceHeight = 0;
        var formats = new ArrayList<>(model.resourceImporters().keySet());
        Collections.sort(formats);
        if (resourceImporter == null || !model.resourceImporters().containsKey(resourceImporter)) resourceImporter = formats.isEmpty() ? null : formats.get(0);
        var importer = resourceImporter == null ? null : model.resourceImporters().get(resourceImporter);
        if (importer != null) sourceHeight = 110 + importer.inputs().size() * 72 + (dw < 448 ? 34 : 0);
        var lines = resourceTaskSummary.isEmpty() ? model.resourceImportSummary().stream().map(WorkbenchScreen::component).toList() : resourceTaskSummary;
        var reportIntro = new StudioComponents.SectionIntro(c(resourceTaskFinished ? "resource_imported" : acceptingResource ? "resource_staging" : "resource_report").getString(), c(resourceTaskFinished ? "resource_reopen_hint" : "resource_preflight_hint").getString(), "");
        int introHeight = ui.components.introHeight(reportIntro, dw - 32), reportHeight = introHeight + 8 + (resourceTaskSource == null ? 0 : 26);
        for (var line : lines) reportHeight += ui.components.wrap(line.getString(), Math.max(20, dw - 44)).size() * 13 + 14;
        dialogMaxScroll = Math.max(0, (step == 0 ? Math.max(sourceHeight, formats.size() * 29) : reportHeight) - bodyHeight);
        dialogScroll = Math.max(0, Math.min(dialogScroll, dialogMaxScroll));
        ui.clip(new ControlTree.Bounds(dx + 8, top, dw - 16, bodyHeight));
        int y = top - dialogScroll;
        if (step == 0) {
            var adapters = new ArrayList<StudioComponents.Action>();
            for (EditorId id : formats) adapters.add(studioAction("resource/adapter/" + id, component(model.resourceImporters().get(id).label()), Icon.NONE, Variant.DEFAULT, true, id.equals(resourceImporter), () -> {
                resourceImporter = id;
                dialogScroll = 0;
            }));
            var area = ui.components.wizard("resource/source", new ControlTree.Bounds(dx + 8, y, dw - 28, Math.max(bodyHeight, sourceHeight)), layer, adapters);
            int x = area.content().x(), w = area.content().width();
            y = area.content().y();
            if (importer == null) paragraph(c("missing_importer"), x, y, w, 5);
            else {
                ui.heading(component(importer.label()), x, y, w, layer);
                y += 29;
                for (var source : importer.inputs()) {
                    String key = "resource_import_" + resourceImporter + "/" + source.id();
                    pathInput(key, key, component(source.label()), x, y, w, false, false);
                    y += 72;
                }
                y += 8;
                ui.components.paragraph("resource/source-hint", c("resource_input_hint").getString(), new ControlTree.Bounds(x + 6, y, Math.max(0, w - 12), 65), layer, Variant.MUTED);
            }
        } else {
            if (resourceTaskSource != null) {
                label(resourceTaskSource, dx + 14, y, dw - 44);
                y += 26;
            }
            ui.components.sectionIntro("resource/report-intro", reportIntro, new ControlTree.Bounds(dx + 8, y, dw - 32, introHeight), layer);
            y += introHeight + 8;
            int index = 0;
            for (var line : lines) {
                int lineHeight = ui.components.wrap(line.getString(), dw - 44).size() * 13;
                ui.components.paragraph("resource/report/" + index++, line.getString(), new ControlTree.Bounds(dx + 14, y, dw - 44, lineHeight), layer, Variant.DEFAULT);
                y += lineHeight + 14;
            }
        }
        ui.clip(null);
        taskScrollRail(top, bodyHeight);
        if (failure.isPresent()) {
            var diagnostic = failure.orElseThrow();
            String reason = component(diagnostic.reason()).getString();
            ui.components.notice("resource/failure", studioAction("resource/failure-details", c("studio_failure_event", diagnostic.eventId()), Icon.WARNING, Variant.DANGER, true, false, () -> show(Page.PROBLEMS)), reason, new ControlTree.Bounds(dx + 8, dy + dh - footerHeight + 4, dw - 16, footerHeight - 40), layer);
        } else label(resourcePageStatus(), dx + 14, dy + dh - 52, dw - 28);
        int bw = Math.min(150, (dw - 35) / 2), primary = dx + dw - 14 - bw, secondary = primary - bw - 7;
        if (step == 0) {
            action("resource-import/cancel", c("cancel"), secondary, dy + dh - 30, bw, this::cancelOverlay);
            action("resource-import/inspect", c("inspect_import"), primary, dy + dh - 30, bw, this::inspectResourceImport, importer != null && !model.busy(), "missing_importer");
        } else if (resourceTaskFinished) {
            action("resource/close", c("back"), secondary, dy + dh - 30, bw, this::cancelOverlay);
            action("resource/browse", c("resource_browser"), primary, dy + dh - 30, bw, () -> {
                cancelResourceTask();
                show(Page.RESOURCES);
            });
        } else if (acceptingResource) {
            action("resource/cancel-task", c("install_request_stop"), secondary, dy + dh - 30, bw, model::cancelResourceImport, model.canCancel(), "publishing");
            action("resource/hide", c("hide_to_tasks"), primary, dy + dh - 30, bw, () -> {
                hideOverlay();
                activateBottom(WorkspaceTemplates.TASKS);
            });
        } else {
            action("resource-import/back", c(model.busy() ? "cancel_task" : "previous"), secondary, dy + dh - 30, bw, () -> {
                cancelResourceTask();
                show(Page.RESOURCE_IMPORT);
            }, !model.busy() || model.canCancel(), "publishing");
            action("resource-import/accept", c("resource_accept"), primary, dy + dh - 30, bw, () -> {
                resourceTaskSummary = model.resourceImportSummary().stream().map(WorkbenchScreen::component).toList();
                model.acceptResourceImport();
                acceptingResource = true;
            }, model.resourceImportReady() && !model.busy(), "inspect_import");
        }
    }

    private void worldTargetForm() {
        label(c("world_connection").copy().append(worldTarget.targetLabel()), dx + 14, dy + 40, dw - 44);
        paragraph(worldStatus(), dx + 14, dy + 62, dw - 44, 4);
        int y = dy + 116;
        if (worldTarget.canRegister()) {
            action("world/register", c("world_register"), dx + 14, y, dw - 44, () -> {
                worldRegistrationPrompt = worldTarget.registrationPrompt();
                show(Page.WORLD_REGISTER);
            });
            y += 32;
        }
        if (worldTarget.registrationReceipt().isPresent()) {
            action("world/registration-receipt", c("world_registration_receipt").copy().append(" · ").append(registrationStatus()), dx + 14, y, dw - 44, this::openRegistrationReceipt);
            y += 32;
        }
        if (worldTarget.worldId().isPresent()) {
            label(c("world_identity").copy().append(worldTarget.worldId().orElseThrow().toString()), dx + 14, y, dw - 44);
            y += 28;
        }
        for (var mapping : worldTarget.mappings().entrySet()) {
            label(Component.literal(mapping.getKey() + " → " + mapping.getValue()), dx + 14, y, dw - 100);
            action("world/remove/" + mapping.getKey(), c("world_remove_mapping"), dx + dw - 82, y - 5, 48, () -> worldTarget.removeMapping(mapping.getKey()), !worldTarget.registering(), "world_registering");
            y += 28;
        }
        formField("world_source", "world_source", dx + 14, y, dw - 44);
        y += 48;
        var dimensions = worldTarget.dimensions();
        label(c("world_destination"), dx + 14, y, dw - 44);
        action("world/destination", targetDimension == null ? c("world_unavailable") : Component.literal(targetDimension.toString()), dx + 14, y + 16, dw - 44, () -> targetDimension = dimensions.get((dimensions.indexOf(targetDimension) + 1) % dimensions.size()), !dimensions.isEmpty() && !worldTarget.registering(), "world_unavailable");
        y += 48;
        action("world/map", c("world_set_mapping"), dx + 14, y, dw - 44, () -> {
            try {
                worldTarget.setMapping(EditorId.parse(form.getOrDefault("world_source", "")), targetDimension);
                worldMappingError = null;
            } catch (IllegalArgumentException failure) {
                worldMappingError = c("world_mapping_invalid");
            }
        }, worldTarget.available() && targetDimension != null && !worldTarget.registering(), "world_unavailable");
        paragraph(c("world_coordinates"), dx + 14, y + 36, dw - 44, 4);
        action("world/install", c("install_title"), dx + 14, y + 94, dw - 44, this::openInstallation, worldTarget.installationTarget().isPresent(), "world_unverified");
    }

    private void openRegistrationReceipt() {
        worldRegistrationPrompt = null;
        show(Page.WORLD_REGISTER);
    }

    private Component registrationStatus() {
        if (worldRegistrationPrompt != null) return c(worldTarget.canRegister(worldRegistrationPrompt) ? "world_registration_confirm_hint" : "world_registration_stale");
        var receipt = worldTarget.registrationReceipt();
        if (receipt.isEmpty()) return c("world_unverified");
        var result = receipt.orElseThrow().result();
        return result == null ? c("world_registering") : result.worldId() != null ? c("world_registered_verify") : component(result.failure());
    }

    private List<Component> worldRegistrationLines() {
        List<Component> lines = new ArrayList<>();
        String label = worldRegistrationPrompt != null ? worldRegistrationPrompt.targetLabel() : worldTarget.registrationReceipt().map(WorldTargetWorkbench.RegistrationReceipt::targetLabel).orElse("");
        lines.add(c("world_registration_target").copy().append(label));
        lines.add(registrationStatus());
        if (worldRegistrationPrompt == null) worldTarget.registrationReceipt().ifPresent(receipt -> {
            if (receipt.result() != null && receipt.result().worldId() != null)
                lines.add(c("world_identity").copy().append(receipt.result().worldId().toString()));
        });
        lines.add(c("world_registration_explain"));
        lines.add(c("world_registration_continues"));
        lines.add(c("install_registration_hint"));
        return lines;
    }

    private int worldRegistrationHeight() {
        int result = 40;
        for (var line : worldRegistrationLines()) result += font.split(line, Math.max(20, dw - 44)).size() * 13 + 14;
        return result;
    }

    private void worldRegistrationForm() {
        int y = dy + 40;
        for (var line : worldRegistrationLines()) {
            int count = font.split(line, Math.max(20, dw - 44)).size();
            paragraph(line, dx + 14, y, dw - 44, count);
            y += count * 13 + 14;
        }
    }

    private void projectDialog() {
        String id = "dialog/" + page;
        int dialogWidth = Math.max(120, Math.min(studioUnits(650), width - 24));
        int contentWidth = Math.max(0, dialogWidth - 24);
        var content = page == Page.PROJECT ? projectView(contentWidth) : page == Page.PROJECT_WORLD ? projectWorldView() : newDocumentView();
        var actions = new ArrayList<StudioComponents.Action>();
        collectingFooter = actions;
        try {
            footerActions();
        } finally {
            collectingFooter = null;
        }
        var footerParts = new ArrayList<StudioView.Element>();
        Component status = page == Page.PROJECT ? projectPageStatus() : page == Page.PROJECT_WORLD ? projectWorldPanelStatus() : component(model.status());
        footerParts.add(ui.view.text(id + "/status", status.getString(), TextSize.SMALL, Variant.MUTED));
        Component error = page == Page.PROJECT ? projectCreationError : page == Page.PROJECT_WORLD ? projectWorldPanelMessage() : null;
        if (error != null && !error.getString().equals(status.getString())) content = ui.view.column(id + "/feedback-content", Style.defaults().gap(8), List.of(ui.view.paragraph(id + "/error", error.getString(), TextSize.SMALL, Variant.DANGER), content));
        var arranged = ui.view.layoutContent(content, 0, 0, contentWidth);
        footerParts.add(ui.view.wrap(id + "/actions", Style.defaults().gap(6).mainAlign(FlowLayout.MainAlign.END), actions.stream().map(ui.view::button).toList()));
        var footer = ui.view.column(id + "/footer", Style.defaults().gap(6), footerParts);
        var header = ui.view.row(id + "/header", Style.defaults().gap(8), List.of(ui.view.paragraph(id + "/title", c(titleKey()).getString(), TextSize.TITLE, Variant.DEFAULT), ui.view.button(studioAction("dialog/close", c("cancel"), Icon.CLOSE, Variant.DEFAULT, true, false, this::cancelOverlay), Kind.ICON)));
        var limits = new FlowLayout.Constraints(dialogWidth - 16, Integer.MAX_VALUE);
        int natural = ui.view.measure(header, limits).height() + ui.view.measure(footer, limits).height() + arranged.contentHeight() + 32;
        int dialogHeight = Math.max(100, Math.min(height - 24, natural));
        dx = (width - dialogWidth) / 2;
        dy = (height - dialogHeight) / 2;
        dw = dialogWidth;
        dh = dialogHeight;
        var shell = ui.view.layout(ui.view.surface(id, Kind.DIALOG, Style.fill().padding(Insets.all(8)).gap(8), List.of(header, ui.view.placeholder(id + "/viewport", Style.fill()), footer)), new ControlTree.Bounds(dx, dy, dw, dh));
        ui.dimOverlay = true;
        ui.emit(shell, layer);
        var viewport = shell.bounds(id + "/viewport");
        dialogMaxScroll = Math.max(0, arranged.contentHeight() - viewport.height());
        dialogScroll = ui.components.scrollContent(id + "/body", viewport, arranged.contentHeight(), layer, dialogScroll, value -> {
            dialogScroll = value;
            request();
        }, body -> ui.emit(arranged.translated(body.x(), body.y()), layer));
    }

    private StudioView.Element projectView(int width) {
        var content = new ArrayList<StudioView.Element>();
        if (model.hasProject() || model.pendingCreation()) {
            content.add(sectionIntroView("project/details", new StudioComponents.SectionIntro(model.hasProject() ? model.projectName() : projectPageStatus().getString(), model.directory().orElseThrow().toString(), "")));
            content.add(projectWorldSectionView(false));
            content.add(ui.view.button(menuAction("project/close-form", "close_project", Icon.CLOSE, !projectWorld.busy(), "busy", () -> show(Page.CLOSE))));
            return ui.view.column("project/content", Style.defaults().gap(12), content);
        }
        ensureProjectTemplate();
        boolean editable = !model.busy() && !projectWorld.busy();
        content.add(ui.view.wrap("project/modes", Style.defaults().gap(4), List.of(
                ui.view.button(studioAction("project/mode-new", c("new_project"), Icon.PLUS, Variant.DEFAULT, editable, creating, () -> {
                    pathPicker.cancelPending();
                    projectWorld.cancelInspection();
                    projectDraftGeneration++;
                    creating = true;
                    projectCreationError = null;
                    dialogScroll = 0;
                }), Kind.TAB),
                ui.view.button(studioAction("project/mode-open", c("open_project"), Icon.FOLDER, Variant.DEFAULT, editable, !creating, () -> {
                    pathPicker.cancelPending();
                    projectWorld.cancelInspection();
                    projectDraftGeneration++;
                    creating = false;
                    projectCreationError = null;
                    dialogScroll = 0;
                }), Kind.TAB))));
        if (!creating) {
            content.add(pathInputView("form/project_path", "project_path", c("project_location"), true, false));
            content.add(ui.view.paragraph("project/open-note", c("project_open_note").getString(), TextSize.SMALL, Variant.MUTED));
        } else {
            var categories = new ArrayList<StudioView.Element>();
            categories.add(ui.view.text("project/category-label", c("studio_project_category").getString(), TextSize.SMALL, Variant.MUTED));
            for (var entry : model.projectCategories().entrySet()) {
                EditorId category = entry.getKey();
                categories.add(ui.view.button(studioAction("project/category/" + category, component(entry.getValue().label()), Icon.NONE, Variant.DEFAULT, editable, category.equals(projectCategory), () -> {
                    pathPicker.cancelPending();
                    projectWorld.cancelInspection();
                    projectDraftGeneration++;
                    projectWorldAcknowledged = false;
                    projectCategory = category;
                    projectTemplate = null;
                    projectCreationError = null;
                    ensureProjectTemplate();
                })).style(Style.defaults()));
            }
            var details = new ArrayList<StudioView.Element>();
            details.add(ui.view.text("project/template-label", c("studio_project_template").getString(), TextSize.SMALL, Variant.MUTED));
            var templates = new ArrayList<StudioView.Element>();
            for (var entry : (projectCategory == null ? Map.<EditorId, net.ptcrys.fpsmatch.editor.common.ui.workbench.ProjectTemplate<?>>of() : model.projectTemplates(projectCategory)).entrySet()) {
                EditorId template = entry.getKey();
                templates.add(ui.view.button(studioAction("project/template/" + template, component(entry.getValue().label()), Icon.MAP, Variant.OUTLINED, editable, template.equals(projectTemplate), () -> {
                    pathPicker.cancelPending();
                    projectWorld.cancelInspection();
                    projectDraftGeneration++;
                    projectWorldAcknowledged = false;
                    projectTemplate = template;
                    projectCreationError = null;
                    projectTemplateRaw();
                })));
            }
            details.add(ui.view.wrap("project/templates", Style.defaults().gap(6), templates));
            if (projectTemplate == null) details.add(ui.view.paragraph("project/no-templates", c("studio_no_templates").getString()));
            else {
                details.add(ui.view.fields("project/identity/name", List.of(projectTextField("project_name", "project_name")), null));
                details.add(ui.view.fields("project/identity/document", List.of(projectTextField("first_document_name", "document_name")), null));
                details.add(pathInputView("form/project_path", "project_path", c("project_location"), true, true));
                if (creationRequiresWorld()) details.add(projectWorldSectionView(true));
                details.add(ui.view.text("project/parameters-label", c("studio_template_parameters").getString(), TextSize.SMALL, Variant.MUTED));
                var raw = projectTemplateRaw();
                for (var field : model.projectTemplateFields(projectTemplate)) {
                    String property = field.property(), id = "project-template/" + projectTemplate + "/" + property, value = raw.getOrDefault(property, field.raw());
                    var kind = field.kind() == DocumentEditor.Kind.BOOLEAN ? StudioComponents.FieldKind.TOGGLE : field.kind() == DocumentEditor.Kind.CHOICE ? StudioComponents.FieldKind.CHOICE : StudioComponents.FieldKind.TEXT;
                    var choices = field.choices();
                    int current = -1;
                    for (int i = 0; i < choices.size(); i++) if (choices.get(i).raw().equals(value)) current = i;
                    int next = choices.isEmpty() ? 0 : (current + 1) % choices.size();
                    String caption = kind == StudioComponents.FieldKind.TOGGLE ? c(Boolean.parseBoolean(value) ? "boolean_on" : "boolean_off").getString() : current >= 0 ? component(choices.get(current).label()).getString() : value;
                    var control = new StudioComponents.Field(id, component(field.label()).getString(), value, caption, component(field.unit()).getString(), kind, ui.theme.muted(), editable && (kind != StudioComponents.FieldKind.CHOICE || !choices.isEmpty()), false, text -> {
                        raw.put(property, text);
                        projectCreationError = null;
                    }, () -> run(() -> {
                        projectCreationError = null;
                        if (kind == StudioComponents.FieldKind.TOGGLE) raw.put(property, Boolean.toString(!Boolean.parseBoolean(value)));
                        else if (!choices.isEmpty()) raw.put(property, choices.get(next).raw());
                    }), () -> {});
                    details.add(ui.view.fields(id + "/row", List.of(control), null));
                }
                details.add(ui.view.paragraph("project/atomic-note", c("studio_template_atomic").getString(), TextSize.SMALL, Variant.MUTED));
            }
            boolean sidebar = width >= studioUnits(500);
            var categoryView = sidebar ? ui.view.column("project/categories", Style.defaults().width(Length.fixed(studioUnits(145))).gap(4), categories) : ui.view.wrap("project/categories", Style.defaults().gap(4), categories);
            var detailView = ui.view.column("project/details-form", Style.defaults().gap(8), details);
            content.add(sidebar ? ui.view.row("project/creation", Style.defaults().gap(12), List.of(categoryView, detailView)) : ui.view.column("project/creation", Style.defaults().gap(12), List.of(categoryView, detailView)));
        }
        projectFailure().ifPresent(failure -> {
            content.add(ui.view.paragraph("project/failure", component(failure.reason()).getString(), TextSize.SMALL, Variant.DANGER));
            content.add(ui.view.button(studioAction("project/failure-details", c("studio_failure_event", failure.eventId()), Icon.WARNING, Variant.DANGER, true, false, () -> show(Page.PROBLEMS))));
        });
        return ui.view.column("project/content", Style.defaults().gap(12), content);
    }

    private Optional<ProjectWorkbench.FailureDiagnostic> projectFailure() {
        return model.busy() ? Optional.empty() : model.failureDiagnostic().filter(failure -> failure.operation().equals(creating ? "creating" : "opening"));
    }

    private Component projectPageStatus() {
        if (projectWorld.busy() || model.hasProject() && projectWorld.requiresWorld()) return component(projectWorld.status());
        boolean ownOperation = model.status().equals(ProjectWorkbench.text(creating ? "creating" : "opening")) || model.pendingCreation() && model.status().equals(ProjectWorkbench.text("reconciling"));
        if (model.busy() && ownOperation) return component(model.status());
        return c(model.protectedMode() ? "protected_status" : "ready");
    }

    private StudioComponents.Field projectTextField(String key, String label) {
        String value = form.getOrDefault(key, "");
        return new StudioComponents.Field("form/" + key, c(label).getString(), value, value, "", StudioComponents.FieldKind.TEXT, ui.theme.muted(), !model.busy() && !projectWorld.busy(), false, text -> {
            pathPicker.cancelPending();
            form.put(key, text);
            projectCreationError = null;
        }, () -> {}, () -> {});
    }

    private void ensureProjectTemplate() {
        var categories = model.projectCategories();
        if (projectCategory == null || !categories.containsKey(projectCategory)) projectCategory = categories.keySet().stream().findFirst().orElse(null);
        if (projectCategory == null) {
            projectTemplate = null;
            return;
        }
        var candidates = model.projectTemplates(projectCategory);
        if (projectTemplate == null || !candidates.containsKey(projectTemplate)) projectTemplate = candidates.keySet().stream().findFirst().orElse(null);
        if (projectTemplate != null) projectTemplateRaw();
    }

    private Map<String, String> projectTemplateRaw() {
        if (projectTemplate == null) return Map.of();
        return templateDrafts.computeIfAbsent(projectTemplate, id -> {
            Map<String, String> raw = new LinkedHashMap<>();
            for (var field : model.projectTemplateFields(id)) raw.put(field.property(), field.raw());
            return raw;
        });
    }

    private void formField(String id, String label, int x, int y, int w) {
        boolean inline = w >= 300;
        int inputX = inline ? x + 92 : x;
        label(c(label), x, y + (inline ? 6 : 0), inline ? 86 : w);
        ui.edit("form/" + id, c(label), form.getOrDefault(id, ""), inputX, y + (inline ? 0 : 17), w - (inputX - x), layer, true, value -> form.put(id, value));
    }

    private StudioView.Element newDocumentView() {
        var content = new ArrayList<StudioView.Element>();
        if (!model.hasProject()) content.add(ui.view.paragraph("document/missing", c("missing_project").getString()));
        else {
            var types = new ArrayList<>(model.documentTypes().keySet());
            if (documentType == null && !types.isEmpty()) documentType = types.get(0);
            content.add(ui.view.fields("document/name", List.of(projectTextField("document_name", "document_name")), null));
            for (var type : types) content.add(ui.view.button(studioAction("document/type/" + type, component(model.typeLabel(type)), Icon.MAP, Variant.OUTLINED, true, type.equals(documentType), () -> documentType = type)).style(Style.defaults()));
        }
        return ui.view.column("document/content", Style.defaults().gap(8), content);
    }

    private StudioComponents.Field draftField(String id, String key, Component label, boolean enabled) {
        String value = form.getOrDefault(key, "");
        return new StudioComponents.Field(id, label.getString(), value, value, "", StudioComponents.FieldKind.TEXT, ui.theme.muted(), enabled, false, text -> form.put(key, text), () -> {}, () -> {});
    }

    private String importInputKey(String sourceId) {
        return "import_" + importer + "/" + sourceId;
    }

    private void openImportTask() {
        boolean resume = inspectingImport || model.importReady();
        if (!resume) {
            importTaskFinished = false;
            importedDocument = null;
            importTaskSource = null;
            importTaskStatus = null;
            importResultProblems = List.of();
            importFailureDetails = false;
        }
        show(resume ? Page.IMPORT_REPORT : Page.IMPORT);
    }

    private void cancelImportTask() {
        model.cancelImport();
        inspectingImport = inspectingImport && model.busy();
        importTaskFinished = false;
        importedDocument = null;
        importTaskSource = null;
        importTaskStatus = null;
        importResultProblems = List.of();
        importFailureDetails = false;
    }

    private Optional<ProjectWorkbench.FailureDiagnostic> importFailure() {
        return model.busy() ? Optional.empty() : model.failureDiagnostic().filter(failure -> failure.operation().equals("inspecting") || failure.operation().equals("import_accepting"));
    }

    private Component importPageStatus() {
        if (inspectingImport) return c(model.status().equals(ProjectWorkbench.text("cancelling")) ? "cancelling" : "inspecting");
        if (importTaskFinished) return c("imported");
        if (importFailure().isPresent()) return c("failed");
        if (model.importReady()) return c("import_ready");
        return page == Page.IMPORT_REPORT && importTaskStatus != null ? importTaskStatus : c("ready");
    }

    private void acceptDocumentImport() {
        guardCreation(() -> {
            var problems = model.importProblems();
            try {
                model.acceptImport(form.getOrDefault("import_name", ""));
                importResultProblems = problems;
                importedDocument = model.activeDocument().orElse(null);
                importTaskFinished = true;
                importTaskStatus = c("imported");
            } catch (RuntimeException failure) {
                model.reportImportAcceptanceFailure(failure);
                importTaskStatus = component(model.status());
            }
            importFailureDetails = false;
            show(Page.IMPORT_REPORT);
        });
    }

    private void importTaskDialog() {
        int step = page == Page.IMPORT ? 0 : importTaskFinished ? 2 : 1;
        var area = taskFrame("import/task", c("import_legacy"), new String[] { "resource_step_source", "resource_step_check", "resource_step_result" }, step);
        var viewport = area.content();
        int contentWidth = Math.max(0, viewport.width() - 10);
        var formats = new ArrayList<>(model.importers().keySet());
        Collections.sort(formats);
        if (importer == null || !model.importers().containsKey(importer)) importer = formats.isEmpty() ? null : formats.get(0);
        var selected = importer == null ? null : model.importers().get(importer);
        var failure = importFailure();
        var problems = importTaskFinished ? importResultProblems : model.importProblems();
        var sourceFields = new ArrayList<StudioComponents.Field>();
        if (selected != null) for (var source : selected.inputs()) {
            String key = importInputKey(source.id());
            sourceFields.add(draftField(key, key, component(source.label()), !model.busy()));
        }
        var sourceArea = ui.components.wizardArea(new ControlTree.Bounds(0, 0, contentWidth, Math.max(34, viewport.height())));
        int sourceTextWidth = Math.max(0, sourceArea.content().width() - 12);
        String sourceTitle = selected == null ? c("missing_importer").getString() : component(selected.label()).getString();
        int sourceTitleHeight = ui.components.wrap(sourceTitle, sourceTextWidth).size() * 13 + 12;
        var sourceLayouts = sourceFields.stream().map(field -> ui.components.measureDocumentFields(List.of(field), null, sourceArea.content().width())).toList();
        int sourceHintHeight = ui.components.wrap(c("import_input_hint").getString(), sourceTextWidth).size() * 13;
        int sourceHeight = sourceArea.content().y() + sourceTitleHeight + sourceFields.size() * 72 + sourceHintHeight + 16;
        if (contentWidth >= 420) sourceHeight = Math.max(sourceHeight, formats.size() * (ui.components.metrics().row() + 5) + 12);
        var intro = new StudioComponents.SectionIntro(c(importTaskFinished ? "resource_step_result" : "import_report").getString(), c(importTaskFinished ? "import_result_hint" : "import_preflight_hint").getString(), "");
        int introHeight = ui.components.introHeight(intro, contentWidth), textWidth = Math.max(0, contentWidth - 12);
        var nameField = draftField("form/import_name", "import_name", c("document_name"), !model.busy());
        var nameLayout = ui.components.measureDocumentFields(List.of(nameField), null, contentWidth);
        String source = importTaskSource == null ? "" : importTaskSource.getString();
        int sourceHeightReport = source.isEmpty() ? 0 : ui.components.wrap(source, textWidth, TextSize.SMALL).size() * 13 + 10;
        String resultName = importedDocument == null ? "" : Optional.ofNullable(listProjection.documents().get(importedDocument)).map(document -> c("document_name").copy().append(": ").append(document.displayName()).getString()).orElse("");
        int resultHeight = resultName.isEmpty() ? 0 : ui.components.wrap(resultName, textWidth).size() * 13 + 12;
        String reason = failure.map(diagnostic -> component(diagnostic.reason()).getString()).orElse("");
        String details = failure.filter(ignored -> importFailureDetails).map(diagnostic -> c("studio_failure_summary", diagnostic.operation(), diagnostic.exceptionType()).getString()).orElse("");
        int failureHeight = failure.isPresent() ? ui.components.noticeHeight(reason, contentWidth) + 8 : 0;
        int detailsHeight = details.isEmpty() ? 0 : ui.components.wrap(details, textWidth, TextSize.SMALL).size() * 13 + 12;
        int reportHeight = sourceHeightReport + introHeight + 8 + (importTaskFinished ? resultHeight : nameLayout.height() + 8) + failureHeight + detailsHeight;
        var messages = problems.stream().map(problem -> c("test_severity_" + problem.severity().name().toLowerCase(Locale.ROOT)).copy().append(" · ").append(component(problem.message())).getString()).toList();
        for (int i = 0; i < problems.size(); i++) reportHeight += ui.components.wrap(messages.get(i), textWidth).size() * 13 + (problems.get(i).target().isBlank() ? 0 : ui.components.wrap(problems.get(i).target(), textWidth, TextSize.SMALL).size() * 13) + 12;
        int contentHeight = step == 0 ? sourceHeight : reportHeight;
        dialogMaxScroll = Math.max(0, contentHeight - viewport.height());
        dialogScroll = Math.max(0, Math.min(dialogScroll, dialogMaxScroll));
        try (var ignored = ui.components.frame().clipped(viewport)) {
            int y = viewport.y() - dialogScroll;
            if (step == 0) {
                var adapters = new ArrayList<StudioComponents.Action>();
                for (EditorId id : formats) adapters.add(studioAction("import/adapter/" + id, component(model.importers().get(id).label()), Icon.NONE, Variant.DEFAULT, !model.busy(), id.equals(importer), () -> {
                    importer = id;
                    dialogScroll = 0;
                }));
                var wizard = ui.components.wizard("import/source", new ControlTree.Bounds(viewport.x(), y, contentWidth, Math.max(viewport.height(), sourceHeight)), layer, adapters);
                int x = wizard.content().x();
                y = wizard.content().y();
                ui.components.paragraph("import/source-title", sourceTitle, new ControlTree.Bounds(x + 6, y, sourceTextWidth, sourceTitleHeight), layer, Variant.DEFAULT);
                y += sourceTitleHeight;
                if (selected != null) for (var input : selected.inputs()) {
                    String key = importInputKey(input.id());
                    pathInput(key, key, component(input.label()), x, y, wizard.content().width(), input.directory(), false);
                    y += 72;
                }
                ui.components.paragraph("import/source-hint", c("import_input_hint").getString(), new ControlTree.Bounds(x + 6, y + 8, sourceTextWidth, sourceHintHeight), layer, Variant.MUTED);
            } else {
                int x = viewport.x();
                if (!source.isEmpty()) {
                    ui.components.paragraph("import/report-source", source, new ControlTree.Bounds(x + 6, y, textWidth, sourceHeightReport - 10), layer, TextSize.SMALL, Variant.MUTED);
                    y += sourceHeightReport;
                }
                ui.components.sectionIntro("import/report-intro", intro, new ControlTree.Bounds(x, y, contentWidth, introHeight), layer);
                y += introHeight + 8;
                if (failure.isPresent()) {
                    var diagnostic = failure.orElseThrow();
                    ui.components.notice("import/failure", studioAction("import/failure-details", c("studio_failure_event", diagnostic.eventId()), Icon.WARNING, Variant.DANGER, true, importFailureDetails, () -> importFailureDetails = !importFailureDetails), reason, new ControlTree.Bounds(x, y, contentWidth, failureHeight - 8), layer);
                    y += failureHeight;
                    if (!details.isEmpty()) {
                        ui.components.paragraph("import/failure-summary", details, new ControlTree.Bounds(x + 6, y, textWidth, detailsHeight - 12), layer, TextSize.SMALL, Variant.MUTED);
                        y += detailsHeight;
                    }
                }
                if (importTaskFinished) {
                    if (!resultName.isEmpty()) {
                        ui.components.paragraph("import/result-document", resultName, new ControlTree.Bounds(x + 6, y, textWidth, resultHeight - 12), layer, Variant.DEFAULT);
                        y += resultHeight;
                    }
                } else {
                    ui.components.documentFields("import/report-name", List.of(nameField), null, nameLayout, x, y, layer);
                    y += nameLayout.height() + 8;
                }
                for (int i = 0; i < problems.size(); i++) {
                    var problem = problems.get(i);
                    String id = "import/report-problem/" + i, message = messages.get(i);
                    int h = ui.components.wrap(message, textWidth).size() * 13;
                    ui.components.paragraph(id, message, new ControlTree.Bounds(x + 6, y, textWidth, h), layer, problem.severity() == Problem.Severity.ERROR ? Variant.DANGER : Variant.DEFAULT);
                    y += h;
                    for (int line = 0; line < h / 13; line++) ui.detail(id + "/" + line, problem.target() + " · " + problem.code());
                    if (!problem.target().isBlank()) {
                        int targetHeight = ui.components.wrap(problem.target(), textWidth, TextSize.SMALL).size() * 13;
                        ui.components.paragraph(id + "/target", problem.target(), new ControlTree.Bounds(x + 6, y, textWidth, targetHeight), layer, TextSize.SMALL, Variant.MUTED);
                        y += targetHeight;
                    }
                    y += 12;
                }
            }
        }
        ui.components.pixelScroll("import/body-scroll", viewport, dialogScroll, contentHeight, layer, value -> {
            dialogScroll = value;
            request();
        });
        var footer = area.footer();
        label(importPageStatus(), footer.x() + 6, footer.y() + 7, Math.max(0, footer.width() - 12));
        int bw = Math.min(150, Math.max(0, (footer.width() - 19) / 2)), primary = footer.x() + footer.width() - 6 - bw, secondary = primary - bw - 7, buttons = footer.y() + footer.height() - 26;
        if (step == 0) {
            action("import/cancel", c("cancel"), secondary, buttons, bw, this::cancelOverlay);
            action("import/inspect", c("inspect_import"), primary, buttons, bw, this::inspectImport, selected != null && !model.busy(), selected == null ? "missing_importer" : "publishing");
        } else if (importTaskFinished) {
            action("import/close", c("back"), secondary, buttons, bw, this::cancelOverlay);
            action("import/open-document", c("import_open_document"), primary, buttons, bw, () -> {
                UUID document = importedDocument;
                cancelImportTask();
                hideOverlay();
                openSceneDocument(document);
            }, importedDocument != null && listProjection.documents().containsKey(importedDocument), "missing_target");
            ui.style("import/open-document", WorkbenchSurface.Style.PRIMARY);
        } else {
            action("import/back-source", c(inspectingImport ? "cancel_task" : "import_back_source"), secondary, buttons, bw, () -> {
                cancelImportTask();
                show(Page.IMPORT);
            }, !inspectingImport || model.canCancel(), "publishing");
            action("import/accept", c("accept_import"), primary, buttons, bw, this::acceptDocumentImport, model.importReady() && !model.busy() && !form.getOrDefault("import_name", "").isBlank(), model.busy() ? "publishing" : model.importReady() ? "invalid_name" : "import_preflight_required");
        }
    }

    private void resourceList() {
        var view = model.resources();
        if (view.isEmpty()) {
            paragraph(c("protected_status"), dx + 14, dy + 42, dw - 28, 4);
            return;
        }
        var intro = new StudioComponents.SectionIntro(c(view.get().dirty() ? "resource_dirty" : "resource_saved").getString(), c("resource_reopen_hint").getString(), "");
        int introHeight = ui.components.introHeight(intro, dw - 32);
        ui.components.sectionIntro("resources/summary", intro, new ControlTree.Bounds(dx + 8, dy + 40, dw - 32, introHeight), layer);
        int y = dy + 48 + introHeight;
        for (var resource : view.get().content().resources().values().stream().sorted(Comparator.comparing(net.ptcrys.fpsmatch.editor.core.resources.LogicalResource::name).thenComparing(net.ptcrys.fpsmatch.editor.core.resources.LogicalResource::id)).toList()) {
            ui.components.sectionIntro("resources/entry/" + resource.id(), new StudioComponents.SectionIntro(component(model.resourceLabel(resource.id())).getString(), resource.type().toString(), ""), new ControlTree.Bounds(dx + 8, y, dw - 32, 44), layer);
            y += 48;
        }
    }

    private void resourceImportForm() {
        var formats = new ArrayList<>(model.resourceImporters().keySet());
        Collections.sort(formats);
        if (resourceImporter == null && !formats.isEmpty()) resourceImporter = formats.get(0);
        if (resourceImporter == null) {
            paragraph(c("missing_importer"), dx + 14, dy + 42, dw - 44, 4);
            return;
        }
        var selected = model.resourceImporters().get(resourceImporter);
        action("resource-import/format", component(selected.label()), dx + 14, dy + 40, dw - 44, () -> resourceImporter = formats.get((formats.indexOf(resourceImporter) + 1) % formats.size()));
        int y = dy + 76;
        for (var source : selected.inputs()) {
            String key = "resource_import_" + resourceImporter + "/" + source.id();
            label(component(source.label()), dx + 14, y, dw - 44);
            ui.edit(key, component(source.label()), form.getOrDefault(key, ""), dx + 14, y + 17, dw - 44, layer, true, value -> form.put(key, value));
            y += 52;
        }
        paragraph(c("resource_input_hint"), dx + 14, y + 8, dw - 44, 5);
    }

    private void inspectResourceImport() {
        try {
            var selected = model.resourceImporters().get(resourceImporter);
            Map<String, Path> paths = new HashMap<>();
            for (var source : selected.inputs()) {
                String raw = form.getOrDefault("resource_import_" + resourceImporter + "/" + source.id(), "");
                if (!raw.isBlank()) paths.put(source.id(), Path.of(raw));
            }
            model.inspectResourceImport(resourceImporter, paths);
            resourceTaskSource = Component.literal(model.projectName()).append(" · ").append(component(model.resourceImporters().get(resourceImporter).label()));
            inspectingResource = true;
            resourceTaskKnown = true;
            resourceTaskFinished = false;
            resourceTaskSummary = List.of();
            resourceTaskStatus = c("resource_report");
            show(Page.RESOURCE_REPORT);
        } catch (RuntimeException failure) {
            model.reportResourceImportFailure(failure);
            resourceTaskKnown = true;
            resourceTaskFinished = false;
            resourceTaskStatus = component(model.status());
        }
    }

    private void resourceImportReport() {
        paragraph(c("resource_preflight_hint"), dx + 14, dy + 40, dw - 44, 5);
        int y = dy + 110;
        for (var line : model.resourceImportSummary()) {
            paragraph(component(line), dx + 14, y, dw - 44, 3);
            y += 44;
        }
    }

    private void closeForm() {
        paragraph(c(model.unsaved() ? "unsaved_close" : "close_confirm"), dx + 14, dy + 42, dw - 28, 3);
        paragraph(c("unknown_close"), dx + 14, dy + 92, dw - 28, 3);
    }

    private void problemDialog() {
        int y = dy + 42;
        var failure = model.failureDiagnostic();
        if (failure.isPresent()) {
            var diagnostic = failure.orElseThrow();
            var intro = new StudioComponents.SectionIntro(c("studio_failure_event", diagnostic.eventId()).getString(), component(diagnostic.reason()).getString() + "\n" + c("studio_failure_summary", diagnostic.operation(), diagnostic.exceptionType()).getString(), "");
            int h = ui.components.introHeight(intro, dw - 16);
            ui.components.sectionIntro("problems/failure", intro, new ControlTree.Bounds(dx + 8, y, dw - 16, h), layer);
            y += h + 8;
        }
        var problems = failure.isEmpty() ? model.problems() : model.problems().stream().filter(problem -> !Set.of(EditorId.parse("editor:workbench/operation_failed"), EditorId.parse("editor:workbench/action_failed")).contains(problem.code())).filter(problem -> !Set.of("resource_inspecting", "resource_staging").contains(failure.orElseThrow().operation()) || !problem.message().equals(failure.orElseThrow().reason())).toList();
        if (!problems.isEmpty() || failure.isEmpty()) showProblems(dx + 14, y, dw - 28, Math.max(1, (dy + dh - 82 - y) / 30), problems);
        if (problems.size() > Math.max(1, (dy + dh - 82 - y) / 30)) scroll("problems", dx + 14, dy + dh - 74, dw - 28, () -> problemScroll -= 4, () -> problemScroll += 4);
    }

    private void showProblems(int x, int y, int w, int rows) {
        showProblems(x, y, w, rows, model.problems());
    }

    private void showProblems(int x, int y, int w, int rows, List<Problem> problems) {
        if (problems.isEmpty()) {
            paragraph(component(model.status()), x, y, w, 4);
            return;
        }
        problemScroll = Math.max(0, Math.min(problemScroll, Math.max(0, problems.size() - rows)));
        for (int i = problemScroll; i < Math.min(problems.size(), problemScroll + rows); i++) {
            var problem = problems.get(i);
            label(component(problem.message()), x, y + (i - problemScroll) * 30, w);
            String id = "problem/location/" + layer + "/" + x + "/" + y + "/" + i;
            var locations = fieldErrors.keySet().stream().filter(address -> address.property().equals(problem.target()) || address.node().equals(problem.target())).map(this::fieldLocation).filter(value -> !value.isBlank()).distinct().toList();
            String location = locations.isEmpty() ? (model.hasProject() ? model.projectName() : "") : String.join(" | ", locations);
            if (!location.isBlank()) {
                ui.components.text(id, location, new ControlTree.Bounds(x, y + (i - problemScroll) * 30 + 13, w, 13), layer, TextSize.SMALL, Variant.MUTED);
                ui.detail(id, location + "\n" + problem.target() + " · " + problem.code());
            }
        }
    }

    private void documentsDialog() {
        int rows = Math.max(1, (dh - 118) / 26);
        var ids = new ArrayList<>(listProjection.documents().keySet());
        documentListScroll = Math.max(0, Math.min(documentListScroll, Math.max(0, ids.size() - rows)));
        for (int i = documentListScroll; i < Math.min(ids.size(), documentListScroll + rows); i++) {
            UUID id = ids.get(i);
            var doc = listProjection.documents().get(id);
            tab("document-list/" + id, Component.literal(doc.displayName()), dx + 14, dy + 40 + (i - documentListScroll) * 26, dw - 28, model.activeDocument().filter(id::equals).isPresent(), () -> guardCreation(() -> {
                openSceneDocument(id);
                hideOverlay();
            }));
        }
        if (ids.isEmpty()) label(c("no_document"), dx + 14, dy + 44, dw - 28);
        scroll("document-list", dx + 14, dy + dh - 74, dw - 28, () -> documentListScroll -= rows, () -> documentListScroll += rows);
    }

    private void manageWorkspaces() {
        workspaceEdits.clear();
        for (var workspace : workspaces.workspaces()) workspaceEdits.add(new WorkspaceEdit(workspace.id(), null, workspace.templateId(), workspace.name(), workspace.visible()));
        selectedWorkspaceEdit = 0;
        show(Page.WORKSPACES);
    }

    private void workspaceDialog() {
        if (workspaceEdits.isEmpty()) {
            label(c("no_workspace"), dx + 14, dy + 44, dw - 28);
            return;
        }
        selectedWorkspaceEdit = Math.min(selectedWorkspaceEdit, workspaceEdits.size() - 1);
        WorkspaceEdit selected = workspaceEdits.get(selectedWorkspaceEdit);
        int listWidth = Math.max(80, (dw - 42) / 2), rows = Math.max(1, (dh - 132) / 24);
        int start = Math.max(0, selectedWorkspaceEdit - rows + 1);
        for (int i = start; i < Math.min(workspaceEdits.size(), start + rows); i++) {
            int index = i;
            var item = workspaceEdits.get(i);
            tab("workspace-edit/" + item.draftId, Component.literal(item.name).append(item.visible ? Component.empty() : c("hidden_mark")), dx + 14, dy + 42 + (i - start) * 24, listWidth, i == selectedWorkspaceEdit, () -> selectedWorkspaceEdit = index);
        }
        int x = dx + 28 + listWidth, w = dw - listWidth - 42;
        label(c("workspace_name"), x, dy + 42, w);
        ui.edit("workspace-rename/" + selected.draftId, c("workspace_name"), selected.name, x, dy + 56, w, layer, true, value -> selected.name = value);
        action("workspace/up", c("move_up"), x, dy + 88, w, () -> moveWorkspaceEdit(-1), selectedWorkspaceEdit > 0, "workspace_first");
        action("workspace/down", c("move_down"), x, dy + 114, w, () -> moveWorkspaceEdit(1), selectedWorkspaceEdit < workspaceEdits.size() - 1, "workspace_last");
        action("workspace/duplicate", c("workspace_duplicate"), x, dy + 140, w, () -> workspaceEdits.add(new WorkspaceEdit(null, selected.existing == null ? selected.copyOf : selected.existing, selected.template, selected.name + " (2)", selected.visible)), workspaceEdits.size() < WorkspaceManager.MAX_WORKSPACES, "workspace_limit");
        action("workspace/visible", c(selected.visible ? "workspace_hide" : "workspace_show"), x, dy + 166, w, () -> selected.visible = !selected.visible, !selected.visible || workspaceEdits.stream().filter(e -> e.visible).count() > 1, "workspace_keep_one");
        action("workspace/activate", c("workspace_activate"), x, dy + 192, w, () -> guardCreation(() -> {
            applyWorkspaceEdits();
            workspaces.activate(selected.existing, false);
        }), selected.visible, "workspace_hidden");
        scroll("workspace-edit-list", dx + 14, dy + 196, listWidth, () -> selectedWorkspaceEdit = Math.max(0, selectedWorkspaceEdit - rows), () -> selectedWorkspaceEdit = Math.min(workspaceEdits.size() - 1, selectedWorkspaceEdit + rows));
    }

    private void moveWorkspaceEdit(int delta) {
        int next = selectedWorkspaceEdit + delta;
        Collections.swap(workspaceEdits, selectedWorkspaceEdit, next);
        selectedWorkspaceEdit = next;
    }

    private void applyWorkspaceEdits() {
        if (workspaceEdits.stream().noneMatch(e -> e.visible) || workspaceEdits.stream().anyMatch(e -> e.name.isBlank() || e.name.length() > 256)) throw new IllegalArgumentException(c("workspace_invalid").getString());
        for (var entry : workspaceEdits) if (entry.existing == null) entry.existing = (entry.copyOf == null ? workspaces.create(entry.template, entry.name) : workspaces.duplicate(entry.copyOf, entry.name)).id();
        for (int i = 0; i < workspaceEdits.size(); i++) {
            var entry = workspaceEdits.get(i);
            workspaces.rename(entry.existing, entry.name);
            workspaces.setVisible(entry.existing, entry.visible);
            workspaces.move(entry.existing, i);
        }
        if (workspaces.activeWorkspace().isEmpty()) workspaceEdits.stream().filter(e -> e.visible).findFirst().ifPresent(e -> workspaces.activate(e.existing, false));
        ui.releaseText("workspace-rename/");
        workspaceEdits.clear();
        hideOverlay();
    }

    private void workspaceNew() {
        int y = dy + 42;
        for (var template : templates.values()) {
            action("workspace-template/" + template.id(), component(template.label()), dx + 14, y, dw - 28, () -> guardCreation(() -> {
                var created = workspaces.create(template.id(), component(template.label()).getString());
                workspaces.activate(created.id(), false);
                hideOverlay();
            }));
            y += 28;
        }
        if (templates.isEmpty()) label(c("no_workspace"), dx + 14, y, dw - 28);
    }

    private void input(String id, String key, String initial, int y) {
        label(c(key), dx + 14, y, dw - 28);
        ui.edit("form/" + id, c(key), form.getOrDefault(id, initial), dx + 14, y + 16, dw - 28, layer, true, value -> form.put(id, value));
    }

    private void label(Component text, int x, int y, int w) {
        ui.label(text, x, y, w, layer);
    }

    private void paragraph(Component text, int x, int y, int w, int maxLines) {
        var lines = font.split(text, Math.max(20, w));
        for (int i = 0; i < Math.min(lines.size(), maxLines); i++) {
            StringBuilder value = new StringBuilder();
            lines.get(i).accept((index, style, code) -> {
                value.appendCodePoint(code);
                return true;
            });
            label(i == maxLines - 1 && lines.size() > maxLines ? text : Component.literal(value.toString()), x, y + i * 13, w);
        }
    }

    private void action(String id, Component caption, int x, int y, int w, Runnable action) {
        action(id, caption, x, y, w, action, true, "");
    }

    private void action(String id, Component caption, int x, int y, int w, Runnable action, boolean enabled, String reason) {
        ui.button(id, caption, x, y, w, ui.theme.row(), layer, enabled, false, Role.BUTTON, enabled || reason.isEmpty() ? "" : c(reason).getString(), () -> {
            Page before = page;
            run(action);
            if (menuPage() && page == before && !id.startsWith("dialog/scroll-") && !id.startsWith("add/")) hideOverlay();
        });
        if (id.startsWith("object/create/") || id.startsWith("resources/import/") || Set.of("add/configure", "world/select-center", "action/save", "project/submit", "document/create", "object/create", "reference/apply", "import/accept", "import/inspect", "resource-import/accept", "resource-import/inspect", "resources/import", "workspace/apply", "close/save", "world/verify", "world/register-confirm", "install/prepare", "install/confirm", "test/start", "test/from-install", "test/from-record", "test/enter").contains(id)) ui.style(id, WorkbenchSurface.Style.PRIMARY);
        else if (id.equals("test/stop")) ui.style(id, WorkbenchSurface.Style.DANGER);
        else if (id.equals("close/discard") && model.unsaved()) ui.style(id, WorkbenchSurface.Style.DANGER);
        else if (id.equals("project/cancel")) ui.style(id, WorkbenchSurface.Style.SECONDARY);
    }

    private void tab(String id, Component caption, int x, int y, int w, boolean selected, Runnable action) {
        ui.button(id, caption, x, y, w, ui.theme.row(), layer, true, selected, Role.TAB, "", () -> run(action));
    }

    private void scroll(String id, int x, int y, int w, Runnable previous, Runnable next) {
        action(id + "/previous", c("previous"), x, y, w / 2 - 2, previous);
        action(id + "/next", c("next"), x + w / 2 + 2, y, w / 2 - 2, next);
    }

    /** Identity, role, value, bounds and state from the exact drawing/input frame. */
    public List<ControlTree.Node> semanticTree() {
        return ui == null || uiBuildFailed ? List.of() : ui.tree.snapshot();
    }

    private void cancelPanelDocking() {
        if (panelDocking.cancel()) request();
    }

    private void reconcilePanelDocking() {
        var before = panelDocking.target();
        boolean available = page == Page.EDITOR && drawer < 0 && widePanels() && minecraft.isWindowActive();
        if (panelDocking.reconcile(available ? workspaces.activeWorkspace().orElse(null) : null, available ? dockSources : List.of(), available ? dockRegions : List.of())) {
            ui.cancelPointer();
            request();
        } else if (!before.equals(panelDocking.target())) request();
    }

    private void moveDockedPanel(UUID workspaceId, UUID panelId, String group) {
        var workspace = activeWorkspace().filter(value -> value.id().equals(workspaceId)).orElse(null);
        if (workspace == null || StudioDocking.Zone.fromKey(group).isEmpty() || workspace.panels().stream().noneMatch(panel -> panel.id().equals(panelId) && !panel.type().equals(WorkspaceTemplates.VIEWPORT))) return;
        persistPanelViews();
        workspaces.movePanel(workspaceId, panelId, group);
        for (var panel : activeWorkspace().orElseThrow().panels()) if (Boolean.parseBoolean(panel.viewState().getOrDefault("studio.maximized", "false"))) panelState(workspaceId, panel, "studio.maximized", false);
        focusPanel(panelId);
        focusBeforeOverlay = (widePanels() ? "dock-tab/" : "panel/menu/") + panelId;
        restoreFocus = true;
        persistPanelViews();
        request();
    }

    private void cancelDocumentTabDrag() {
        boolean changed = documentTabs.dragging() || documentDropTarget != null;
        documentTabs.cancelDrag();
        documentDropTarget = null;
        if (changed) request();
    }

    private Optional<StudioDocumentTabs.Key> documentTabAt(double x, double y, boolean includeClose) {
        if (page != Page.EDITOR || drawer >= 0 || ui.tree.topLayer() != 0) return Optional.empty();
        var hit = ui.tree.hit(x, y);
        if (hit.isPresent()) {
            var node = hit.orElseThrow();
            var key = documentTabControls.get(node.id());
            if (key != null) return node.role() == Role.TAB || includeClose ? Optional.of(key) : Optional.empty();
            return Optional.empty();
        }
        // Padding and the dirty marker belong to the same compound tab; use its published clip.
        return ui.tree.snapshot().stream().filter(node -> node.role() == Role.PANEL && documentTabControls.containsKey(node.id()) && node.visibleBounds().contains(x, y) && ui.tree.pointerExposed(node.id(), x, y)).map(node -> documentTabControls.get(node.id())).findFirst();
    }

    private ControlTree.Bounds nativeCreationCancelBounds() {
        return new ControlTree.Bounds(width - 105, 5, 96, 25);
    }

    @Override
    public boolean mouseClicked(double x, double y, int button) {
        x /= DENSITY;
        y /= DENSITY;
        if (uiBuildFailed) {
            if (button == 0 && ui.recoveryAction(width, height).contains(x, y)) retryUi();
            return true;
        }
        if (nativeCreationVisible()) {
            if (button == 0 && worldSession.canCancel() && nativeCreationCancelBounds().contains(x, y)) worldSession.requestCancel();
            else worldSession.click(x, y, button);
            return true;
        }
        if (ui.refreshResources()) {
            cancelWorldInput();
            ui.reflow();
            request();
        }
        if (rebuildRequested || observedVersion != model.version()) rebuild();
        if (uiBuildFailed) return true;
        if (layouts.loading()) {
            if (button == 0 && ui.tree.hit(x, y).map(node -> node.id().equals("studio/layout-cancel")).orElse(false)) layouts.cancelLoad();
            return true;
        }
        if (panelDocking.pending()) return true;
        if (page == Page.EDITOR && drawer < 0 && panelDocking.press(ui.tree, x, y, button)) {
            cancelDocumentTabDrag();
            cancelWorldInput();
            worldSession.clearFocus();
            ui.cancelPointer();
            panelDocking.source().ifPresent(source -> ui.focus(source.controlId()));
            return true;
        }
        if (page == Page.EDITOR && drawer < 0 && button == 0) for (var grip : dockGrips) if (grip.bounds().contains(x, y)) {
            ui.focus(null);
            draggingDock = grip;
            cancelWorldInput();
            ui.cancelPointer();
            return true;
        }
        if (page == Page.EDITOR && drawer < 0 && viewportBounds != null && viewportBounds.contains(x, y) && worldSession.hasNative()) {
            ui.focus(null);
            worldSession.click(x, y, button);
            return true;
        }
        worldSession.clearFocus();
        if (areaPlacement.active() && button == 0 && placementPointerExposed(x, y)) {
            ui.focus(null);
            try {
                areaPlacement.commit(viewport, x, y, true);
                request();
            } catch (RuntimeException failure) {
                cancelAreaPlacement();
                model.report(failure);
                request();
            }
            return true;
        }
        if (pointPlacement.active() && button == 0 && placementPointerExposed(x, y)) {
            ui.focus(null);
            try {
                pointPlacement.commit(viewport, x, y, true);
                request();
            } catch (RuntimeException failure) {
                cancelPointPlacement();
                model.report(failure);
                request();
            }
            return true;
        }
        if (!pointPlacement.active() && !areaPlacement.active() && button == 0 && placementPointerExposed(x, y)) {
            ui.focus(null);
            var hit = pointOverlay.hit(viewport, x, y, true);
            if (hit.isPresent()) {
                var selected = hit.get();
                guardCreation(() -> {
                    if (!pointOverlay.current(viewport, selected)) return;
                    selectObjectNow(selected.document(), selected.node(), false, null);
                    revealObject(selected.document(), selected.node());
                    request();
                });
                return true;
            }
        }
        if (page == Page.EDITOR && drawer < 0 && worldEditingReady() && viewportBounds != null && viewportBounds.contains(x, y) && viewport.mouseClicked(x, y, button)) {
            ui.focus(null);
            return true;
        }
        cancelWorldInput();
        if (page == Page.EDITOR && drawer >= 0 && drawerBounds != null && !drawerBounds.contains(x, y)) {
            drawer = -1;
            request();
            return true;
        }
        if (page != Page.EDITOR && !(x >= dx && x < dx + dw && y >= dy && y < dy + dh)) {
            cancelOverlay();
            return true;
        }
        if (button == 1 && page == Page.EDITOR) {
            var hit = ui.tree.hit(x, y);
            String node = hit.map(n -> objectControls.get(n.id())).orElse(null);
            if (node != null) {
                UUID document = objectDocuments.get(hit.orElseThrow().id());
                int menuX = (int) x, menuTop = (int) y;
                guardCreation(() -> {
                    model.selectNode(document, node);
                    openObjectMenu(document, node, menuX, menuTop);
                });
                return true;
            }
        }
        final double pointerX = x;
        var documentPress = button == 0 ? documentTabAt(x, y, false) : Optional.<StudioDocumentTabs.Key>empty();
        documentPress.ifPresent(key -> {
            documentDropTarget = null;
            documentTabs.beginDrag(key, pointerX);
        });
        if (button == 0 && page == Page.EDITOR) ui.tree.hit(x, y).filter(n -> n.role() == Role.TAB).flatMap(n -> workspaces.workspaces().stream().filter(w -> n.id().equals("workspace/" + w.id())).findFirst()).ifPresent(w -> {
            dragWorkspace = w.id();
            dragX = pointerX;
        });
        ui.click(x, y, button, hasShiftDown());
        documentPress.ifPresent(key -> ui.focus(key.controlId()));
        return true;
    }

    @Override
    public boolean mouseReleased(double x, double y, int button) {
        if (uiBuildFailed) return true;
        if (layouts.loading()) return true;
        x /= DENSITY;
        y /= DENSITY;
        if (nativeCreationVisible()) {
            worldSession.release(x, y, button);
            return true;
        }
        if (panelDocking.pending()) {
            if (button != 0) return true;
            if (rebuildRequested || observedVersion != model.version()) rebuild();
            if (uiBuildFailed) return true;
            reconcilePanelDocking();
            var workspace = workspaces.activeWorkspace().orElse(null);
            var completion = panelDocking.release(x, y, button);
            ui.cancelPointer();
            completion.ifPresent(result -> run(() -> {
                if (result.move()) moveDockedPanel(workspace, result.panel(), result.target().key());
                else focusPanel(result.panel());
            }));
            request();
            return true;
        }
        if (documentTabs.dragging()) {
            if (button != 0) return true;
            documentTabs.drag(x);
            var target = documentTabAt(x, y, documentTabs.movedDuringDrag()).orElse(null);
            var click = documentTabs.endDrag(target, x);
            documentDropTarget = null;
            ui.cancelPointer();
            click.ifPresent(key -> run(() -> guardCreation(() -> activateDocumentView(key))));
            request();
            return true;
        }
        if (draggingDock != null) {
            resizeDock(draggingDock, x, y);
            draggingDock = null;
            return true;
        }
        if (dragWorkspace != null) {
            UUID source = dragWorkspace;
            dragWorkspace = null;
            if (Math.abs(x - dragX) > 5) {
                ui.cancelPointer();
                ui.tree.hit(x, y).filter(n -> n.role() == Role.TAB).flatMap(n -> workspaces.workspaces().stream().filter(w -> n.id().equals("workspace/" + w.id())).findFirst()).ifPresent(w -> {
                    var all = workspaces.workspaces();
                    for (int i = 0; i < all.size(); i++) if (all.get(i).id().equals(w.id())) workspaces.move(source, i);
                });
                request();
                return true;
            }
        }
        if (page == Page.EDITOR && drawer < 0 && worldSession.release(x, y, button)) return true;
        if (page == Page.EDITOR && drawer < 0 && worldEditingReady() && viewport.mouseReleased(x, y, button)) return true;
        ui.release(x, y, button);
        return true;
    }

    @Override
    public boolean mouseDragged(double x, double y, int button, double deltaX, double deltaY) {
        if (uiBuildFailed) return true;
        if (layouts.loading()) return true;
        x /= DENSITY;
        y /= DENSITY;
        if (nativeCreationVisible()) {
            worldSession.drag(x, y, button, deltaX / DENSITY, deltaY / DENSITY);
            return true;
        }
        if (panelDocking.pending()) {
            if (button == 0) {
                if (rebuildRequested || observedVersion != model.version()) rebuild();
                if (uiBuildFailed) return true;
                reconcilePanelDocking();
                if (panelDocking.drag(x, y)) request();
            }
            return true;
        }
        if (documentTabs.dragging()) {
            if (button != 0) return true;
            if (documentBarBounds == null || !documentBarBounds.contains(x, y)) {
                cancelDocumentTabDrag();
                ui.cancelPointer();
                request();
                return true;
            }
            boolean moved = documentTabs.movedDuringDrag();
            documentTabs.drag(x);
            var target = documentTabAt(x, y, true).orElse(null);
            if (moved != documentTabs.movedDuringDrag() || !Objects.equals(target, documentDropTarget)) {
                documentDropTarget = target;
                request();
            }
            return true;
        }
        if (draggingDock != null) {
            resizeDock(draggingDock, x, y);
            return true;
        }
        if (page == Page.EDITOR && drawer < 0 && worldSession.drag(x, y, button, deltaX / DENSITY, deltaY / DENSITY)) return true;
        if (page == Page.EDITOR && drawer < 0 && worldEditingReady() && viewport.mouseDragged(x, y, button, deltaX / DENSITY, deltaY / DENSITY)) return true;
        return ui.drag(x, y) || dragWorkspace != null;
    }

    @Override
    public boolean mouseScrolled(double x, double y, double delta) {
        if (uiBuildFailed) return true;
        if (layouts.loading()) return true;
        x /= DENSITY;
        y /= DENSITY;
        if (nativeCreationVisible()) {
            worldSession.scroll(x, y, delta);
            return true;
        }
        if (panelDocking.pending()) return true;
        if (ui.scroll(x, y, delta)) return true;
        if (page == Page.DOCUMENT_CLOSE) return true;
        if (page == Page.EDITOR && drawer < 0 && worldSession.scroll(x, y, delta)) return true;
        if (page == Page.EDITOR && drawer < 0 && worldSession.selecting() && viewportBounds != null && viewportBounds.contains(x, y)) {
            worldListScroll += delta > 0 ? -1 : 1;
            request();
            return true;
        }
        if (page == Page.EDITOR && drawer < 0 && worldEditingReady() && viewportBounds != null && viewportBounds.contains(x, y) && viewport.mouseScrolled(x, y, delta)) return true;
        int move = delta > 0 ? -1 : 1;
        if (page == Page.ADD_MENU) {
            if (dw >= 560 && x < dx + 130) addCategoryScroll += move;
            else addScroll += move;
        } else if (page == Page.PROBLEMS) problemScroll += move;
        else if (page == Page.DOCUMENTS) documentListScroll += move;
        else if (page == Page.FIELD_CHOICES) choiceScroll += move;
        else if (page == Page.REFERENCES) referenceScroll += move;
        else if (page != Page.EDITOR) dialogScroll += move * 22;
        else if (page == Page.EDITOR) {
            var hit = ui.tree.hit(x, y);
            if (documentBarBounds != null && documentBarBounds.contains(x, y)) documentScroll += move;
            else for (var entry : panelBounds.entrySet()) if (entry.getValue().contains(x, y)) {
                UUID panel = entry.getKey();
                var type = panelTypes.get(panel);
                if (type.equals(WorkspaceTemplates.TASKS)) taskScroll.compute(panel, (id, value) -> Math.max(0, (value == null ? 0 : value) + move));
                else if (type.equals(WorkspaceTemplates.PROBLEMS)) problemScroll = Math.max(0, problemScroll + move);
                else if (type.equals(WorkspaceTemplates.VIEWPORT) && resourceDocument) {
                    var scrolls = x < entry.getValue().x() + entry.getValue().width() * 2 / 5 ? resourceScroll : resourceDetailScroll;
                    scrolls.compute(centralResources, (id, value) -> Math.max(0, (value == null ? 0 : value) + move));
                } else if (type.equals(WorkspaceTemplates.RESOURCES)) {
                    var scrolls = x < entry.getValue().x() + entry.getValue().width() * 2 / 5 ? resourceScroll : resourceDetailScroll;
                    scrolls.compute(panel, (id, value) -> Math.max(0, (value == null ? 0 : value) + move));
                }
                break;
            }
        }
        request();
        return true;
    }

    @Override
    public boolean keyReleased(int key, int scan, int modifiers) {
        if (uiBuildFailed) return true;
        return nativeCreationVisible() || viewport != null && viewport.keyReleased(key, scan, modifiers);
    }

    @Override
    public boolean charTyped(char character, int modifiers) {
        if (uiBuildFailed) return true;
        if (nativeCreationVisible()) {
            worldSession.character(character, modifiers);
            return true;
        }
        if (layouts.loading()) return true;
        if (page == Page.EDITOR && drawer < 0 && worldSession.character(character, modifiers)) return true;
        return ui.character(character);
    }

    @Override
    public boolean keyPressed(int key, int scan, int modifiers) {
        if (uiBuildFailed) {
            if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) retryUi();
            else if (key == GLFW.GLFW_KEY_ESCAPE) onClose();
            return true;
        }
        if (nativeCreationVisible()) {
            if (!worldSession.key(key, scan, modifiers) && key == GLFW.GLFW_KEY_ESCAPE) worldSession.requestCancel();
            return true;
        }
        if (layouts.loading()) {
            if (key == GLFW.GLFW_KEY_ESCAPE) layouts.cancelLoad();
            return true;
        }
        ui.keyboardNavigation();
        if (rebuildRequested || observedVersion != model.version()) rebuild();
        if (uiBuildFailed) return true;
        if (layouts.loading()) {
            if (key == GLFW.GLFW_KEY_ESCAPE) layouts.cancelLoad();
            return true;
        }
        if (key == GLFW.GLFW_KEY_ESCAPE && panelDocking.pending()) {
            onClose();
            return true;
        }
        if (panelDocking.pending()) {
            cancelPanelDocking();
            ui.cancelPointer();
        }
        if (key == GLFW.GLFW_KEY_ESCAPE && documentTabs.dragging()) {
            onClose();
            return true;
        }
        boolean control = (modifiers & GLFW.GLFW_MOD_CONTROL) != 0 || hasControlDown(), shift = (modifiers & GLFW.GLFW_MOD_SHIFT) != 0 || hasShiftDown();
        if (page == Page.EDITOR && control && key == GLFW.GLFW_KEY_S) {
            cancelPointPlacement();
            cancelAreaPlacement();
            cancelDocumentTabDrag();
            ui.cancelPointer();
            run(() -> {
                if (shift) {
                    ui.focus(null);
                    if (model.hasProject()) model.saveAll();
                } else saveCurrentDocument();
            });
            return true;
        }
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            onClose();
            return true;
        }
        if (page == Page.EDITOR && drawer < 0 && worldSession.key(key, scan, modifiers)) return true;
        if (page != Page.EDITOR && (key == GLFW.GLFW_KEY_PAGE_UP || key == GLFW.GLFW_KEY_PAGE_DOWN)) {
            if (page == Page.ADD_MENU) addScroll += key == GLFW.GLFW_KEY_PAGE_UP ? -5 : 5;
            else if (page == Page.FIELD_CHOICES) choiceScroll += (key == GLFW.GLFW_KEY_PAGE_UP ? -1 : 1) * choiceRows();
            else if (page == Page.REFERENCES) referenceScroll += (key == GLFW.GLFW_KEY_PAGE_UP ? -1 : 1) * referenceRows();
            else dialogScroll += key == GLFW.GLFW_KEY_PAGE_UP ? -66 : 66;
            request();
            return true;
        }
        if (menuPage() && (key == GLFW.GLFW_KEY_DOWN || key == GLFW.GLFW_KEY_UP)) {
            ui.cycleFocus(key == GLFW.GLFW_KEY_UP);
            return true;
        }
        if (control && shift && key == GLFW.GLFW_KEY_P) {
            openCommands();
            return true;
        }
        var focused = ui.tree.focused().orElse(null);
        var logical = ui.logicalFocus();
        var objectContext = logical == null || !logical.virtual() ? null : objectLists.get(logical.rangeId());
        if (page == Page.EDITOR && objectContext != null) {
            String node = logical.rowKey();
            UUID doc = objectContext.document(), panel = objectContext.panel();
            String state = panel + "/" + doc;
            boolean actual = focused != null && node.equals(objectControls.get(focused.id()));
            if (key == GLFW.GLFW_KEY_DELETE && actual) {
                run(() -> guardCreation(() -> {
                    model.selectNode(doc, node);
                    openRemoval(doc, node);
                }));
                return true;
            }
            if (key == GLFW.GLFW_KEY_LEFT || key == GLFW.GLFW_KEY_RIGHT) {
                toggleObjectExpansion(state, node, key == GLFW.GLFW_KEY_RIGHT);
                revealObjects.put(state, node);
                request();
                return true;
            }
            if (actual && (key == GLFW.GLFW_KEY_MENU || (shift && key == GLFW.GLFW_KEY_F10))) {
                guardCreation(() -> {
                    model.selectNode(doc, node);
                    openObjectMenuAt(doc, node, focused.id());
                });
                return true;
            }
            if (key == GLFW.GLFW_KEY_DOWN || key == GLFW.GLFW_KEY_UP) {
                var source = objectContext.source();
                int index = source.indexOf(node);
                if (index >= 0) {
                    String next = source.keyAt(Math.max(0, Math.min(source.size() - 1, index + (key == GLFW.GLFW_KEY_DOWN ? 1 : -1))));
                    run(() -> selectTreeObject(logical.rangeId(), next));
                }
                return true;
            }
        }
        if ((focused == null || focused.role() != Role.TEXT_FIELD) && page == Page.EDITOR && drawer < 0 && worldEditingReady() && viewport.keyPressed(key, scan, modifiers)) return true;
        if (ui.key(key, control, shift)) {
            cancelWorldInput();
            return true;
        }
        if (page != Page.EDITOR) return true;
        if (control && key == GLFW.GLFW_KEY_Z) run(shift ? model::redo : model::undo);
        else if (control && key == GLFW.GLFW_KEY_Y) run(model::redo);
        return true;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (worldSession.nativeRendering()) {
            worldSession.renderBackdrop(graphics, mouseX, mouseY, partialTick);
            return;
        }
        worldSession.refreshOverlay();
        if (ui.refreshResources()) {
            cancelWorldInput();
            ui.reflow();
            request();
        }
        if (rebuildRequested || observedVersion != model.version()) rebuild();
        if (uiBuildFailed) {
            graphics.pose().pushPose();
            try {
                graphics.pose().scale(DENSITY, DENSITY, 1);
                ui.renderRecovery(graphics, width, height, (int) (mouseX / DENSITY), (int) (mouseY / DENSITY));
            } finally {
                graphics.pose().popPose();
            }
            return;
        }
        Component tooltip;
        graphics.pose().pushPose();
        try {
            graphics.pose().scale(DENSITY, DENSITY, 1);
            graphics.fill(0, 0, width, height, ui.theme.background());
            for (int y : new int[] { MENU_BOTTOM, height - STATUS_HEIGHT }) graphics.fill(0, y, width, y + 1, ui.theme.border());
            if (viewportBounds != null && !nativeCreationVisible()) {
                var b = viewportBounds;
                graphics.fill(b.x(), b.y(), b.x() + b.width(), b.y() + b.height(), ui.theme.canvas());
                if (worldSession.hasNative()) worldSession.render(graphics, b, DENSITY, (int) (mouseX / DENSITY), (int) (mouseY / DENSITY), partialTick);
                else if (worldEditingReady()) viewport.render(graphics, b, DENSITY, partialTick);
            }
            renderPointPlacement(graphics, mouseX, mouseY);
            renderAreaPlacement(graphics, mouseX, mouseY);
            tooltip = ui.render(graphics, (int) (mouseX / DENSITY), (int) (mouseY / DENSITY), width, height);
            if (nativeCreationVisible() && nativeWorldBounds != null) worldSession.render(graphics, nativeWorldBounds, DENSITY, (int) (mouseX / DENSITY), (int) (mouseY / DENSITY), partialTick);
        } finally {
            graphics.pose().popPose();
        }
        if (tooltip != null) ui.renderTooltip(graphics, tooltip, mouseX, mouseY);
    }

    private void cancelProjectFlow() {
        if (projectWorld.canCancelPreparation()) {
            projectWorld.cancelPreparation();
            openingProject = false;
            request();
        } else if (projectWorld.busy()) return;
        else if (model.busy()) model.cancel();
        else cancelOverlay();
    }

    private void pickPath(String key, boolean directory, boolean parent, String focus) {
        String original = form.getOrDefault(key, "");
        Page sourcePage = page;
        long ticket = projectDraftGeneration;
        EditorId sourceImporter = importer, sourceResource = resourceImporter;
        var input = directory ? WorkbenchPathPicker.Request.folder(c("path_pick_folder").getString(), original) : WorkbenchPathPicker.Request.openFile(c("path_pick_file").getString(), original, "*");
        pickerField = key;
        pickerMessage = null;
        var started = pathPicker.request(input, result -> {
            if (ticket != projectDraftGeneration || page != sourcePage || !Objects.equals(original, form.getOrDefault(key, "")) || !Objects.equals(sourceImporter, importer) || !Objects.equals(sourceResource, resourceImporter)) return;
            pickerFocus = focus;
            if (result.status() == WorkbenchPathPicker.Status.CANCELLED) pickerMessage = c("path_pick_cancelled");
            else if (result.status() == WorkbenchPathPicker.Status.FAILED) pickerMessage = c("path_pick_failed");
            else try {
                Path selected = result.path();
                if (parent) {
                    String leaf = "new-project";
                    try {
                        var current = Path.of(original).getFileName();
                        if (current != null && !current.toString().isBlank() && !Set.of(".", "..").contains(current.toString())) leaf = current.toString();
                    } catch (RuntimeException ignored) {}
                    Path child = selected.resolve(leaf);
                    int suffix = 2;
                    while (Files.exists(child) && suffix < 10000) child = selected.resolve(leaf + "-" + suffix++);
                    if (Files.exists(child)) throw new IllegalStateException("No free child directory");
                    selected = child;
                }
                form.put(key, selected.toString());
                pickerMessage = c("path_pick_selected");
                if (key.equals("_project_world_candidate")) inspectProjectWorld(selected, selected.getFileName().toString());
                else if (key.equals("_project_world_original")) {
                    if (!localWorldRoot(selected)) projectWorldMessage = c("project_world_local_only");
                    else projectWorld.locateOriginal(selected);
                }
            } catch (RuntimeException failure) {
                pickerMessage = c("path_pick_failed");
            }
            request();
        });
        if (started != WorkbenchPathPicker.Start.STARTED) pickerMessage = c("path_pick_busy");
        request();
    }

    private StudioView.Element pathInputView(String id, String key, Component caption, boolean directory, boolean parent) {
        boolean enabled = !model.busy() && !projectWorld.busy();
        String browse = id + "/browse";
        var input = ui.view.input(new StudioView.Input(id, caption.getString(), form.getOrDefault(key, ""), true, !enabled, false, "", null, value -> {
            pathPicker.cancelPending();
            form.put(key, value);
            if (key.equals(pickerField)) pickerMessage = null;
        }, null)).style(Style.defaults().width(Length.stretch().limits(50, Integer.MAX_VALUE)));
        var control = ui.view.wrap(id + "/path-controls", Style.defaults().gap(4), List.of(input, ui.view.button(studioAction(browse, c("path_browse"), Icon.FOLDER, Variant.OUTLINED, enabled && !pathPicker.isBusy(), false, () -> pickPath(key, directory, parent, browse)))));
        Component hint = key.equals(pickerField) && pickerMessage != null ? pickerMessage : parent ? c("project_path_parent_hint") : Component.empty();
        return ui.view.field(new StudioView.Field(id, caption.getString(), control, hint.getString(), "", List.of()));
    }

    private StudioView.Element projectWorldSectionView(boolean draft) {
        var content = new ArrayList<StudioView.Element>();
        content.add(ui.view.text("project/world-title", c("project_world_title").getString(), TextSize.HEADING, Variant.DEFAULT));
        var selected = draft ? selectedProjectWorld : projectWorld.binding().map(ProjectWorldStore.Binding::world).orElse(null);
        content.add(ui.view.paragraph("project/world-summary", selected == null ? c("project_world_none_selected").getString() : selected.label() + " · " + selected.root()));
        var actions = new ArrayList<StudioView.Element>();
        if (draft) {
            actions.add(ui.view.button(studioAction("project/world-existing", c("project_world_choose_existing"), Icon.FOLDER, Variant.OUTLINED, !model.busy() && !projectWorld.busy(), false, () -> openProjectWorld(true))));
            actions.add(ui.view.button(studioAction("project/world-new", c("project_world_create_native"), Icon.PLUS, Variant.OUTLINED, !model.busy() && !projectWorld.busy() && worldSession.canSelect(), false, this::createProjectWorld)));
            content.add(ui.view.wrap("project/world-actions", Style.defaults().gap(4), actions));
            var confirm = new StudioComponents.Field("project/world-confirm", c("project_world_confirm").getString(), Boolean.toString(projectWorldAcknowledged), c(projectWorldAcknowledged ? "boolean_on" : "boolean_off").getString(), "", StudioComponents.FieldKind.TOGGLE, ui.theme.muted(), selectedProjectWorld != null && !model.busy() && !projectWorld.busy(), false, value -> {}, () -> {
                projectWorldAcknowledged = !projectWorldAcknowledged;
                request();
            }, () -> {});
            content.add(ui.view.fields("project/world-confirm-form", List.of(confirm), null));
        } else {
            actions.add(ui.view.button(studioAction("project/world-details", c("project_world_title"), Icon.MAP, Variant.OUTLINED, !projectWorld.busy(), false, () -> openProjectWorld(false))));
            actions.add(ui.view.button(studioAction("project/world-refresh", c("world_refresh"), Icon.NONE, Variant.DEFAULT, !projectWorld.busy(), false, projectWorld::refresh)));
            content.add(ui.view.wrap("project/world-actions", Style.defaults().gap(4), actions));
        }
        Component note = projectWorld.failure().isPresent() ? c("project_world_failed") : projectWorldMessage != null ? projectWorldMessage : draft ? c("project_world_scope") : component(projectWorld.status());
        content.add(ui.view.paragraph("project/world-note", note.getString(), TextSize.SMALL, projectWorld.failure().isPresent() ? Variant.DANGER : Variant.MUTED));
        return ui.view.surface("project/world-section", Kind.INLINE_CARD, Style.defaults().padding(Insets.all(8)).gap(6), content);
    }

    private StudioView.Element projectWorldView() {
        var content = new ArrayList<StudioView.Element>();
        if (projectWorld.binding().isPresent()) {
            var bound = projectWorld.binding().orElseThrow().world();
            content.add(ui.view.paragraph("project-world/summary", bound.label() + " · " + bound.root() + " · " + c("project_world_scope").getString()));
            content.add(ui.view.wrap("project-world/actions", Style.defaults().gap(6), List.of(
                    ui.view.button(studioAction("project-world/recheck", c("world_refresh"), Icon.NONE, Variant.OUTLINED, !projectWorld.busy(), false, projectWorld::refresh)),
                    ui.view.button(studioAction("project-world/locate", c("project_world_locate"), Icon.FOLDER, Variant.OUTLINED, !projectWorld.busy() && !pathPicker.isBusy() && projectWorld.state() != ProjectWorldWorkbench.State.UNKNOWN, false, () -> pickPath("_project_world_original", true, false, "project-world/locate"))))));
            if (wrongCurrentProjectWorld()) {
                content.add(ui.view.paragraph("project-world/wrong-current", c("project_world_wrong_current").getString(), TextSize.SMALL, Variant.MUTED));
                content.add(ui.view.button(studioAction("project-world/leave-enter", c("project_world_leave_enter"), Icon.NONE, Variant.OUTLINED, !worldSession.loading() && !projectWorld.busy(), false, () -> beginBoundWorldEntry(true))));
            }
            content.add(ui.view.paragraph("project-world/local-scope", c("project_world_local_only").getString(), TextSize.SMALL, Variant.MUTED));
        } else {
            content.add(ui.view.wrap("project-world/actions", Style.defaults().gap(6), List.of(
                    ui.view.button(studioAction("project-world/refresh-list", c("world_refresh"), Icon.NONE, Variant.DEFAULT, !worldSession.reading(), false, worldSession::refresh)),
                    ui.view.button(studioAction("project-world/browse", c("path_browse"), Icon.FOLDER, Variant.OUTLINED, !model.busy() && !projectWorld.busy() && !pathPicker.isBusy(), false, () -> pickPath("_project_world_candidate", true, false, "project-world/browse"))),
                    ui.view.button(studioAction("project-world/new", c("project_world_create_native"), Icon.PLUS, Variant.OUTLINED, worldSession.canSelect() && !model.busy() && !projectWorld.busy(), false, this::createProjectWorld)))));
            content.add(ui.view.input(new StudioView.Input("project-world/search", c("world_save_search").getString(), worldSession.search(), true, false, false, "", null, value -> {
                worldSession.search(value);
                request();
            }, null)));
            var candidates = worldSession.worlds().stream().filter(world -> (world.getLevelName() + " " + world.getLevelId()).toLowerCase(Locale.ROOT).contains(worldSession.search().toLowerCase(Locale.ROOT))).toList();
            if (candidates.isEmpty()) content.add(ui.view.paragraph("project-world/empty", c(worldSession.reading() ? "world_save_loading" : "world_save_empty").getString()));
            for (var world : candidates) {
                String id = "project-world/candidate/" + world.getLevelId();
                content.add(ui.view.column(id + "/item", Style.defaults().gap(4), List.of(
                        ui.view.button(studioAction(id, Component.literal(world.getLevelName()), Icon.MAP, Variant.OUTLINED, (!world.isDisabled() || ownsListedWorld(world.getLevelId())) && !model.busy() && !projectWorld.busy(), selectedProjectWorld != null && selectedProjectWorld.levelId().equals(world.getLevelId()), () -> inspectProjectWorld(minecraft.getLevelSource().getBaseDir().resolve(world.getLevelId()), world.getLevelName()))).style(Style.defaults()),
                        ui.view.paragraph(id + "/details", world.getLevelId() + " · " + world.getInfo().getString(), TextSize.SMALL, Variant.MUTED))));
            }
            if (selectedProjectWorld != null) content.add(ui.view.paragraph("project-world/selected", selectedProjectWorld.label() + " · " + selectedProjectWorld.root()));
        }
        return ui.view.column("project-world/content", Style.defaults().gap(10), content);
    }

    private void pathInput(String id, String key, Component caption, int x, int y, int w, boolean directory, boolean parent) {
        boolean enabled = !model.busy() && !projectWorld.busy();
        int browse = Math.min(72, Math.max(45, w / 3));
        label(caption, x + 6, y, Math.max(0, w - 12));
        ui.edit(id, caption, form.getOrDefault(key, ""), x + 6, y + 17, Math.max(20, w - browse - 20), layer, enabled, value -> {
            pathPicker.cancelPending();
            form.put(key, value);
            if (key.equals(pickerField)) pickerMessage = null;
        });
        String button = id + "/browse";
        action(button, c("path_browse"), x + w - browse - 6, y + 17, browse, () -> pickPath(key, directory, parent, button), enabled && !pathPicker.isBusy(), "path_pick_busy");
        ui.icon(button, Icon.FOLDER);
        Component hint = key.equals(pickerField) && pickerMessage != null ? pickerMessage : parent ? c("project_path_parent_hint") : Component.empty();
        ui.components.paragraph(id + "/path-note", hint.getString(), new ControlTree.Bounds(x + 6, y + 42, Math.max(0, w - 12), 26), layer, Variant.MUTED);
    }

    private int projectWorldTextHeight(String text, int width) {
        return Math.max(13, font.split(Component.literal(text), Math.max(20, width)).size() * 13);
    }

    private boolean wrongCurrentProjectWorld() {
        return connectedWorldRoot().filter(root -> projectWorld.binding().map(binding -> !binding.world().root().equals(root.toAbsolutePath().normalize())).orElse(false)).isPresent();
    }

    private Component projectWorldPanelMessage() {
        return projectWorld.failure().isPresent() ? c("project_world_failed") : projectWorldMessage != null ? projectWorldMessage : pickerMessage;
    }

    private Component projectWorldPanelStatus() {
        return projectWorldDraftSelection ? c(projectWorld.busy() ? "project_world_checking" : "project_world_choose_for_new") : component(projectWorld.status());
    }

    private boolean ownsListedWorld(String id) {
        Path candidate = minecraft.getLevelSource().getBaseDir().resolve(id);
        try {
            return connectedWorldRoot().isPresent() && localWorldRoot(candidate) && Files.isSameFile(candidate, connectedWorldRoot().orElseThrow());
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean nativeCreationVisible() {
        return worldSession.creatingWorld() && worldSession.hasNative();
    }

    private String nativeCreateDisabledReason() {
        return !worldSession.loading() && (minecraft.level != null || minecraft.getSingleplayerServer() != null) ? "project_world_leave_before_create" : "world_session_busy";
    }

    private Optional<Path> connectedWorldRoot() {
        var server = minecraft.getSingleplayerServer();
        return server == null ? Optional.empty() : Optional.of(server.getWorldPath(LevelResource.ROOT));
    }

    private boolean worldEditingReady() {
        return worldSession.ready() && connectedWorldRoot().filter(projectWorld::allows).isPresent();
    }

    private boolean creationRequiresWorld() {
        return projectTemplate != null && model.templateRequiresWorld(projectTemplate);
    }

    private String projectSubmitDisabledReason() {
        if (model.busy() || projectWorld.busy()) return "busy";
        if (projectWorld.state() == ProjectWorldWorkbench.State.UNKNOWN) return "project_world_unknown";
        if (form.getOrDefault("project_path", "").isBlank()) return creating ? "studio_creation_required" : "project_path_required";
        if (creating && (projectTemplate == null || form.getOrDefault("project_name", "").isBlank() || form.getOrDefault("first_document_name", "").isBlank())) return "studio_creation_required";
        if (creating && creationRequiresWorld()) {
            if (selectedProjectWorld == null) return "project_world_select_required";
            if (!projectWorldAcknowledged) return "project_world_ack_required";
        }
        return "";
    }

    private String projectWorldActionKey() {
        return projectWorld.binding().isPresent() ? "project_world_open" : projectWorld.requiresWorld() ? "project_world_title" : "world_select_save";
    }

    private boolean localWorldRoot(Path root) {
        try {
            Path real = root.toRealPath(), base = minecraft.getLevelSource().getBaseDir().toRealPath();
            return real.getParent() != null && real.getParent().equals(base);
        } catch (Exception ignored) {
            return false;
        }
    }

    private void inspectProjectWorld(Path root, String label) {
        selectedProjectWorld = null;
        projectWorldAcknowledged = false;
        projectWorldMessage = null;
        // Vanilla may use a relative game directory. Keep the original path spelling
        // for the store's reparse checks, but pass its required absolute path.
        root = root.toAbsolutePath().normalize();
        if (!localWorldRoot(root)) {
            projectWorldMessage = c("project_world_local_only");
            request();
            return;
        }
        long ticket = projectDraftGeneration;
        try {
            projectWorld.inspect(root, label, world -> {
                if (ticket != projectDraftGeneration) return;
                selectedProjectWorld = world;
                projectWorldMessage = c("project_world_selected");
                request();
            });
        } catch (RuntimeException failure) {
            projectWorldMessage = c("project_world_failed");
        }
        request();
    }

    private void openProjectWorld(boolean draft) {
        projectWorldReturn = page == Page.PROJECT_WORLD ? projectWorldReturn : page;
        projectWorldDraftSelection = draft;
        show(Page.PROJECT_WORLD);
        worldSession.refresh();
    }

    private void acceptProjectWorld() {
        if (selectedProjectWorld == null || model.busy() || projectWorld.busy()) return;
        if (projectWorldDraftSelection) {
            projectWorldAcknowledged = false;
            show(projectWorldReturn);
        } else if (projectWorld.state() == ProjectWorldWorkbench.State.UNBOUND) {
            projectWorldMessage = null;
            pickerMessage = null;
            try {
                projectWorld.bind(selectedProjectWorld);
            } catch (RuntimeException failure) {
                projectWorldMessage = c("project_world_failed");
            }
            request();
        }
    }

    private void createProjectWorld() {
        if (model.busy() || projectWorld.busy() || !worldSession.canSelect()) return;
        // Keep the draft selection on cancellation; inspecting a created world replaces it.
        long ticket = ++projectDraftGeneration;
        Page origin = page;
        projectWorldMessage = null;
        boolean started = worldSession.createWorld(form.getOrDefault("project_name", ""), created -> {
            if (ticket != projectDraftGeneration || page != origin) return;
            inspectProjectWorld(created.root(), created.displayName());
        }, () -> {
            if (ticket != projectDraftGeneration || page != origin) return;
            projectWorldMessage = c(worldSession.failure().isPresent() ? "project_world_failed" : "project_world_native_cancelled");
            request();
        });
        if (!started) projectWorldMessage = c("world_session_busy");
        request();
    }

    private void openAuthoringWorld() {
        if (!model.hasProject()) {
            show(Page.PROJECT);
            return;
        }
        if (!projectWorld.requiresWorld() && projectWorld.binding().isEmpty()) {
            hideOverlay();
            worldSession.openSelection();
            request();
            return;
        }
        if (projectWorld.state() != ProjectWorldWorkbench.State.READY || projectWorld.binding().isEmpty()) {
            openProjectWorld(false);
            return;
        }
        if (connectedWorldRoot().filter(root -> projectWorld.binding().map(binding -> !binding.world().root().equals(root.toAbsolutePath().normalize())).orElse(false)).isPresent()) {
            openProjectWorld(false);
            projectWorldMessage = c("project_world_wrong_current");
            request();
            return;
        }
        beginBoundWorldEntry(false);
    }

    private void beginBoundWorldEntry(boolean leaveCurrent) {
        if (projectWorld.binding().isEmpty() || projectWorld.busy()) return;
        if (page != Page.PROJECT_WORLD) openProjectWorld(false);
        boundEntryBinding = projectWorld.binding().orElseThrow();
        boundEntryGeneration = projectDraftGeneration;
        pendingBoundWorld = boundEntryBinding.world().levelId();
        projectWorldMessage = null;
        if (leaveCurrent && connectedWorldRoot().isPresent()) {
            boundEntryPhase = 1;
            worldSession.leave();
        } else {
            boundEntryPhase = 2;
            projectWorld.refresh();
        }
        request();
    }

    private void pollBoundWorldEntry() {
        if (boundEntryPhase == 0) return;
        if (boundEntryGeneration != projectDraftGeneration || !model.hasProject()) {
            boundEntryPhase = 0;
            pendingBoundWorld = null;
            return;
        }
        if (boundEntryPhase == 1) {
            if (worldSession.loading()) return;
            if (!worldSession.canSelect()) {
                boundEntryPhase = 0;
                projectWorldMessage = c("project_world_failed");
                request();
                return;
            }
            boundEntryPhase = 2;
            projectWorld.refresh();
            return;
        }
        if (projectWorld.busy() || projectWorld.state() == ProjectWorldWorkbench.State.CHECKING) return;
        if (projectWorld.state() != ProjectWorldWorkbench.State.READY || !projectWorld.binding().filter(boundEntryBinding::equals).isPresent()) {
            boundEntryPhase = 0;
            projectWorldMessage = c("project_world_failed");
            request();
            return;
        }
        if (boundEntryPhase == 2) {
            if (connectedWorldRoot().isPresent()) {
                boundEntryPhase = 0;
                if (worldEditingReady()) hideOverlay();
                else projectWorldMessage = c("project_world_wrong_current");
                request();
                return;
            }
            if (!worldSession.canSelect()) {
                boundEntryPhase = 0;
                projectWorldMessage = c("world_session_busy");
                request();
                return;
            }
            hideOverlay();
            boundEntryGeneration = projectDraftGeneration;
            boundEntryPhase = 3;
            worldSession.openSelection();
            return;
        }
        if (worldSession.reading()) return;
        if (boundEntryPhase == 3) {
            boundEntryPhase = 4;
            projectWorld.refresh();
            return;
        }
        String target = pendingBoundWorld;
        boundEntryPhase = 0;
        pendingBoundWorld = null;
        if (!worldSession.worlds().stream().anyMatch(world -> world.getLevelId().equals(target) && !world.isDisabled())) {
            openProjectWorld(false);
            projectWorldMessage = c("project_world_local_only");
            projectWorld.refresh();
            request();
            return;
        }
        worldSession.select(target);
        worldSession.enterSelected();
        request();
    }

    private static Component c(String key, Object... arguments) {
        return Component.translatable("editor.workbench." + key, arguments);
    }

    static Component component(TextContent text) {
        if (text instanceof TextContent.Literal literal) return Component.literal(literal.text());
        TextContent.Message message = (TextContent.Message) text;
        Object[] args = message.arguments().stream().map(argument -> {
            if (argument instanceof TextContent.LiteralArgument literal) return (Object) literal.value();
            if (argument instanceof TextContent.NumberArgument number) return number.value();
            return component(((TextContent.NestedArgument) argument).value());
        }).toArray();
        return Component.translatable(message.key(), args);
    }
}

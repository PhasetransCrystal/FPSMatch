package net.ptcrys.fpsmatch.common.client.screen;

import net.ptcrys.fpsmatch.FPSMatch;
import net.ptcrys.fpsmatch.common.client.FPSMClient;
import net.ptcrys.fpsmatch.common.client.screen.mapselect.FPSMGuiTheme;
import net.ptcrys.fpsmatch.common.item.MapCreatorTool;
import net.ptcrys.fpsmatch.common.item.tool.ToolInteractionAction;
import net.ptcrys.fpsmatch.common.packet.MapCreatorToolActionC2SPacket;
import net.ptcrys.fpsmatch.common.packet.OpenMapCreatorToolScreenS2CPacket;
import net.ptcrys.fpsmatch.common.packet.ToolInteractionC2SPacket;
import net.ptcrys.fpsmatch.util.MapId;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

public class MapCreatorToolScreen extends Screen {

    private static final int PANEL_WIDTH = 300;
    private static final int PANEL_HEIGHT = 226;
    // 统一设计 Token：遮罩/面板/边框全部引用 FPSMGuiTheme（原青色边框改为主题中性边框）
    private static final int SCREEN_OVERLAY = FPSMGuiTheme.BG_SHADOW;
    private static final int PANEL_BACKGROUND = FPSMGuiTheme.BG_PANEL;
    private static final int PANEL_BORDER = FPSMGuiTheme.BORDER_INNER;

    private List<String> availableTypes;
    private List<OpenMapCreatorToolScreenS2CPacket.MapEntry> maps;
    private String selectedType;
    private String selectedMap;

    private EditBox mapNameField;
    private EditBox pos1XField;
    private EditBox pos1YField;
    private EditBox pos1ZField;
    private EditBox pos2XField;
    private EditBox pos2YField;
    private EditBox pos2ZField;
    private Button typeButton;
    private Button mapButton;

    public MapCreatorToolScreen(OpenMapCreatorToolScreenS2CPacket data) {
        super(Component.translatable("gui.fpsm.map_creator.title"));
        this.availableTypes = new ArrayList<>(data.availableTypes());
        this.maps = new ArrayList<>(data.maps());
        this.selectedType = normalizeSelectedType(data.selectedType());
        this.selectedMap = normalizeSelectedMap(data.selectedMap());
    }

    @Override
    protected void init() {
        int left = 18;
        int top = Math.max(18, (this.height - PANEL_HEIGHT) / 2);

        this.typeButton = this.addRenderableWidget(new Button.Builder(Component.empty(), button -> cycleType())
                .pos(left + 110, top + 20)
                .size(172, 20)
                .build());

        this.mapButton = this.addRenderableWidget(new Button.Builder(Component.empty(), button -> cycleMap())
                .pos(left + 110, top + 48)
                .size(172, 20)
                .build());

        this.mapNameField = addTextField(left + 110, top + 80, 172, 18, MapId.MAX_LENGTH, MapId::isValidPrefix);
        this.pos1XField = addIntField(left + 110, top + 118);
        this.pos1YField = addIntField(left + 166, top + 118);
        this.pos1ZField = addIntField(left + 222, top + 118);
        this.pos2XField = addIntField(left + 110, top + 148);
        this.pos2YField = addIntField(left + 166, top + 148);
        this.pos2ZField = addIntField(left + 222, top + 148);

        this.addRenderableWidget(new Button.Builder(Component.translatable("gui.fpsm.map_creator.create"), button -> createMap())
                .pos(left + 18, top + 182)
                .size(82, 20)
                .build());
        this.addRenderableWidget(new Button.Builder(Component.translatable("gui.fpsm.map_creator.save"), button -> updateMap())
                .pos(left + 109, top + 182)
                .size(82, 20)
                .build());
        this.addRenderableWidget(new Button.Builder(Component.translatable("gui.fpsm.close"), button -> onClose())
                .pos(left + 200, top + 182)
                .size(82, 20)
                .build());

        var debugData = FPSMClient.getGlobalData().getDebugData();
        this.addRenderableWidget(new Button.Builder(Component.translatable(
                debugData.isVisible() ? "gui.fpsm.preview.hide" : "gui.fpsm.preview.show"), button -> {
                    debugData.toggleVisibility();
                    button.setMessage(Component.translatable(debugData.isVisible() ? "gui.fpsm.preview.hide" : "gui.fpsm.preview.show"));
                }).pos(left + 18, top + 204).size(264, 20).build());

        updateTypeButton();
        updateMapButton();
        loadFromHeldTool();
    }

    public void applyData(OpenMapCreatorToolScreenS2CPacket data) {
        this.availableTypes = new ArrayList<>(data.availableTypes());
        this.maps = new ArrayList<>(data.maps());
        this.selectedType = normalizeSelectedType(data.selectedType());
        this.selectedMap = normalizeSelectedMap(data.selectedMap());
        if (this.typeButton != null) {
            updateTypeButton();
        }
        if (this.mapButton != null) {
            updateMapButton();
        }
        if (this.mapNameField != null) {
            this.mapNameField.setValue(data.draftMapName());
            setBlockPosFields(data.pos1(), true);
            setBlockPosFields(data.pos2(), false);
        }
    }

    @Override
    public void tick() {
        super.tick();
        if (this.mapNameField != null) {
            this.mapNameField.tick();
        }
        for (EditBox field : getPosFields()) {
            field.tick();
        }
    }

    @Override
    public void render(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        int left = 18;
        int top = Math.max(18, (this.height - PANEL_HEIGHT) / 2);
        guiGraphics.fill(0, 0, this.width, this.height, SCREEN_OVERLAY);
        guiGraphics.fill(left, top, left + PANEL_WIDTH, top + PANEL_HEIGHT, PANEL_BACKGROUND);
        guiGraphics.fill(left, top, left + PANEL_WIDTH, top + 1, PANEL_BORDER);
        guiGraphics.fill(left, top + PANEL_HEIGHT - 1, left + PANEL_WIDTH, top + PANEL_HEIGHT, PANEL_BORDER);
        guiGraphics.fill(left, top, left + 1, top + PANEL_HEIGHT, PANEL_BORDER);
        guiGraphics.fill(left + PANEL_WIDTH - 1, top, left + PANEL_WIDTH, top + PANEL_HEIGHT, PANEL_BORDER);

        guiGraphics.drawString(this.font, this.title, left + 10, top + 8, 0xFFFFFF, false);
        guiGraphics.drawString(this.font, Component.translatable("gui.fpsm.map_creator.type"), left + 10, top + 26, 0xD0E3EA, false);
        guiGraphics.drawString(this.font, Component.translatable("gui.fpsm.map_creator.map"), left + 10, top + 54, 0xD0E3EA, false);
        guiGraphics.drawString(this.font, Component.translatable("gui.fpsm.map_creator.name"), left + 10, top + 86, 0xD0E3EA, false);
        guiGraphics.drawString(this.font, Component.translatable("gui.fpsm.map_creator.pos1"), left + 10, top + 124, 0xD0E3EA, false);
        guiGraphics.drawString(this.font, Component.translatable("gui.fpsm.map_creator.pos2"), left + 10, top + 154, 0xD0E3EA, false);
        guiGraphics.drawString(this.font, Component.literal("X"), left + 112, top + 108, 0x8FA7B3, false);
        guiGraphics.drawString(this.font, Component.literal("Y"), left + 168, top + 108, 0x8FA7B3, false);
        guiGraphics.drawString(this.font, Component.literal("Z"), left + 224, top + 108, 0x8FA7B3, false);

        super.render(guiGraphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (this.mapNameField.keyPressed(keyCode, scanCode, modifiers)) return true;
        for (EditBox field : getPosFields()) {
            if (field.keyPressed(keyCode, scanCode, modifiers)) {
                return true;
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (this.mapNameField.charTyped(codePoint, modifiers)) return true;
        for (EditBox field : getPosFields()) {
            if (field.charTyped(codePoint, modifiers)) {
                return true;
            }
        }
        return super.charTyped(codePoint, modifiers);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (isInsidePanel(mouseX, mouseY)) {
            return super.mouseClicked(mouseX, mouseY, button);
        }

        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT && button != GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
            return super.mouseClicked(mouseX, mouseY, button);
        }

        BlockPos clickedPos = pickBlockPos(mouseX, mouseY);
        if (clickedPos == null) {
            return super.mouseClicked(mouseX, mouseY, button);
        }

        boolean isPos1 = button == GLFW.GLFW_MOUSE_BUTTON_LEFT;
        FPSMatch.sendToServer(new ToolInteractionC2SPacket(
                isPos1 ? ToolInteractionAction.LEFT_CLICK_BLOCK : ToolInteractionAction.RIGHT_CLICK_BLOCK,
                clickedPos));
        setBlockPosFields(clickedPos, isPos1);
        return true;
    }

    @Override
    public void onClose() {
        FPSMatch.sendToServer(new MapCreatorToolActionC2SPacket(
                MapCreatorToolActionC2SPacket.Action.SAVE_DRAFT,
                this.selectedType,
                this.selectedMap,
                this.mapNameField.getValue(),
                parseBlockPos(true),
                parseBlockPos(false)));
        super.onClose();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private EditBox addTextField(int x, int y, int width, int height, int maxLength, java.util.function.Predicate<String> filter) {
        EditBox editBox = new EditBox(this.font, x, y, width, height, Component.empty());
        editBox.setMaxLength(maxLength);
        editBox.setFilter(filter);
        this.addRenderableWidget(editBox);
        return editBox;
    }

    private EditBox addIntField(int x, int y) {
        return addTextField(x, y, 48, 18, 10, value -> value.matches("-?\\d*"));
    }

    private List<EditBox> getPosFields() {
        return List.of(pos1XField, pos1YField, pos1ZField, pos2XField, pos2YField, pos2ZField);
    }

    private void cycleType() {
        if (this.availableTypes.isEmpty()) {
            this.selectedType = "";
        } else {
            int currentIndex = this.availableTypes.indexOf(this.selectedType);
            int nextIndex = currentIndex < 0 ? 0 : (currentIndex + 1) % this.availableTypes.size();
            this.selectedType = this.availableTypes.get(nextIndex);
        }
        this.selectedMap = normalizeSelectedMap("");
        updateTypeButton();
        updateMapButton();
        loadSelectedMapArea();
    }

    private void cycleMap() {
        List<OpenMapCreatorToolScreenS2CPacket.MapEntry> mapsForType = getMapsForSelectedType();
        if (mapsForType.isEmpty()) {
            this.selectedMap = "";
        } else {
            int currentIndex = indexOfMap(mapsForType, this.selectedMap);
            int nextIndex = currentIndex < 0 ? 0 : (currentIndex + 1) % mapsForType.size();
            this.selectedMap = mapsForType.get(nextIndex).name();
        }
        updateMapButton();
        loadSelectedMapArea();
    }

    private void updateTypeButton() {
        this.typeButton.setMessage(Component.literal(this.selectedType.isBlank() ? "-" : this.selectedType));
    }

    private void updateMapButton() {
        this.mapButton.setMessage(Component.literal(this.selectedMap.isBlank() ? "-" : this.selectedMap));
    }

    private String normalizeSelectedType(String type) {
        if (type != null && !type.isBlank() && this.availableTypes.contains(type)) {
            return type;
        }
        return this.availableTypes.isEmpty() ? "" : this.availableTypes.get(0);
    }

    private String normalizeSelectedMap(String mapName) {
        List<OpenMapCreatorToolScreenS2CPacket.MapEntry> mapsForType = getMapsForSelectedType();
        if (mapName != null && !mapName.isBlank() && indexOfMap(mapsForType, mapName) >= 0) {
            return mapName;
        }
        return mapsForType.isEmpty() ? "" : mapsForType.get(0).name();
    }

    private void loadFromHeldTool() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return;
        }

        ItemStack stack = minecraft.player.getMainHandItem();
        if (!(stack.getItem() instanceof MapCreatorTool)) {
            return;
        }

        this.selectedType = normalizeSelectedType(MapCreatorTool.getSelectedType(stack));
        this.selectedMap = normalizeSelectedMap(MapCreatorTool.getSelectedMap(stack));
        this.mapNameField.setValue(MapCreatorTool.getDraftMapName(stack));
        setBlockPosFields(MapCreatorTool.getBlockPos(stack, MapCreatorTool.BLOCK_POS_TAG_1), true);
        setBlockPosFields(MapCreatorTool.getBlockPos(stack, MapCreatorTool.BLOCK_POS_TAG_2), false);
        updateTypeButton();
        updateMapButton();
    }

    private void setBlockPosFields(@Nullable BlockPos pos, boolean first) {
        EditBox xField = first ? pos1XField : pos2XField;
        EditBox yField = first ? pos1YField : pos2YField;
        EditBox zField = first ? pos1ZField : pos2ZField;
        xField.setValue(pos == null ? "" : Integer.toString(pos.getX()));
        yField.setValue(pos == null ? "" : Integer.toString(pos.getY()));
        zField.setValue(pos == null ? "" : Integer.toString(pos.getZ()));
    }

    private @Nullable BlockPos parseBlockPos(boolean first) {
        EditBox xField = first ? pos1XField : pos2XField;
        EditBox yField = first ? pos1YField : pos2YField;
        EditBox zField = first ? pos1ZField : pos2ZField;
        if (xField.getValue().isBlank() || yField.getValue().isBlank() || zField.getValue().isBlank()) {
            return null;
        }
        try {
            return new BlockPos(
                    Integer.parseInt(xField.getValue()),
                    Integer.parseInt(yField.getValue()),
                    Integer.parseInt(zField.getValue()));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private void createMap() {
        FPSMatch.sendToServer(new MapCreatorToolActionC2SPacket(
                MapCreatorToolActionC2SPacket.Action.CREATE,
                this.selectedType,
                this.selectedMap,
                this.mapNameField.getValue(),
                parseBlockPos(true),
                parseBlockPos(false)));
    }

    private void updateMap() {
        FPSMatch.sendToServer(new MapCreatorToolActionC2SPacket(
                MapCreatorToolActionC2SPacket.Action.UPDATE,
                this.selectedType,
                this.selectedMap,
                this.mapNameField.getValue(),
                parseBlockPos(true),
                parseBlockPos(false)));
    }

    private List<OpenMapCreatorToolScreenS2CPacket.MapEntry> getMapsForSelectedType() {
        return this.maps.stream()
                .filter(map -> map.type().equals(this.selectedType))
                .toList();
    }

    private int indexOfMap(List<OpenMapCreatorToolScreenS2CPacket.MapEntry> mapsForType, String mapName) {
        for (int i = 0; i < mapsForType.size(); i++) {
            if (mapsForType.get(i).name().equals(mapName)) {
                return i;
            }
        }
        return -1;
    }

    private void loadSelectedMapArea() {
        getMapsForSelectedType().stream()
                .filter(map -> map.name().equals(this.selectedMap))
                .findFirst()
                .ifPresent(map -> {
                    setBlockPosFields(map.pos1(), true);
                    setBlockPosFields(map.pos2(), false);
                });
    }

    private boolean isInsidePanel(double mouseX, double mouseY) {
        int left = 18;
        int top = Math.max(18, (this.height - PANEL_HEIGHT) / 2);
        return mouseX >= left && mouseX < left + PANEL_WIDTH && mouseY >= top && mouseY < top + PANEL_HEIGHT;
    }

    private @Nullable BlockPos pickBlockPos(double mouseX, double mouseY) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null) {
            return null;
        }

        Camera camera = minecraft.gameRenderer.getMainCamera();
        Vec3 eyePosition = camera.getPosition();
        Vec3 direction = getRayDirection(camera, mouseX, mouseY);
        double reach = minecraft.player.getBlockReach();
        BlockHitResult hitResult = minecraft.level.clip(new ClipContext(
                eyePosition,
                eyePosition.add(direction.scale(reach)),
                ClipContext.Block.OUTLINE,
                ClipContext.Fluid.NONE,
                minecraft.player));
        if (hitResult.getType() != HitResult.Type.BLOCK) {
            return null;
        }
        return hitResult.getBlockPos();
    }

    private Vec3 getRayDirection(Camera camera, double mouseX, double mouseY) {
        double normalizedX = mouseX / (double) this.width * 2.0D - 1.0D;
        double normalizedY = 1.0D - mouseY / (double) this.height * 2.0D;
        double aspect = (double) this.width / (double) this.height;
        double tanHalfFov = Math.tan(Math.toRadians(Minecraft.getInstance().options.fov().get()) / 2.0D);
        double horizontalScale = normalizedX * aspect * tanHalfFov;
        double verticalScale = normalizedY * tanHalfFov;

        Vec3 look = toVec3(camera.getLookVector());
        Vec3 up = toVec3(camera.getUpVector());
        Vec3 left = toVec3(camera.getLeftVector());
        return look.add(left.scale(-horizontalScale)).add(up.scale(verticalScale)).normalize();
    }

    private static Vec3 toVec3(Vector3f vector) {
        return new Vec3(vector.x(), vector.y(), vector.z());
    }
}

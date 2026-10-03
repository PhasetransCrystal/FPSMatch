package net.ptcrys.fpsmatch.common.shop.editor;

public enum ShopEditorResult {

    SUCCESS("gui.fpsm.shop_editor.save.success"),
    NO_PERMISSION("gui.fpsm.shop_editor.save.no_permission"),
    INVALID_ID("gui.fpsm.shop_editor.open.invalid_id"),
    SHOP_UNAVAILABLE("gui.fpsm.shop_editor.save.shop_unavailable"),
    INVALID_SLOT("gui.fpsm.shop_editor.save.invalid_slot"),
    INVALID_ITEM("gui.fpsm.shop_editor.save.invalid_item"),
    INVALID_VALUE("gui.fpsm.shop_editor.save.invalid_value"),
    INVALID_MODULE("gui.fpsm.shop_editor.save.invalid_module"),
    CONFLICT("gui.fpsm.shop_editor.save.conflict"),
    FAILED("gui.fpsm.shop_editor.save.failed"),
    MODULE_EXISTS("gui.fpsm.listener_editor.exists"),
    MODULE_READ_ONLY("gui.fpsm.listener_editor.read_only"),
    MODULE_IN_USE("gui.fpsm.listener_editor.in_use");

    private final String translationKey;

    ShopEditorResult(String translationKey) {
        this.translationKey = translationKey;
    }

    public String translationKey() {
        return translationKey;
    }
}

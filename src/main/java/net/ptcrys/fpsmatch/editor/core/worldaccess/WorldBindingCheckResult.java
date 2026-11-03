package net.ptcrys.fpsmatch.editor.core.worldaccess;

/** Opaque successful check, consumable once by the issuing controller. */
public final class WorldBindingCheckResult {

    final WorldBindingCheck check;
    boolean consumed;

    WorldBindingCheckResult(WorldBindingCheck check) {
        this.check = check;
    }
}

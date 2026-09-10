package com.ptcrys.fpsmatch.common.client.screen.mapselect.ldlib2;

import com.ptcrys.fpsmatch.common.packet.mapselect.MapRoomDetail;
import net.minecraft.client.gui.screens.Screen;

/** Compatibility entry point; all room details use the unified lobby. */
@Deprecated
public final class Ldlib2MapDetailScreen extends Ldlib2TeamManageScreen {
    public Ldlib2MapDetailScreen(MapRoomDetail detail, Screen parent) {
        super(detail, parent);
    }
}

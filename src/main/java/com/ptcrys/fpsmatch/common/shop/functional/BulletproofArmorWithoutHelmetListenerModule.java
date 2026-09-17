package com.ptcrys.fpsmatch.common.shop.functional;

import com.ptcrys.fpsmatch.common.attributes.ammo.BulletproofArmorAttribute;
import com.ptcrys.fpsmatch.core.shop.event.ShopSlotChangeEvent;
import com.ptcrys.fpsmatch.core.shop.functional.ListenerModule;

public class BulletproofArmorWithoutHelmetListenerModule implements ListenerModule {

    @Override
    public void onChange(ShopSlotChangeEvent event) {
        if (event.flag >= 1) {
            BulletproofArmorAttribute.addPlayer(event.player, new BulletproofArmorAttribute(false));
        } else {
            BulletproofArmorAttribute.removePlayer(event.player);
        }
    }

    @Override
    public String getName() {
        return "bulletproof_without_helmet";
    }

    @Override
    public int getPriority() {
        return 0;
    }
}

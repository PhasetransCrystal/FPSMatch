package net.ptcrys.fpsmatch.common.client.screen.shop;

import net.ptcrys.fpsmatch.common.packet.shop.ShopEditorResultS2CPacket;
import net.ptcrys.fpsmatch.common.packet.shop.ShopEditorResultS2CPacket.Operation;
import net.ptcrys.fpsmatch.common.shop.editor.ShopEditorResult;
import net.ptcrys.fpsmatch.common.shop.editor.ShopEditorSnapshot;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ShopEditorRequestTest {

    private final ShopEditorSnapshot.Target target = new ShopEditorSnapshot.Target("cs", "map", "ct");

    private ShopEditorResultS2CPacket response(long id, Operation operation, ShopEditorSnapshot.Target target) {
        return new ShopEditorResultS2CPacket(id, operation, target, ShopEditorResult.FAILED, null);
    }

    @Test
    void timeoutAllowsRetryAndLateResponseButNewRequestRejectsOldResponse() {
        var request = new ShopEditorRequest(target);
        long first = request.begin(Operation.SAVE_SLOT);
        for (int i = 0; i < 199; i++) assertFalse(request.tick());
        assertTrue(request.tick());
        assertFalse(request.waiting());
        assertTrue(request.accepts(response(first, Operation.SAVE_SLOT, target)));
        request.retry();
        assertTrue(request.waiting());
        assertEquals(first, request.id());
        long second = request.begin(Operation.SAVE_SLOT);
        assertNotEquals(first, second);
        assertFalse(request.accepts(response(first, Operation.SAVE_SLOT, target)));
        assertTrue(request.accepts(response(second, Operation.SAVE_SLOT, target)));
    }

    @Test
    void rejectsWrongOperationShopAndDuplicateCompletion() {
        var request = new ShopEditorRequest(target);
        long id = request.begin(Operation.LOAD);
        assertFalse(request.accepts(response(id, Operation.SAVE_SLOT, target)));
        assertFalse(request.accepts(response(id, Operation.LOAD, new ShopEditorSnapshot.Target("cs", "map", "t"))));
        assertTrue(request.accepts(response(id, Operation.LOAD, target)));
        request.complete();
        assertFalse(request.waiting());
        assertFalse(request.accepts(response(id, Operation.LOAD, target)));
    }
}

package com.ptcrys.fpsmatch.common.client.screen.shop;

import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;

/** Run without Minecraft to exercise malformed editor requests at the trust boundary. */
public final class ShopEditorValuesCheck {
    public static void main(String[] args) {
        require(ShopEditorValues.validGroup(-1), "ungroup sentinel");
        require(!ShopEditorValues.validGroup(-2) && !ShopEditorValues.validGroup(1_000_000), "invalid group range");
        require(ShopEditorValues.validSelection(new int[]{0, 4, 9}, 10), "cross-category selection");
        require(!ShopEditorValues.validSelection(new int[]{0, 4, 4}, 10), "duplicate target");
        require(!ShopEditorValues.validSelection(new int[]{0, 10}, 10), "one invalid target rejects whole batch");
        require(!ShopEditorValues.validSelection(new int[]{-1, 0}, 10), "negative target");
        require(!ShopEditorValues.validSelection(new int[0], 10), "empty batch");
        require(!ShopEditorValues.validSelection(IntStream.range(0, 513).toArray(), 600), "oversize batch");
        var catalog = List.of("purchase", "refund", "server-only");
        require(ShopEditorValues.validModules(List.of(), catalog), "remove all modules");
        require(ShopEditorValues.validModules(List.of("server-only", "refund"), catalog), "server catalog is authoritative");
        require(!ShopEditorValues.validModules(List.of("purchase", "purchase"), catalog), "duplicate callback");
        require(!ShopEditorValues.validModules(List.of("purchase", "unknown"), catalog), "unknown module");
        require(!ShopEditorValues.validModules(Arrays.asList("purchase", null), catalog), "null module");
        require(!ShopEditorValues.validModules(List.of(" "), List.of(" ")), "blank registered name");
        String longName = "x".repeat(257);
        require(!ShopEditorValues.validModules(List.of(longName), List.of(longName)), "oversize module name");
        var tooMany = IntStream.range(0, 65).mapToObj(i -> "module" + i).toList();
        require(!ShopEditorValues.validModules(tooMany, tooMany), "oversize callback list");
        System.out.println("Shop editor request validation passed (16 cases)");
    }

    private static void require(boolean condition, String reason) {
        if (!condition) throw new AssertionError(reason);
    }
}

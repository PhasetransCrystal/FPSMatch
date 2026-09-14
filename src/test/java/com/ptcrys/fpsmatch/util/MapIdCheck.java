package com.ptcrys.fpsmatch.util;

/** Standalone regression check for portable map IDs. */
public final class MapIdCheck {
    public static void main(String[] args) {
        expect(true, MapId.isValid("dust2"), "simple ID");
        expect(true, MapId.isValid("dust_2-rc1"), "separator ID");
        expect(false, MapId.isValid(""), "empty ID");
        expect(false, MapId.isValid("Dust2"), "uppercase ID");
        expect(false, MapId.isValid("dust/2"), "path separator");
        expect(false, MapId.isValid("dust:2"), "Windows separator");
        expect(false, MapId.isValid("a".repeat(MapId.MAX_LENGTH + 1)), "long ID");
        expect(true, MapId.isValidPrefix(""), "empty typing prefix");
        System.out.println("Map ID checks passed");
    }

    private static void expect(boolean expected, boolean actual, String label) {
        if (expected != actual) {
            throw new AssertionError(label + ": expected " + expected + ", got " + actual);
        }
    }
}

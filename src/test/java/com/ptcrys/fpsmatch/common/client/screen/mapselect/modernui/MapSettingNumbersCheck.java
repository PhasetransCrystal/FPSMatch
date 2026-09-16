package com.ptcrys.fpsmatch.common.client.screen.mapselect.modernui;

/** Standalone regression check; requires only the JDK, without a Minecraft client. */
public final class MapSettingNumbersCheck {
    public static void main(String[] args) {
        for (double[] range : new double[][]{{0, 10, 3}, {-1, 1, .1}, {0, 1_000_000_000, 1}, {0, 1, .000001}}) {
            int steps = MapSettingNumbers.sliderSteps(range[0],range[1],range[2]);
            String end = MapSettingNumbers.sliderValue(steps,false,range[0],range[1],range[2]);
            String expected = MapSettingNumbers.normalize(Double.toString(range[1]),false,range[0],range[1],range[2]).orElseThrow();
            if (!end.equals(expected)) throw new AssertionError("Slider cannot reach maximum: " + end);
            if (MapSettingNumbers.sliderProgress(end,range[0],range[1],range[2]) != steps) throw new AssertionError("Slider endpoint round trip");
            if (!MapSettingNumbers.sliderValue(0,false,range[0],range[1],range[2]).equals(MapSettingNumbers.normalize(Double.toString(range[0]),false,range[0],range[1],range[2]).orElseThrow())) throw new AssertionError("Slider minimum");
        }
        expect("9223372036854775807", true, 0, 0, 1, "9223372036854775807");
        expect("-9223372036854775808", true, 0, 0, 1, "-9223372036854775808");
        expect("16777217", true, 0, 0, 1, "16777217");
        expect("0.12345678912345678", false, 0, 0, 1, "0.12345678912345678");
        expect("1e3", false, 0, 0, 1, "1000");
        expect(" 42 ", true, 0, 0, 1, "42");
        expect("-100", true, 0, 10, 2, "0");
        expect("100", true, 0, 10, 2, "10");
        expect("5", true, 0, 10, 2, "6");
        expect("10", true, 0, 10, 3, "9");
        expect("-0.26", false, -1, 1, 0.1, "-0.3");
        expect("0.3", false, 0, 1, 0.1, "0.3");
        expect("0.999", false, 0, 1, 0.3, "0.9");
        expect("0.0000099", false, 0, 1, 0.000001, "0.00001");
        for (String raw : new String[]{"", "-", ".", "NaN", "Infinity", "1e309", "abc", "1e-1000000"}) {
            expect(raw, false, 0, 0, 1, null);
            if (MapSettingNumbers.sliderProgress(raw, 0, 1, .01) != 0) {
                throw new AssertionError("Invalid slider draft: " + raw);
            }
        }
        expect("9223372036854775808", true, 0, 0, 1, null);
        expect("0.5", true, 0, 10, 1, null);
        expect("1".repeat(1025), false, 0, 0, 1, null);
        // The upper endpoint need not be on the step grid. Every output must remain on it.
        for (int i = -100; i <= 200; i++) {
            String raw = Double.toString(i / 37.0);
            String normalized = MapSettingNumbers.normalize(raw, false, -0.4, 1, 0.3).orElseThrow();
            double value = Double.parseDouble(normalized);
            double index = (value + 0.4) / 0.3;
            if (value < -0.4 || value > 1 || Math.abs(index - Math.rint(index)) > 1e-9) {
                throw new AssertionError(raw + " -> " + normalized);
            }
        }
        System.out.println("MapSettingNumbers: 25 boundary cases and 301 step-grid cases passed");
    }

    private static void expect(String raw, boolean integer, double min, double max, double step, String expected) {
        String actual = MapSettingNumbers.normalize(raw, integer, min, max, step).orElse(null);
        if (!java.util.Objects.equals(actual, expected)) {
            throw new AssertionError(raw + ": expected " + expected + ", got " + actual);
        }
    }
}

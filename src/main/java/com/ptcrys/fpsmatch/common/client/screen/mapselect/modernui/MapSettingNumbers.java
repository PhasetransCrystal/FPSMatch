package com.ptcrys.fpsmatch.common.client.screen.mapselect.modernui;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

/** Decimal arithmetic keeps text edits on the server's step grid without float round trips. */
final class MapSettingNumbers {
    private MapSettingNumbers() {}

    static Optional<String> normalize(String raw, boolean integer, double min, double max, double step) {
        if (raw == null || raw.length() > 1024) return Optional.empty();
        try {
            BigDecimal value = new BigDecimal(raw.trim());
            if (value.scale() > 1024 || (long) value.precision() - value.scale() > 1024) return Optional.empty();
            if (integer) value.longValueExact();
            if (!Double.isFinite(value.doubleValue())) return Optional.empty();
            if (value.signum() != 0 && value.doubleValue() == 0) return Optional.empty();
            if (hasRange(min, max, step)) {
                BigDecimal lower = BigDecimal.valueOf(min);
                BigDecimal increment = BigDecimal.valueOf(step);
                BigDecimal steps = BigDecimal.valueOf(max).subtract(lower)
                        .divide(increment, 0, RoundingMode.FLOOR);
                BigDecimal index = value.subtract(lower).divide(increment, 0, RoundingMode.HALF_UP)
                        .max(BigDecimal.ZERO).min(steps);
                value = lower.add(index.multiply(increment));
            }
            if (integer) return Optional.of(Long.toString(value.longValueExact()));
            // Avoid expanding malicious exponents into unbounded strings.
            if (value.scale() > 1024 || value.precision() - value.scale() > 1024) return Optional.empty();
            String normalized = value.stripTrailingZeros().toPlainString();
            return normalized.length() <= 1024 ? Optional.of(normalized) : Optional.empty();
        } catch (NumberFormatException | ArithmeticException ignored) {
            return Optional.empty();
        }
    }

    /** Keep native SeekBar indices bounded while retaining both endpoints on large ranges. */
    static int sliderSteps(double min, double max, double step) {
        return Math.max(1, Math.min(100_000, gridSteps(min,max,step).min(BigDecimal.valueOf(100_000)).intValue()));
    }

    private static BigDecimal gridSteps(double min,double max,double step) {
        return BigDecimal.valueOf(max).subtract(BigDecimal.valueOf(min)).divide(BigDecimal.valueOf(step),0,RoundingMode.FLOOR);
    }

    static int sliderProgress(String value,double min,double max,double step) {
        Optional<String> normalized = normalize(value, false, min, max, step);
        if (normalized.isEmpty()) return 0;
        try {
            BigDecimal grid=gridSteps(min,max,step);
            if(grid.signum()==0)return 0;
            return new BigDecimal(normalized.get()).subtract(BigDecimal.valueOf(min)).divide(BigDecimal.valueOf(step),16,RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(sliderSteps(min,max,step))).divide(grid,0,RoundingMode.HALF_UP)
                    .max(BigDecimal.ZERO).min(BigDecimal.valueOf(sliderSteps(min,max,step))).intValue();
        } catch(NumberFormatException | ArithmeticException invalid) {return 0;}
    }

    static String sliderValue(int progress,boolean integer,double min,double max,double step) {
        int steps=sliderSteps(min,max,step);
        BigDecimal index=gridSteps(min,max,step).multiply(BigDecimal.valueOf(Math.max(0,Math.min(steps,progress))))
                .divide(BigDecimal.valueOf(steps),0,RoundingMode.HALF_UP);
        String value=BigDecimal.valueOf(min).add(index.multiply(BigDecimal.valueOf(step))).toPlainString();
        return normalize(value,integer,min,max,step).orElseThrow();
    }

    static boolean hasRange(double min, double max, double step) {
        return Double.isFinite(min) && Double.isFinite(max) && Double.isFinite(step)
                && max > min && step > 0;
    }
}

package com.ptcrys.fpsmatch.common.client.screen.mapselect.ldlib2;

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

    static boolean hasRange(double min, double max, double step) {
        return Double.isFinite(min) && Double.isFinite(max) && Double.isFinite(step)
                && max > min && step > 0;
    }
}

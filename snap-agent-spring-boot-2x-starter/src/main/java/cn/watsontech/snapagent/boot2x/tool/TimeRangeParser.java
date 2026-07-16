package cn.watsontech.snapagent.boot2x.tool;

import java.time.Instant;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses relative or absolute time strings into Unix epoch seconds.
 *
 * <p>Supported formats:</p>
 * <ul>
 *   <li>{@code "now"} (case-insensitive) → current epoch seconds</li>
 *   <li>{@code "1h"} / {@code "30m"} / {@code "2d"} / {@code "300s"} → now minus duration</li>
 *   <li>Pure number {@code "1689700000"} → treated as epoch seconds</li>
 *   <li>ISO-8601 {@code "2026-07-16T14:00:00Z"} → parsed via {@link Instant#parse}</li>
 * </ul>
 *
 * <p>See design doc §3.2 for the contract.</p>
 */
public final class TimeRangeParser {

    private static final Pattern RELATIVE = Pattern.compile("^(\\d+)([smhd])$");

    private TimeRangeParser() {
    }

    /**
     * Parses the given input into an epoch-second value.
     *
     * @param input time string (relative, pure epoch, or ISO-8601); {@code null}/empty → now
     * @return epoch seconds
     * @throws IllegalArgumentException if the input does not match any known format
     */
    public static long parseToEpochSeconds(String input) {
        if (input == null || input.isEmpty() || "now".equalsIgnoreCase(input)) {
            return Instant.now().getEpochSecond();
        }
        Matcher m = RELATIVE.matcher(input);
        if (m.matches()) {
            long value = Long.parseLong(m.group(1));
            String unit = m.group(2);
            long seconds;
            switch (unit) {
                case "s":
                    seconds = value;
                    break;
                case "m":
                    seconds = value * 60;
                    break;
                case "h":
                    seconds = value * 3600;
                    break;
                case "d":
                    seconds = value * 86400;
                    break;
                default:
                    throw new IllegalArgumentException("Unknown time unit: " + unit);
            }
            return Instant.now().getEpochSecond() - seconds;
        }
        // Pure number → epoch seconds
        try {
            return Long.parseLong(input);
        } catch (NumberFormatException e) {
            // Try ISO-8601
            try {
                return Instant.parse(input).getEpochSecond();
            } catch (Exception e2) {
                throw new IllegalArgumentException("Unrecognized time format: " + input);
            }
        }
    }
}

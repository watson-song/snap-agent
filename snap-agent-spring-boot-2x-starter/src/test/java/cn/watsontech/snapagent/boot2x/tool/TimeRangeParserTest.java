package cn.watsontech.snapagent.boot2x.tool;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * Unit tests for {@link TimeRangeParser}.
 *
 * <p>Covers "now", relative durations (s/m/h/d), pure epoch numbers, ISO-8601
 * timestamps, and invalid inputs. See design doc §8.2.</p>
 */
class TimeRangeParserTest {

    @Test
    @DisplayName("\"now\" returns current epoch seconds")
    void shouldParseNowToCurrentEpoch() {
        long before = Instant.now().getEpochSecond();
        long parsed = TimeRangeParser.parseToEpochSeconds("now");
        long after = Instant.now().getEpochSecond();

        assertThat(parsed).isBetween(before, after);
    }

    @Test
    @DisplayName("\"NOW\" is case-insensitive")
    void shouldParseNowCaseInsensitive() {
        long before = Instant.now().getEpochSecond();
        long parsed = TimeRangeParser.parseToEpochSeconds("NOW");
        long after = Instant.now().getEpochSecond();

        assertThat(parsed).isBetween(before, after);
    }

    @Test
    @DisplayName("null input returns current epoch seconds")
    void shouldParseNullToCurrentEpoch() {
        long before = Instant.now().getEpochSecond();
        long parsed = TimeRangeParser.parseToEpochSeconds(null);
        long after = Instant.now().getEpochSecond();

        assertThat(parsed).isBetween(before, after);
    }

    @Test
    @DisplayName("empty input returns current epoch seconds")
    void shouldParseEmptyToCurrentEpoch() {
        long before = Instant.now().getEpochSecond();
        long parsed = TimeRangeParser.parseToEpochSeconds("");
        long after = Instant.now().getEpochSecond();

        assertThat(parsed).isBetween(before, after);
    }

    @Test
    @DisplayName("\"300s\" subtracts 300 seconds from now")
    void shouldParseSecondsRelative() {
        long now = Instant.now().getEpochSecond();
        long parsed = TimeRangeParser.parseToEpochSeconds("300s");

        assertThat(parsed).isCloseTo(now - 300, within(5L));
    }

    @Test
    @DisplayName("\"30m\" subtracts 1800 seconds from now")
    void shouldParseMinutesRelative() {
        long now = Instant.now().getEpochSecond();
        long parsed = TimeRangeParser.parseToEpochSeconds("30m");

        assertThat(parsed).isCloseTo(now - 30 * 60, within(5L));
    }

    @Test
    @DisplayName("\"1h\" subtracts 3600 seconds from now")
    void shouldParseHoursRelative() {
        long now = Instant.now().getEpochSecond();
        long parsed = TimeRangeParser.parseToEpochSeconds("1h");

        assertThat(parsed).isCloseTo(now - 3600, within(5L));
    }

    @Test
    @DisplayName("\"2d\" subtracts 172800 seconds from now")
    void shouldParseDaysRelative() {
        long now = Instant.now().getEpochSecond();
        long parsed = TimeRangeParser.parseToEpochSeconds("2d");

        assertThat(parsed).isCloseTo(now - 2 * 86400L, within(5L));
    }

    @Test
    @DisplayName("pure number \"1689700000\" is returned as epoch seconds")
    void shouldParsePureNumberAsEpochSeconds() {
        long parsed = TimeRangeParser.parseToEpochSeconds("1689700000");

        assertThat(parsed).isEqualTo(1689700000L);
    }

    @Test
    @DisplayName("ISO-8601 \"2026-07-16T14:00:00Z\" is parsed to epoch seconds")
    void shouldParseIso8601ToEpochSeconds() {
        long parsed = TimeRangeParser.parseToEpochSeconds("2026-07-16T14:00:00Z");

        long expected = Instant.parse("2026-07-16T14:00:00Z").getEpochSecond();
        assertThat(parsed).isEqualTo(expected);
    }

    @Test
    @DisplayName("relative units are case-sensitive (uppercase H rejected)")
    void shouldRejectUppercaseUnit() {
        assertThatThrownBy(() -> TimeRangeParser.parseToEpochSeconds("1H"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unrecognized time format: 1H");
    }

    @Test
    @DisplayName("garbage string throws IllegalArgumentException")
    void shouldRejectUnrecognizedFormat() {
        assertThatThrownBy(() -> TimeRangeParser.parseToEpochSeconds("yesterday"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unrecognized time format: yesterday");
    }

    @Test
    @DisplayName("unsupported unit letter throws IllegalArgumentException")
    void shouldRejectUnsupportedUnit() {
        // "5y" is not a recognised relative format and not a number nor ISO-8601
        assertThatThrownBy(() -> TimeRangeParser.parseToEpochSeconds("5y"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unrecognized time format: 5y");
    }

    @Test
    @DisplayName("malformed ISO-8601 throws IllegalArgumentException")
    void shouldRejectMalformedIso8601() {
        assertThatThrownBy(() -> TimeRangeParser.parseToEpochSeconds("2026/07/16 14:00:00"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unrecognized time format");
    }

    @Test
    @DisplayName("ISO-8601 without zone designator is rejected")
    void shouldRejectIso8601WithoutZone() {
        // Instant.parse (per §3.2) requires a zone/offset; a bare local datetime is not recognised.
        assertThatThrownBy(() -> TimeRangeParser.parseToEpochSeconds("2026-07-16T14:00:00"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unrecognized time format");
    }

    @Test
    @DisplayName("negative pure number is returned as-is (epoch seconds can be negative)")
    void shouldParseNegativeNumberAsEpochSeconds() {
        long parsed = TimeRangeParser.parseToEpochSeconds("-100");

        assertThat(parsed).isEqualTo(-100L);
    }

    @Test
    @DisplayName("decimal number is rejected")
    void shouldRejectDecimalNumber() {
        assertThatThrownBy(() -> TimeRangeParser.parseToEpochSeconds("1689700000.5"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unrecognized time format");
    }

    @Test
    @DisplayName("\"0s\" equals now")
    void shouldParseZeroSecondsAsNow() {
        long now = Instant.now().getEpochSecond();
        long parsed = TimeRangeParser.parseToEpochSeconds("0s");

        assertThat(parsed).isCloseTo(now, within(5L));
    }

    @Test
    @DisplayName("1h then 1h produces equal values within tolerance")
    void shouldBeConsistentAcrossCalls() {
        long first = TimeRangeParser.parseToEpochSeconds("1h");
        long second = TimeRangeParser.parseToEpochSeconds("1h");

        assertThat(first).isCloseTo(second, within(ChronoUnit.SECONDS.getDuration().getSeconds()));
    }
}

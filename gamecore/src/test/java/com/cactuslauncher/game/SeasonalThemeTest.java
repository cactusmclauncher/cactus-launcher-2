package com.cactuslauncher.game;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class SeasonalThemeTest {
    @Test
    void onlyWinterMonthsEnableWinter() {
        for (int month = 1; month <= 12; month++) {
            var clock = Clock.fixed(Instant.parse("2028-%02d-15T12:00:00Z".formatted(month)), ZoneId.of("UTC"));
            assertEquals(month == 12 || month <= 2 ? "winter" : "off", SeasonalTheme.current(clock));
        }
    }

    @Test
    void boundariesFollowComputerLocalZoneInsteadOfUtc() {
        assertAt("2027-11-30T23:30:00Z", "UTC", "off");
        assertAt("2027-11-30T23:30:00Z", "Europe/Moscow", "winter");
        assertAt("2028-03-01T00:30:00Z", "UTC", "off");
        assertAt("2028-03-01T00:30:00Z", "America/Los_Angeles", "winter");
        assertAt("2028-02-29T23:59:59Z", "UTC", "winter");
        assertAt("2027-12-31T23:59:59Z", "UTC", "winter");
        assertAt("2028-01-01T00:00:00Z", "UTC", "winter");
        assertAt("2028-02-01T00:00:00Z", "UTC", "winter");
    }

    @Test
    void runningClockCanCrossMonthBoundary() {
        var clock = Clock.fixed(Instant.parse("2028-02-29T23:59:59Z"), ZoneId.of("UTC"));
        assertEquals("winter", SeasonalTheme.current(clock));
        assertEquals("off", SeasonalTheme.current(Clock.offset(clock, java.time.Duration.ofSeconds(1))));
    }

    private void assertAt(String instant, String zone, String expected) {
        assertEquals(expected, SeasonalTheme.current(Clock.fixed(Instant.parse(instant), ZoneId.of(zone))));
    }
}

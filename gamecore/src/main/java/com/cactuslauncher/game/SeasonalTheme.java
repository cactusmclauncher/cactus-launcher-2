package com.cactuslauncher.game;

import java.time.Clock;
import java.time.LocalDate;

public final class SeasonalTheme {
    private SeasonalTheme() {}

    public static String current() {
        return current(Clock.systemDefaultZone());
    }

    static String current(Clock clock) {
        int month = LocalDate.now(clock).getMonthValue();
        return month == 12 || month == 1 || month == 2 ? "winter" : "off";
    }
}

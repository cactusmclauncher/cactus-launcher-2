package com.cactuslauncher.game;

import java.io.IOException;
import java.nio.file.*;
import java.util.Properties;

/**
 * Play time of one build in {@code <build>/.cactus-playtime.properties} ({@code total} and {@code last}, epoch millis).
 * A running {@link Session} adds its time at every {@link Session#checkpoint}, so a crash of the launcher loses
 * at most one checkpoint interval and a new session never counts the old one again.
 */
public final class Playtime {
    public record Stats(long totalMillis, long lastPlayedMillis) {}

    static final String FILE = ".cactus-playtime.properties";
    public static final long CHECKPOINT_MILLIS = 30_000;
    /** Longest gap one checkpoint may add; more means the computer slept or the clock jumped. */
    static final long MAX_STEP_MILLIS = 10 * 60_000;
    private final Path file;

    public Playtime(Path buildFolder) { this.file = buildFolder.resolve(FILE); }

    /** Missing or damaged data reads as zero, so old builds simply start counting. */
    public synchronized Stats read() {
        try {
            if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > 64 * 1024)
                return new Stats(0, 0);
            Properties properties = new Properties();
            try (var input = Files.newInputStream(file)) { properties.load(input); }
            return new Stats(Math.max(0, parse(properties.getProperty("total"))), Math.max(0, parse(properties.getProperty("last"))));
        } catch (IOException | IllegalArgumentException e) {
            return new Stats(0, 0);
        }
    }

    public Session start(long nowMillis) { return new Session(nowMillis); }

    private synchronized void add(long millis, long nowMillis) throws IOException {
        Stats stats = read();
        Properties properties = new Properties();
        properties.setProperty("total", Long.toString(stats.totalMillis() + millis));
        properties.setProperty("last", Long.toString(Math.max(stats.lastPlayedMillis(), nowMillis)));
        if (Files.isSymbolicLink(file)) throw new IOException("Файл времени игры не должен быть символической ссылкой.");
        Files.createDirectories(file.getParent());
        Path temp = Files.createTempFile(file.getParent(), ".cactus-playtime-", ".tmp");
        try {
            try (var output = Files.newOutputStream(temp)) { properties.store(output, "Cactus Launcher play time"); }
            ModrinthClient.move(temp, file);
        } finally { Files.deleteIfExists(temp); }
    }

    /** "меньше минуты", "40 мин", "12 ч", "12 ч 40 мин"; empty for no play time. */
    public static String duration(long totalMillis) {
        if (totalMillis <= 0) return "";
        long minutes = totalMillis / 60_000;
        if (minutes < 1) return "меньше минуты";
        if (minutes < 60) return minutes + " мин";
        long hours = minutes / 60, rest = minutes % 60;
        return rest == 0 ? hours + " ч" : hours + " ч " + rest + " мин";
    }

    /** "сегодня", "вчера" or a date like "12.10.2026". */
    public static String day(long millis, long nowMillis, java.time.ZoneId zone) {
        var date = java.time.Instant.ofEpochMilli(millis).atZone(zone).toLocalDate();
        var today = java.time.Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate();
        if (date.equals(today)) return "сегодня";
        if (date.equals(today.minusDays(1))) return "вчера";
        return java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy").format(date);
    }

    /** "12 ч 40 мин · вчера", or empty when nothing was counted yet (builds launched before play time existed). */
    public static String summary(Stats stats, long nowMillis, java.time.ZoneId zone) {
        if (stats.totalMillis() <= 0) return "";
        String text = duration(stats.totalMillis());
        return stats.lastPlayedMillis() > 0 ? text + " · " + day(stats.lastPlayedMillis(), nowMillis, zone) : text;
    }

    private static long parse(String value) {
        if (value == null) return 0;
        try { return Long.parseLong(value.trim()); } catch (NumberFormatException e) { return 0; }
    }

    /** One game process. Not thread-safe by itself; callers checkpoint from one thread or synchronize. */
    public final class Session {
        private long counted;
        private boolean stopped;

        private Session(long startMillis) { this.counted = startMillis; }

        /** Adds the time since the previous checkpoint and records "last played" as now. */
        public synchronized void checkpoint(long nowMillis) throws IOException {
            if (stopped) return;
            long step = Math.min(Math.max(0, nowMillis - counted), MAX_STEP_MILLIS);
            add(step, nowMillis);
            counted = Math.max(counted, nowMillis);
        }

        public synchronized void stop(long nowMillis) throws IOException {
            if (stopped) return;
            checkpoint(nowMillis);
            stopped = true;
        }
    }
}

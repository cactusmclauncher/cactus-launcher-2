package com.cactuslauncher.game;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlaytimeTest {
    private static final long MINUTE = 60_000;
    private static final long T0 = 1_760_000_000_000L;
    @TempDir Path temp;

    @Test
    void sessionAddsTimeAtCheckpointsAndStop() throws Exception {
        var playtime = new Playtime(temp);
        var session = playtime.start(T0);
        session.checkpoint(T0 + 30_000);
        assertEquals(30_000, playtime.read().totalMillis());
        session.stop(T0 + 75_000);
        session.stop(T0 + 999_000);
        assertEquals(new Playtime.Stats(75_000, T0 + 75_000), new Playtime(temp).read());
    }

    @Test
    void launcherCrashLosesOnlyTheLastIntervalAndNeverDoubleCounts() throws Exception {
        new Playtime(temp).start(T0).checkpoint(T0 + MINUTE); // then the launcher dies
        var next = new Playtime(temp).start(T0 + 10 * MINUTE);
        next.stop(T0 + 10 * MINUTE + 30_000);
        assertEquals(MINUTE + 30_000, new Playtime(temp).read().totalMillis());
        var another = new Playtime(temp).start(T0 + 20 * MINUTE);
        another.stop(T0 + 21 * MINUTE);
        assertEquals(2 * MINUTE + 30_000, new Playtime(temp).read().totalMillis());
    }

    @Test
    void sleepAndClockJumpsAreBounded() throws Exception {
        var session = new Playtime(temp).start(T0);
        session.checkpoint(T0 + 120 * MINUTE);
        assertEquals(Playtime.MAX_STEP_MILLIS, new Playtime(temp).read().totalMillis());
        session.checkpoint(T0 + 60 * MINUTE); // clock went back
        assertEquals(Playtime.MAX_STEP_MILLIS, new Playtime(temp).read().totalMillis());
    }

    @Test
    void oldOrDamagedDataReadsAsZero() throws Exception {
        assertEquals(new Playtime.Stats(0, 0), new Playtime(temp).read());
        Files.writeString(temp.resolve(Playtime.FILE), "total=abc\nlast=-5\n");
        assertEquals(new Playtime.Stats(0, 0), new Playtime(temp).read());
        new Playtime(temp).start(T0).stop(T0 + MINUTE);
        assertEquals(MINUTE, new Playtime(temp).read().totalMillis());
    }

    @Test
    void formatsDurationAndDay() {
        assertEquals("", Playtime.duration(0));
        assertEquals("меньше минуты", Playtime.duration(59_000));
        assertEquals("40 мин", Playtime.duration(40 * MINUTE));
        assertEquals("12 ч", Playtime.duration(12 * 60 * MINUTE));
        assertEquals("12 ч 40 мин", Playtime.duration((12 * 60 + 40) * MINUTE + 59_000));
        ZoneId zone = ZoneId.of("Europe/Moscow");
        long now = ZonedDateTime.of(2026, 10, 21, 10, 0, 0, 0, zone).toInstant().toEpochMilli();
        long lateYesterday = ZonedDateTime.of(2026, 10, 20, 23, 50, 0, 0, zone).toInstant().toEpochMilli();
        assertEquals("сегодня", Playtime.day(now - MINUTE, now, zone));
        assertEquals("вчера", Playtime.day(lateYesterday, now, zone));
        assertEquals("01.10.2026", Playtime.day(ZonedDateTime.of(2026, 10, 1, 12, 0, 0, 0, zone).toInstant().toEpochMilli(), now, zone));
        assertEquals("12 ч 40 мин · вчера", Playtime.summary(new Playtime.Stats((12 * 60 + 40) * MINUTE, lateYesterday), now, zone));
        assertEquals("", Playtime.summary(new Playtime.Stats(0, lateYesterday), now, zone));
    }

    @Test
    void buildStoreReadsOldFilesAndKeepsAutoUpdateSetting() throws Exception {
        Path file = temp.resolve("builds.properties");
        String id = "0f8fad5b-d9cb-469f-a165-70867728950e";
        Files.writeString(file, "format=1\norder=" + id + "\nbuild." + id + ".name=Old\nbuild." + id + ".version=1.21.1\nbuild." + id + ".loader=Fabric\n");
        var store = new BuildStore(file);
        assertFalse(store.load().getFirst().autoUpdateMods());
        store.setAutoUpdateMods(id, true);
        store.rename(id, "Renamed");
        store.setIcon(id, "diamond");
        var reloaded = new BuildStore(file).load().getFirst();
        assertTrue(reloaded.autoUpdateMods());
        assertEquals("Renamed", reloaded.name());
        store.setAutoUpdateMods(id, false);
        assertFalse(Files.readString(file).contains("autoUpdateMods"));
    }
}

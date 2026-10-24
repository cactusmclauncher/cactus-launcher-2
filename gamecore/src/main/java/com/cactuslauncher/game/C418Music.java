package com.cactuslauncher.game;

import java.util.List;
import java.util.Random;

/**
 * The C418 tracks the launcher plays on start and puts into the game's title-screen music.
 * No audio ships with the launcher: every track is a vanilla Minecraft asset, read from the
 * asset index and objects folder (see {@link MusicAssets}).
 */
public final class C418Music {
    /**
     * One track. {@code path} is the sound name since 1.20.3 (minecraft/sounds/&lt;path&gt;.ogg),
     * {@code legacyPath} the name of the same file (same SHA-1) in older asset indexes.
     * Titles match the game's own language files (key music.game.* / music.menu.*).
     */
    public record Track(String title, String path, String legacyPath, int weight) {
        public String assetKey() { return "minecraft/sounds/" + path + ".ogg"; }
        public String legacyAssetKey() { return "minecraft/sounds/" + legacyPath + ".ogg"; }
    }

    /** Chance of each track, in percent (the weights add up to 100). Edit only here. */
    public static final List<Track> TRACKS = List.of(
            new Track("Moog City 2",       "music/menu/moog_city_2",       "music/menu/menu2",     40),
            new Track("Sweden",            "music/game/sweden",            "music/game/calm3",     12),
            new Track("Wet Hands",         "music/game/wet_hands",         "music/game/piano2",    10),
            new Track("Subwoofer Lullaby", "music/game/subwoofer_lullaby", "music/game/hal1",       8),
            new Track("Mice on Venus",     "music/game/mice_on_venus",     "music/game/piano3",     8),
            new Track("Key",               "music/game/key",               "music/game/nuance1",    7),
            new Track("Living Mice",       "music/game/living_mice",       "music/game/hal2",       5),
            new Track("Danny",             "music/game/danny",             "music/game/hal4",       5),
            new Track("Haggstrom",         "music/game/haggstrom",         "music/game/hal3",       5));

    private C418Music() {}

    public static int totalWeight(List<Track> tracks) {
        return tracks.stream().mapToInt(Track::weight).sum();
    }

    /** A weighted random track from [tracks]; null when the list is empty. */
    public static Track pick(List<Track> tracks, Random random) {
        int total = totalWeight(tracks);
        if (total <= 0) return null;
        int roll = random.nextInt(total);
        for (Track track : tracks) {
            roll -= track.weight();
            if (roll < 0) return track;
        }
        return tracks.getLast();
    }
}

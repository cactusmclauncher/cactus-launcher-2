package com.cactuslauncher.game;

public final class GameCore {

    /** Default .minecraft location for the current OS. */
    public String defaultGameDir() {
        String os = System.getProperty("os.name", "").toLowerCase();
        String home = System.getProperty("user.home", ".");
        if (os.contains("mac")) {
            return home + "/Library/Application Support/minecraft";
        }
        if (os.contains("win")) {
            return System.getenv().getOrDefault("APPDATA", home) + "\\.minecraft";
        }
        return home + "/.minecraft";
    }
}

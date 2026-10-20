package com.cactuslauncher.game;

import java.awt.Desktop;
import java.io.IOException;
import java.nio.file.Path;

public final class BuildFolders {
    public static void open(Path folder) throws IOException {
        if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.OPEN))
            throw new IOException("Открытие папок не поддерживается системой.");
        Desktop.getDesktop().open(folder.toFile());
    }
}

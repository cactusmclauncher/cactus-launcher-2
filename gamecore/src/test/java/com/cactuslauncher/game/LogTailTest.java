package com.cactuslauncher.game;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LogTailTest {
    @TempDir Path temp;

    private Path log(String text) throws Exception {
        return Files.writeString(temp.resolve("cactus-launch.log"), text, StandardCharsets.UTF_8);
    }

    private static void append(Path file, String text) throws Exception {
        Files.writeString(file, text, StandardCharsets.UTF_8, StandardOpenOption.APPEND);
    }

    @Test
    void readsOnlyAppendedCompleteLines() throws Exception {
        Path file = log("first\r\nsecond\npart");
        var chunk = LogTail.read(file, 0, 1024);
        assertEquals(List.of("first", "second"), chunk.lines());
        assertFalse(chunk.reset());
        append(file, "ial\nthird\n");
        var next = LogTail.read(file, chunk.nextOffset(), 1024);
        assertEquals(List.of("partial", "third"), next.lines());
        assertEquals(Files.size(file), next.nextOffset());
        assertTrue(LogTail.read(file, next.nextOffset(), 1024).lines().isEmpty());
    }

    @Test
    void truncatedLogRestartsFromBeginning() throws Exception {
        Path file = log("old line one\nold line two\n");
        long offset = LogTail.read(file, 0, 1024).nextOffset();
        log("new\n");
        var chunk = LogTail.read(file, offset, 1024);
        assertTrue(chunk.reset());
        assertEquals(List.of("new"), chunk.lines());
    }

    @Test
    void longLogStartsAtTailOnLineBoundary() throws Exception {
        var text = new StringBuilder();
        for (int index = 0; index < 1000; index++) text.append("line-").append(index).append('\n');
        Path file = log(text.toString());
        var chunk = LogTail.read(file, 0, 100);
        assertTrue(chunk.reset());
        assertEquals("line-999", chunk.lines().getLast());
        assertTrue(chunk.lines().stream().allMatch(line -> line.matches("line-\\d+")), chunk.lines().toString());
        assertTrue(chunk.lines().size() < 20);
    }

    @Test
    void invalidUtf8IsReplacedNotFatal() throws Exception {
        Path file = temp.resolve("cactus-launch.log");
        Files.write(file, new byte[] {'o', 'k', (byte) 0xC3, '\n'});
        assertEquals(List.of("ok\uFFFD"), LogTail.read(file, 0, 1024).lines());
    }

    @Test
    void missingFileIsEmpty() throws Exception {
        var chunk = LogTail.read(temp.resolve("missing.log"), 0, 1024);
        assertTrue(chunk.lines().isEmpty());
        assertFalse(chunk.reset());
    }
}

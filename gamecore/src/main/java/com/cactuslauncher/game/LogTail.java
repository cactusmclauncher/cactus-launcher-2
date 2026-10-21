package com.cactuslauncher.game;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Incremental log reader: returns only complete lines appended after {@code offset}.
 * A shorter file than the offset means the log was truncated by a new launch, so reading restarts.
 */
public final class LogTail {
    public record Chunk(List<String> lines, long nextOffset, boolean reset) {
        public Chunk { lines = List.copyOf(lines); }
    }

    private LogTail() {}

    public static Chunk read(Path file, long offset, int maxBytes) throws IOException {
        if (maxBytes < 1) throw new IllegalArgumentException("Лимит чтения должен быть положительным.");
        if (Files.isSymbolicLink(file)) throw new IOException("Файл лога не должен быть символической ссылкой.");
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return new Chunk(List.of(), 0, offset != 0);
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            long size = channel.size();
            boolean reset = offset < 0 || offset > size;
            long start = reset ? 0 : offset;
            boolean skipPartialLine = false;
            if (size - start > maxBytes) {
                // Too far behind (first open of a long log): jump to the tail and start on a line boundary.
                start = size - maxBytes;
                reset = true;
                skipPartialLine = !endsLine(channel, start);
            }
            int length = (int) (size - start);
            if (length == 0) return new Chunk(List.of(), start, reset);
            ByteBuffer buffer = ByteBuffer.allocate(length);
            while (buffer.hasRemaining() && channel.read(buffer, start + buffer.position()) > 0) { }
            byte[] bytes = buffer.array();
            int read = buffer.position();
            int from = 0;
            if (skipPartialLine) {
                while (from < read && bytes[from] != '\n') from++;
                from = Math.min(read, from + 1);
            }
            int lastNewline = -1;
            for (int index = read - 1; index >= from; index--) if (bytes[index] == '\n') { lastNewline = index; break; }
            // A single line longer than the whole window would never complete; emit it as is.
            int end = lastNewline >= 0 ? lastNewline + 1 : (read - from >= maxBytes ? read : from);
            var decoder = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE);
            String text = decoder.decode(ByteBuffer.wrap(bytes, from, end - from)).toString();
            List<String> lines = new ArrayList<>();
            if (!text.isEmpty()) {
                for (String line : text.split("\n", -1)) lines.add(line.endsWith("\r") ? line.substring(0, line.length() - 1) : line);
                if (text.endsWith("\n")) lines.remove(lines.size() - 1);
            }
            return new Chunk(lines, start + end, reset);
        }
    }

    private static boolean endsLine(FileChannel channel, long position) throws IOException {
        if (position == 0) return true;
        ByteBuffer previous = ByteBuffer.allocate(1);
        return channel.read(previous, position - 1) == 1 && previous.get(0) == '\n';
    }
}

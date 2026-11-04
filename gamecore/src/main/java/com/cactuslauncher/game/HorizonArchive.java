package com.cactuslauncher.game;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

final class HorizonArchive {
    private static final long MAX_EXPANDED = 1024L * 1024 * 1024;
    private static final int MAX_ENTRIES = 20000;

    static Path safe(Path root, String name) throws IOException {
        if (name.isBlank() || name.startsWith("/") || name.contains("\\") || name.contains(":"))
            throw new IOException("Небезопасный путь в архиве Horizon.");
        for (String part : name.split("/")) {
            if (part.equals("..") || part.endsWith(".") || part.endsWith(" ") || part.matches("(?i)(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\\..*)?") || part.chars().anyMatch(c -> c < 32))
                throw new IOException("Небезопасный путь в архиве Horizon.");
        }
        Path target = root.resolve(name).normalize();
        if (!target.startsWith(root) || target.equals(root)) throw new IOException("Путь выходит за архив Horizon.");
        for (Path path = target; path != null && path.startsWith(root); path = path.getParent())
            if (Files.isSymbolicLink(path)) throw new IOException("Ссылки в архиве Horizon запрещены.");
        return target;
    }

    static void extract(Path archive, Path root, boolean gzip) throws IOException {
        if (Files.exists(root, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Папка распаковки уже существует.");
        Files.createDirectory(root);
        if (gzip) untar(archive, root); else unzip(archive, root);
    }

    private static void unzip(Path archive, Path root) throws IOException {
        checkZipModes(archive);
        long expanded = 0;
        Set<String> seen = new HashSet<>();
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                HorizonInstaller.cancelled();
                ZipEntry entry = entries.nextElement();
                Path target = safe(root, entry.getName());
                unique(root, target, seen);
                if (entry.isDirectory()) Files.createDirectories(target);
                else {
                    Files.createDirectories(target.getParent());
                    try (InputStream input = zip.getInputStream(entry); OutputStream output = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW)) {
                        expanded += copy(input, output, MAX_EXPANDED - expanded);
                    }
                }
            }
        }
    }

    private static void checkZipModes(Path archive) throws IOException {
        try (RandomAccessFile file = new RandomAccessFile(archive.toFile(), "r")) {
            int length = (int) Math.min(file.length(), 65557);
            byte[] tail = new byte[length];
            file.seek(file.length() - length);
            file.readFully(tail);
            int end = -1;
            for (int i = length - 22; i >= 0; i--) {
                if (number(tail, i, 4) == 0x06054b50L && i + 22 + number(tail, i + 20, 2) == length) { end = i; break; }
            }
            if (end < 0 || number(tail, end + 4, 4) != 0) throw new IOException("Неподдерживаемый ZIP Horizon.");
            int count = (int) number(tail, end + 10, 2);
            long offset = number(tail, end + 16, 4);
            long size = number(tail, end + 12, 4);
            if (count == 65535 || count > MAX_ENTRIES || offset + size > file.length() - length + end)
                throw new IOException("Слишком большой или повреждённый ZIP Horizon.");
            file.seek(offset);
            for (int i = 0; i < count; i++) {
                HorizonInstaller.cancelled();
                byte[] header = new byte[46];
                file.readFully(header);
                if (number(header, 0, 4) != 0x02014b50L || (number(header, 8, 2) & 1) != 0)
                    throw new IOException("Повреждённый или зашифрованный ZIP Horizon.");
                int mode = (int) (number(header, 38, 4) >>> 16) & 0170000;
                if (mode != 0 && mode != 0100000 && mode != 0040000)
                    throw new IOException("Ссылки и специальные файлы ZIP Horizon запрещены.");
                long next = file.getFilePointer() + number(header, 28, 2) + number(header, 30, 2) + number(header, 32, 2);
                if (next > offset + size) throw new IOException("Повреждённый каталог ZIP Horizon.");
                file.seek(next);
            }
            if (file.getFilePointer() != offset + size) throw new IOException("Повреждённый каталог ZIP Horizon.");
        }
    }

    private static long number(byte[] bytes, int at, int length) {
        long result = 0;
        for (int i = 0; i < length; i++) result |= (bytes[at + i] & 255L) << (8 * i);
        return result;
    }

    private static void untar(Path archive, Path root) throws IOException {
        Set<String> seen = new HashSet<>();
        long expanded = 0;
        try (InputStream input = new GZIPInputStream(Files.newInputStream(archive))) {
            while (true) {
                HorizonInstaller.cancelled();
                byte[] header = input.readNBytes(512);
                if (header.length != 512) throw new IOException("Оборванный TAR Horizon.");
                if (Arrays.equals(header, new byte[512])) {
                    if (!Arrays.equals(input.readNBytes(512), new byte[512])) throw new IOException("Оборванный конец TAR Horizon.");
                    int padding = 0;
                    for (int value; (value = input.read()) != -1;) {
                        if (value != 0 || ++padding > 1024 * 1024) throw new IOException("Неожиданные данные после TAR Horizon.");
                        HorizonInstaller.cancelled();
                    }
                    return;
                }
                long checksum = 0;
                for (int i = 0; i < 512; i++) checksum += i >= 148 && i < 156 ? 32 : header[i] & 255;
                if (checksum != octal(header, 148, 8)) throw new IOException("Неверная контрольная сумма TAR Horizon.");
                int type = header[156];
                if (type != 0 && type != '0' && type != '5')
                    throw new IOException("Ссылки, специальные файлы и расширения TAR Horizon запрещены.");
                String name = text(header, 0, 100);
                String prefix = text(header, 345, 155);
                if (!prefix.isEmpty()) name = prefix + "/" + name;
                Path target = safe(root, name);
                unique(root, target, seen);
                long size = octal(header, 124, 12);
                if (size > MAX_EXPANDED - expanded || (type == '5' && size != 0)) throw new IOException("Слишком большой TAR Horizon.");
                if (type == '5') Files.createDirectories(target);
                else {
                    Files.createDirectories(target.getParent());
                    try (OutputStream output = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW)) {
                        long remaining = size;
                        byte[] buffer = new byte[65536];
                        while (remaining > 0) {
                            HorizonInstaller.cancelled();
                            int read = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                            if (read < 0) throw new IOException("Оборванный TAR Horizon.");
                            output.write(buffer, 0, read);
                            remaining -= read;
                        }
                    }
                    expanded += size;
                }
                long padding = (512 - size % 512) % 512;
                if (input.readNBytes((int) padding).length != padding) throw new IOException("Оборванный TAR Horizon.");
            }
        }
    }

    private static String text(byte[] bytes, int at, int length) {
        int end = at;
        while (end < at + length && bytes[end] != 0) end++;
        return new String(bytes, at, end - at, StandardCharsets.UTF_8);
    }

    private static long octal(byte[] bytes, int at, int length) throws IOException {
        try { return Long.parseLong(text(bytes, at, length).trim(), 8); }
        catch (NumberFormatException e) { throw new IOException("Некорректный размер TAR Horizon.", e); }
    }

    private static void unique(Path root, Path target, Set<String> seen) throws IOException {
        if (!seen.add(root.relativize(target).toString().toLowerCase(Locale.ROOT)) || seen.size() > MAX_ENTRIES)
            throw new IOException("Повторяющийся путь или слишком много файлов Horizon.");
    }

    private static long copy(InputStream input, OutputStream output, long limit) throws IOException {
        byte[] buffer = new byte[65536];
        long total = 0;
        for (int read; (read = input.read(buffer)) != -1;) {
            HorizonInstaller.cancelled();
            total += read;
            if (total > limit) throw new IOException("Распакованный Horizon превышает лимит размера.");
            output.write(buffer, 0, read);
        }
        return total;
    }
}

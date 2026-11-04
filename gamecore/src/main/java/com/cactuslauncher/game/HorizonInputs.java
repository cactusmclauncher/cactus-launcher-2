package com.cactuslauncher.game;

import com.google.gson.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

final class HorizonInputs {
    static void validate(Path root, JsonObject pins) throws IOException {
        try {
            String version = pins.get("version").getAsString();
            if (!version.matches("[A-Za-z0-9._-]{1,64}")) throw new IOException("Некорректная версия Minecraft.");
            Path manifestFile = root.resolve("versions").resolve(version).resolve(version + ".json");
            String checksum = HorizonInstaller.digest(manifestFile, "SHA-1");
            if (!accepted(pins, "manifest_sha1", "accepted_manifest_sha1", checksum)) throw new IOException("JSON версии Minecraft не совпадает с research-lock.json Horizon.");
            JsonObject manifest = json(manifestFile, 4 * 1024 * 1024);
            if (!manifest.get("id").getAsString().equals(version)) throw new IOException("Не та версия Minecraft.");
            check(root.resolve("versions").resolve(version).resolve(version + ".jar"), pins.get("client_sha1").getAsString());
            int libraries = 0;
            for (JsonElement element : manifest.getAsJsonArray("libraries")) {
                if (++libraries > 2000) throw new IOException("Слишком много библиотек Minecraft.");
                JsonObject library = element.getAsJsonObject();
                for (JsonObject artifact : HorizonResources.libraryArtifacts(library)) {
                    check(HorizonArchive.safe(root.resolve("libraries"), artifact.get("path").getAsString()), artifact.get("sha1").getAsString());
                }
            }
            JsonObject indexInfo = manifest.getAsJsonObject("assetIndex");
            String indexId = indexInfo.get("id").getAsString();
            if (!indexId.matches("[A-Za-z0-9._-]{1,64}")) throw new IOException("Некорректный индекс assets Minecraft.");
            Path indexFile = root.resolve("assets/indexes").resolve(indexId + ".json");
            String indexHash = HorizonInstaller.digest(indexFile, "SHA-1");
            if (!indexHash.equals(indexInfo.get("sha1").getAsString()) && !accepted(pins, "unused", "accepted_asset_index_sha1", indexHash))
                throw new IOException("Индекс assets Minecraft повреждён.");
            JsonObject objects = json(indexFile, 32 * 1024 * 1024).getAsJsonObject("objects");
            if (objects.size() > 100000) throw new IOException("Слишком много assets Minecraft.");
            long total = 0;
            Set<String> checked = new HashSet<>();
            for (var entry : objects.entrySet()) {
                JsonObject object = entry.getValue().getAsJsonObject();
                String hash = object.get("hash").getAsString();
                if (!hash.matches("[a-f0-9]{40}")) throw new IOException("Некорректный hash assets Minecraft.");
                if (!checked.add(hash)) continue;
                long size = object.get("size").getAsLong();
                if (size < 0 || size > 256L * 1024 * 1024 || (total += size) > 4L * 1024 * 1024 * 1024) throw new IOException("Assets Minecraft превышают лимит размера.");
                Path file = root.resolve("assets/objects").resolve(hash.substring(0, 2)).resolve(hash);
                check(file, hash);
                if (Files.size(file) != size) throw new IOException("Размер assets Minecraft не совпадает с индексом.");
            }
        } catch (RuntimeException e) { throw new IOException("Некорректные локальные данные Minecraft.", e); }
    }

    private static JsonObject json(Path file, int limit) throws IOException {
        HorizonInstaller.regular(file);
        if (Files.size(file) > limit) throw new IOException("JSON Minecraft превышает лимит размера.");
        return JsonParser.parseString(Files.readString(file)).getAsJsonObject();
    }

    static boolean accepted(JsonObject pins, String primary, String alternatives, String actual) {
        if (pins.has(primary) && pins.get(primary).getAsString().equals(actual)) return true;
        if (pins.has(alternatives)) for (JsonElement value : pins.getAsJsonArray(alternatives)) if (value.getAsString().equals(actual)) return true;
        return false;
    }

    private static void check(Path file, String expected) throws IOException {
        if (!expected.matches("[a-f0-9]{40}") || !HorizonInstaller.digest(file, "SHA-1").equals(expected))
            throw new IOException("Локальный файл Minecraft повреждён: " + file.getFileName());
    }
}

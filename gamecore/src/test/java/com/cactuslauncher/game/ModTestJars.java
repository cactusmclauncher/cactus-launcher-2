package com.cactuslauncher.game;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** Mod jars with only metadata files, for metadata and conflict tests. */
final class ModTestJars {
    private ModTestJars() {}

    static byte[] jar(String... nameThenContent) throws IOException {
        var entries = new LinkedHashMap<String, byte[]>();
        for (int i = 0; i < nameThenContent.length; i += 2) entries.put(nameThenContent[i], nameThenContent[i + 1].getBytes(StandardCharsets.UTF_8));
        return TestJars.zip(entries);
    }

    static byte[] withNested(byte[] outer, Map<String, byte[]> nested) throws IOException {
        var entries = new LinkedHashMap<String, byte[]>();
        try (var zip = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(outer))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) entries.put(entry.getName(), zip.readAllBytes());
        }
        entries.putAll(nested);
        return TestJars.zip(entries);
    }

    static byte[] fabric(String json) throws IOException { return jar("fabric.mod.json", json); }

    static byte[] fabric(String id, String version, String depends) throws IOException {
        return fabric("{\"schemaVersion\":1,\"id\":\"" + id + "\",\"version\":\"" + version + "\",\"name\":\"" + id + "\"" +
                (depends == null ? "" : ",\"depends\":{" + depends + "}") + "}");
    }

    static byte[] forge(String toml) throws IOException { return jar("META-INF/mods.toml", toml); }
}

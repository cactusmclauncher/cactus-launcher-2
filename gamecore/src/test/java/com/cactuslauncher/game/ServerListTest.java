package com.cactuslauncher.game;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ServerListTest {
    @TempDir Path temp;

    /** servers.dat as the game writes it: uncompressed, unknown tags on the root and on entries. */
    private Path vanillaFile() throws Exception {
        var first = new LinkedHashMap<String, Object>();
        first.put("ip", "mc.example.com");
        first.put("name", "Друзья");
        first.put("icon", "iVBORw0KGgo=");
        first.put("acceptTextures", (byte) 1);
        first.put("futureTag", new int[] {7});
        var hidden = new LinkedHashMap<String, Object>();
        hidden.put("ip", "10.0.0.5:25570");
        hidden.put("name", "Скрытый");
        hidden.put("hidden", (byte) 1);
        var root = new LinkedHashMap<String, Object>();
        root.put("servers", new Nbt.ListTag(Nbt.COMPOUND, List.of(first, hidden)));
        root.put("unknownRoot", "keep me");
        Path folder = Files.createDirectories(temp.resolve("build"));
        Files.write(folder.resolve("servers.dat"), Nbt.write(new Nbt.Root("", root, false)));
        return folder;
    }

    @Test
    void readsServersWithIconsAndHiddenFlags() throws Exception {
        var servers = ServerList.read(vanillaFile());
        assertEquals(2, servers.size());
        assertEquals(new ServerList.Server(0, "Друзья", "mc.example.com", "iVBORw0KGgo=", false), servers.get(0));
        assertTrue(servers.get(1).hidden());
        assertNull(servers.get(1).icon());
        assertEquals(List.of(), ServerList.read(temp.resolve("empty")));
    }

    @Test
    void editsKeepUnknownTagsAndWriteAtomically() throws Exception {
        Path folder = vanillaFile();
        ServerList.add(folder, "Новый", "play.example.org:25566");
        ServerList.edit(folder, 0, "mc.example.com", "Друзья 2", "mc.example.com");
        var root = Nbt.read(Files.readAllBytes(folder.resolve("servers.dat")));
        assertFalse(root.gzip());
        assertEquals("keep me", root.value().get("unknownRoot"));
        @SuppressWarnings("unchecked") var first = (Map<String, Object>) ((Nbt.ListTag) root.value().get("servers")).values().get(0);
        assertArrayEquals(new int[] {7}, (int[]) first.get("futureTag"));
        assertEquals((byte) 1, first.get("acceptTextures"));
        assertEquals("iVBORw0KGgo=", first.get("icon"), "same address keeps its icon");
        var servers = ServerList.read(folder);
        assertEquals(List.of("Друзья 2", "Скрытый", "Новый"), servers.stream().map(ServerList.Server::name).toList());
        assertTrue(Files.exists(folder.resolve("servers.dat_old")));
        try (var files = Files.list(folder)) { assertTrue(files.noneMatch(path -> path.toString().endsWith(".tmp"))); }
        // A new address drops the icon of the old server.
        ServerList.edit(folder, 0, "mc.example.com", "Друзья 2", "other.example.com");
        assertNull(ServerList.read(folder).get(0).icon());
    }

    @Test
    void removeAndIconChecksTheExpectedEntry() throws Exception {
        Path folder = vanillaFile();
        assertThrows(java.io.IOException.class, () -> ServerList.remove(folder, 0, "changed.example.com"));
        assertThrows(java.io.IOException.class, () -> ServerList.remove(folder, 5, "mc.example.com"));
        ServerList.remove(folder, 0, "mc.example.com");
        assertEquals(List.of("Скрытый"), ServerList.read(folder).stream().map(ServerList.Server::name).toList());
        ServerList.setIcon(folder, 0, "10.0.0.5:25570", "QUJD");
        assertEquals("QUJD", ServerList.read(folder).get(0).icon());
        ServerList.setIcon(folder, 0, "10.0.0.5:25570", "not base64!");
        assertEquals("QUJD", ServerList.read(folder).get(0).icon());
    }

    @Test
    void createsTheFileAndKeepsGzipCompression() throws Exception {
        Path folder = Files.createDirectories(temp.resolve("fresh"));
        ServerList.add(folder, "", "localhost");
        assertEquals("Сервер Minecraft", ServerList.read(folder).get(0).name());
        var gz = new LinkedHashMap<String, Object>();
        gz.put("servers", new Nbt.ListTag(Nbt.END, List.of()));
        Files.write(folder.resolve("servers.dat"), Nbt.write(new Nbt.Root("", gz, true)));
        ServerList.add(folder, "A", "a.example.com");
        assertTrue(Nbt.read(Files.readAllBytes(folder.resolve("servers.dat"))).gzip());
        assertEquals(1, ServerList.read(folder).size());
        assertThrows(IllegalArgumentException.class, () -> ServerList.add(folder, "B", "bad host"));
    }

    @Test
    void symlinkedFileIsRefused() throws Exception {
        Path folder = Files.createDirectories(temp.resolve("link"));
        Path outside = Files.write(temp.resolve("outside.dat"), new byte[0]);
        try { Files.createSymbolicLink(folder.resolve("servers.dat"), outside); }
        catch (UnsupportedOperationException | java.io.IOException e) { return; }
        assertThrows(java.io.IOException.class, () -> ServerList.read(folder));
        assertThrows(java.io.IOException.class, () -> ServerList.add(folder, "x", "x.example.com"));
    }

    @Test
    void addressParsing() {
        assertEquals(new ServerList.Address("mc.example.com", 25565, false), ServerList.Address.parse("mc.example.com"));
        assertEquals(new ServerList.Address("mc.example.com", 25570, true), ServerList.Address.parse(" mc.example.com:25570 "));
        assertEquals(new ServerList.Address("::1", 25565, true), ServerList.Address.parse("[::1]:25565"));
        assertEquals(new ServerList.Address("::1", 25565, false), ServerList.Address.parse("[::1]"));
        assertEquals(new ServerList.Address("2001:db8::1", 25565, false), ServerList.Address.parse("2001:db8::1"));
        assertEquals("[::1]:25565", ServerList.Address.parse("[::1]").joinText());
        assertEquals("127.0.0.1:25565", ServerList.Address.parse("127.0.0.1").joinText());
        for (String bad : new String[] {"", "host:0", "host:70000", "host:abc", "a b", "http://x", "user@host", ".host", "[::1]:x"})
            assertThrows(IllegalArgumentException.class, () -> ServerList.Address.parse(bad), bad);
    }

    @Test
    void quickJoinArgumentsByVersion() {
        assertEquals(List.of("--quickPlayMultiplayer", "mc.example.com:25570"), ServerList.quickJoinArguments("1.21.1", "mc.example.com:25570"));
        assertEquals(List.of("--quickPlayMultiplayer", "mc.example.com"), ServerList.quickJoinArguments("1.20", "mc.example.com"));
        assertEquals(List.of("--quickPlayMultiplayer", "x.org"), ServerList.quickJoinArguments("1.20-pre1", "x.org"));
        assertEquals(List.of("--quickPlayMultiplayer", "x.org"), ServerList.quickJoinArguments("24w14a", "x.org"));
        assertEquals(List.of("--server", "x.org", "--port", "25565"), ServerList.quickJoinArguments("23w13a", "x.org"));
        assertEquals(List.of("--server", "mc.example.com", "--port", "25570"), ServerList.quickJoinArguments("1.19.4", "mc.example.com:25570"));
        assertEquals(List.of("--server", "::1", "--port", "25565"), ServerList.quickJoinArguments("1.12.2", "[::1]"));
    }
}

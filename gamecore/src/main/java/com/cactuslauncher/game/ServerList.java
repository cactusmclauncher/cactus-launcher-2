package com.cactuslauncher.game;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The build's multiplayer list, {@code servers.dat} in the game folder, shared with the in-game
 * "Multiplayer" screen. Unknown tags of the file and of each server are kept when it is written back.
 */
public final class ServerList {
    private ServerList() {}

    /** [index] is the position in the file (hidden entries included); [icon] is base64 PNG or null. */
    public record Server(int index, String name, String ip, String icon, boolean hidden) {}

    public static Path file(Path buildFolder) throws IOException {
        Path file = buildFolder.resolve("servers.dat");
        if (Files.isSymbolicLink(buildFolder) || Files.isSymbolicLink(file)) throw new IOException("servers.dat не должен быть символической ссылкой.");
        return file;
    }

    public static List<Server> read(Path buildFolder) throws IOException {
        Nbt.Root root = load(file(buildFolder));
        var result = new ArrayList<Server>();
        List<Object> entries = entries(root);
        for (int i = 0; i < entries.size(); i++) {
            if (!(entries.get(i) instanceof Map<?, ?> server)) continue;
            result.add(new Server(i, string(server.get("name"), "Сервер Minecraft"), string(server.get("ip"), ""),
                    server.get("icon") instanceof String icon && !icon.isBlank() ? icon : null,
                    server.get("hidden") instanceof Byte hidden && hidden != 0));
        }
        return List.copyOf(result);
    }

    /** Appends a server, as "Добавить сервер" in the game does. */
    public static void add(Path buildFolder, String name, String ip) throws IOException {
        String address = validAddress(ip);
        Path file = file(buildFolder);
        Nbt.Root root = load(file);
        var server = new LinkedHashMap<String, Object>();
        server.put("ip", address);
        server.put("name", validName(name));
        entries(root).add(server);
        save(file, root);
    }

    /** Changes name and address of the entry at [index]; [expectedIp] guards against the game having changed the file. */
    public static void edit(Path buildFolder, int index, String expectedIp, String name, String ip) throws IOException {
        String address = validAddress(ip);
        Path file = file(buildFolder);
        Nbt.Root root = load(file);
        Map<String, Object> server = entry(root, index, expectedIp);
        boolean addressChanged = !address.equals(server.get("ip"));
        server.put("name", validName(name));
        server.put("ip", address);
        // The old icon belongs to the old server.
        if (addressChanged) server.remove("icon");
        save(file, root);
    }

    public static void remove(Path buildFolder, int index, String expectedIp) throws IOException {
        Path file = file(buildFolder);
        Nbt.Root root = load(file);
        entry(root, index, expectedIp);
        entries(root).remove(index);
        save(file, root);
    }

    /** Stores the favicon reported by the server (base64 PNG without the data: prefix). */
    public static void setIcon(Path buildFolder, int index, String expectedIp, String icon) throws IOException {
        if (icon == null || icon.length() > 1_000_000 || !icon.matches("[A-Za-z0-9+/=\\r\\n]+")) return;
        Path file = file(buildFolder);
        Nbt.Root root = load(file);
        Map<String, Object> server = entry(root, index, expectedIp);
        if (icon.equals(server.get("icon"))) return;
        server.put("icon", icon);
        save(file, root);
    }

    private static Nbt.Root load(Path file) throws IOException {
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            var value = new LinkedHashMap<String, Object>();
            value.put("servers", new Nbt.ListTag(Nbt.COMPOUND, List.of()));
            return new Nbt.Root("", value, false);
        }
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new IOException("servers.dat не является файлом.");
        if (Files.size(file) > 16L << 20) throw new IOException("servers.dat слишком большой.");
        return Nbt.read(Files.readAllBytes(file));
    }

    private static List<Object> entries(Nbt.Root root) throws IOException {
        Object list = root.value().get("servers");
        if (list == null) {
            var created = new Nbt.ListTag(Nbt.COMPOUND, List.of());
            root.value().put("servers", created);
            return created.values();
        }
        if (!(list instanceof Nbt.ListTag tag) || (tag.elementType() != Nbt.COMPOUND && !tag.values().isEmpty()))
            throw new IOException("servers.dat повреждён: нет списка серверов.");
        if (tag.elementType() != Nbt.COMPOUND) {
            var fixed = new Nbt.ListTag(Nbt.COMPOUND, List.of());
            root.value().put("servers", fixed);
            return fixed.values();
        }
        return tag.values();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> entry(Nbt.Root root, int index, String expectedIp) throws IOException {
        List<Object> entries = entries(root);
        if (index < 0 || index >= entries.size() || !(entries.get(index) instanceof Map<?, ?> server)
                || !string(server.get("ip"), "").equals(expectedIp))
            throw new IOException("Список серверов изменился в игре. Обновите его и повторите.");
        return (Map<String, Object>) server;
    }

    private static void save(Path file, Nbt.Root root) throws IOException {
        byte[] bytes = Nbt.write(root);
        Path parent = file.getParent();
        Files.createDirectories(parent);
        Path temp = Files.createTempFile(parent, "servers-", ".tmp");
        try {
            Files.write(temp, bytes);
            // The game keeps servers.dat_old as its own backup; do the same.
            if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                Path old = parent.resolve("servers.dat_old");
                if (!Files.isSymbolicLink(old)) Files.copy(file, old, StandardCopyOption.REPLACE_EXISTING);
            }
            try { Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temp); }
    }

    private static String string(Object value, String fallback) {
        return value instanceof String text ? text : fallback;
    }

    public static String validName(String name) {
        String value = name == null ? "" : name.strip();
        if (value.isEmpty()) value = "Сервер Minecraft";
        if (value.length() > 64) throw new IllegalArgumentException("Название сервера слишком длинное.");
        return value;
    }

    public static String validAddress(String ip) {
        String value = ip == null ? "" : ip.strip();
        Address.parse(value); // throws with a readable message
        return value;
    }

    /** host, default port 25565 when not given ([explicitPort] false then, so SRV records may apply). */
    public record Address(String host, int port, boolean explicitPort) {
        private static final Pattern BRACKETED = Pattern.compile("\\[([0-9A-Fa-f:.%]+)](?::(\\d{1,5}))?");
        private static final Pattern HOST = Pattern.compile("[A-Za-z0-9._-]{1,253}");

        public static Address parse(String text) {
            String value = text == null ? "" : text.strip();
            if (value.isEmpty()) throw new IllegalArgumentException("Укажите адрес сервера.");
            if (value.length() > 260 || value.chars().anyMatch(c -> c <= ' ' || c == '/' || c == '@'))
                throw new IllegalArgumentException("Некорректный адрес сервера.");
            Matcher bracketed = BRACKETED.matcher(value);
            if (bracketed.matches()) return new Address(bracketed.group(1), port(bracketed.group(2)), bracketed.group(2) != null);
            long colons = value.chars().filter(c -> c == ':').count();
            if (colons > 1) {
                // A bare IPv6 address has no port.
                if (!value.matches("[0-9A-Fa-f:.%]+")) throw new IllegalArgumentException("Некорректный адрес сервера.");
                return new Address(value, 25565, false);
            }
            String host = value;
            String port = null;
            if (colons == 1) {
                int colon = value.indexOf(':');
                host = value.substring(0, colon);
                port = value.substring(colon + 1);
            }
            if (!HOST.matcher(host).matches() || host.startsWith(".") || host.startsWith("-"))
                throw new IllegalArgumentException("Некорректный адрес сервера.");
            return new Address(host, port(port), port != null);
        }

        private static int port(String text) {
            if (text == null) return 25565;
            int port;
            try { port = Integer.parseInt(text); } catch (NumberFormatException e) { throw new IllegalArgumentException("Некорректный порт сервера."); }
            if (port < 1 || port > 65535) throw new IllegalArgumentException("Порт должен быть от 1 до 65535.");
            return port;
        }

        boolean literalIp() { return host.contains(":") || host.matches("\\d{1,3}(\\.\\d{1,3}){3}"); }

        /** "host:port", with brackets for IPv6, as Minecraft expects on the command line. */
        public String joinText() { return (host.contains(":") ? "[" + host + "]" : host) + ":" + port; }
    }

    /**
     * Game arguments that join [address] right after start: 1.20+ (and snapshots since 23w14a) use
     * Quick Play, older versions the legacy --server/--port.
     */
    public static List<String> quickJoinArguments(String minecraftVersion, String address) {
        Address parsed = Address.parse(address);
        if (supportsQuickPlay(minecraftVersion)) return List.of("--quickPlayMultiplayer", address.strip());
        return List.of("--server", parsed.host(), "--port", Integer.toString(parsed.port()));
    }

    static boolean supportsQuickPlay(String version) {
        if (version == null) return false;
        Matcher release = Pattern.compile("1\\.(\\d+)(?:\\.\\d+)?(?:[-_ ].*)?").matcher(version.strip());
        if (release.matches()) return Integer.parseInt(release.group(1)) >= 20;
        Matcher snapshot = Pattern.compile("(\\d{2})w(\\d{2})[a-z]").matcher(version.strip());
        if (snapshot.matches()) {
            int year = Integer.parseInt(snapshot.group(1)), week = Integer.parseInt(snapshot.group(2));
            return year > 23 || (year == 23 && week >= 14);
        }
        // Unknown naming (e.g. 2.x or future schemes): new versions keep Quick Play.
        return !version.startsWith("1.") && !version.matches("(a|b|c|rd|inf).*");
    }
}

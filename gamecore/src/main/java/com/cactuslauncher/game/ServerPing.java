package com.cactuslauncher.game;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Hashtable;
import java.util.function.Function;

/**
 * Server List Ping (Minecraft 1.7+): handshake, status request with its JSON, then ping/pong for the
 * latency. Addresses without a port use the _minecraft._tcp SRV record when there is one.
 */
public final class ServerPing {
    private ServerPing() {}

    public record Status(String motd, int online, int max, String version, long pingMillis, String favicon) {}

    private static final int MAX_PACKET = 2 << 20;

    /** Pings [address] ("host", "host:port", "[::1]:25565") within about [timeoutMillis] per step. */
    public static Status ping(String address, int timeoutMillis) throws IOException {
        return ping(ServerList.Address.parse(address), timeoutMillis, ServerPing::lookupSrv);
    }

    static Status ping(ServerList.Address address, int timeoutMillis, Function<String, ServerList.Address> srv) throws IOException {
        String host = address.host();
        int port = address.port();
        if (!address.explicitPort() && !address.literalIp()) {
            ServerList.Address target = srv.apply(host);
            if (target != null) { host = target.host(); port = target.port(); }
        }
        try (var socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeoutMillis);
            socket.setSoTimeout(timeoutMillis);
            var output = new DataOutputStream(socket.getOutputStream());
            var input = new DataInputStream(socket.getInputStream());
            // The handshake names the address the player typed, as the game does.
            output.write(packet(0x00, handshake(address.host(), address.port())));
            output.write(packet(0x00, new byte[0]));
            output.flush();
            long started = System.nanoTime();
            byte[] status = readPacket(input);
            long statusMillis = (System.nanoTime() - started) / 1_000_000;
            var body = new DataInputStream(new java.io.ByteArrayInputStream(status));
            if (readVarInt(body) != 0x00) throw new IOException("Сервер ответил не статусом.");
            String json = readString(body, MAX_PACKET);
            long ping = statusMillis;
            try {
                long token = System.currentTimeMillis();
                var payload = new ByteArrayOutputStream();
                new DataOutputStream(payload).writeLong(token);
                long sent = System.nanoTime();
                output.write(packet(0x01, payload.toByteArray()));
                output.flush();
                byte[] pong = readPacket(input);
                var pongBody = new DataInputStream(new java.io.ByteArrayInputStream(pong));
                if (readVarInt(pongBody) == 0x01 && pongBody.readLong() == token) ping = (System.nanoTime() - sent) / 1_000_000;
            } catch (IOException ignored) {
                // Some servers close the connection after the status; the status round trip is the latency then.
            }
            return parseStatus(json, ping);
        }
    }

    static byte[] handshake(String host, int port) throws IOException {
        var bytes = new ByteArrayOutputStream();
        var output = new DataOutputStream(bytes);
        writeVarInt(output, -1); // protocol version: "unknown", accepted for status
        writeString(output, host);
        output.writeShort(port);
        writeVarInt(output, 1); // next state: status
        return bytes.toByteArray();
    }

    static byte[] packet(int id, byte[] data) throws IOException {
        var body = new ByteArrayOutputStream();
        writeVarInt(body, id);
        body.write(data);
        var framed = new ByteArrayOutputStream();
        writeVarInt(framed, body.size());
        body.writeTo(framed);
        return framed.toByteArray();
    }

    static byte[] readPacket(DataInputStream input) throws IOException {
        int length = readVarInt(input);
        if (length <= 0 || length > MAX_PACKET) throw new IOException("Сервер прислал пакет неверной длины.");
        byte[] data = new byte[length];
        input.readFully(data);
        return data;
    }

    static void writeVarInt(OutputStream output, int value) throws IOException {
        while (true) {
            if ((value & ~0x7F) == 0) { output.write(value); return; }
            output.write((value & 0x7F) | 0x80);
            value >>>= 7;
        }
    }

    static int readVarInt(InputStream input) throws IOException {
        int value = 0;
        for (int position = 0; ; position += 7) {
            if (position >= 35) throw new IOException("VarInt слишком длинный.");
            int read = input.read();
            if (read < 0) throw new EOFException("Соединение закрыто.");
            value |= (read & 0x7F) << position;
            if ((read & 0x80) == 0) return value;
        }
    }

    private static void writeString(DataOutputStream output, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        writeVarInt(output, bytes.length);
        output.write(bytes);
    }

    private static String readString(DataInputStream input, int limit) throws IOException {
        int length = readVarInt(input);
        if (length < 0 || length > limit) throw new IOException("Слишком длинная строка в ответе сервера.");
        byte[] bytes = new byte[length];
        input.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /** Status JSON to plain values; the favicon keeps only the base64 part of "data:image/png;base64,…". */
    static Status parseStatus(String json, long pingMillis) throws IOException {
        JsonObject root;
        try { root = JsonParser.parseString(json).getAsJsonObject(); }
        catch (RuntimeException e) { throw new IOException("Сервер прислал некорректный статус.", e); }
        String version = "";
        if (root.get("version") instanceof JsonObject versionObject && versionObject.get("name") != null && versionObject.get("name").isJsonPrimitive())
            version = stripFormatting(versionObject.get("name").getAsString());
        int online = 0, max = 0;
        if (root.get("players") instanceof JsonObject players) {
            online = intOf(players.get("online"));
            max = intOf(players.get("max"));
        }
        String motd = plainText(root.get("description"));
        String favicon = null;
        if (root.get("favicon") != null && root.get("favicon").isJsonPrimitive()) {
            String value = root.get("favicon").getAsString();
            String prefix = "data:image/png;base64,";
            if (value.startsWith(prefix)) favicon = value.substring(prefix.length()).replace("\n", "");
        }
        return new Status(motd, online, max, version, pingMillis, favicon);
    }

    private static int intOf(JsonElement value) {
        try { return value != null && value.isJsonPrimitive() ? value.getAsInt() : 0; } catch (RuntimeException e) { return 0; }
    }

    /** Chat component (string, object with text/extra/translate, or array) as plain text without § codes. */
    static String plainText(JsonElement element) {
        var text = new StringBuilder();
        appendText(element, text, 0);
        String plain = stripFormatting(text.toString());
        // MOTDs are centred with runs of spaces; plain text needs single spaces and trimmed lines.
        var lines = plain.lines().map(line -> line.replaceAll("[ \\t]{2,}", " ").strip()).filter(line -> !line.isEmpty()).toList();
        return String.join("\n", lines);
    }

    private static void appendText(JsonElement element, StringBuilder text, int depth) {
        if (element == null || element.isJsonNull() || depth > 32 || text.length() > 4096) return;
        if (element.isJsonPrimitive()) { text.append(element.getAsString()); return; }
        if (element instanceof JsonArray array) { for (JsonElement item : array) appendText(item, text, depth + 1); return; }
        if (element instanceof JsonObject object) {
            if (object.get("text") != null && object.get("text").isJsonPrimitive()) text.append(object.get("text").getAsString());
            else if (object.get("translate") != null && object.get("translate").isJsonPrimitive()) text.append(object.get("translate").getAsString());
            if (object.get("extra") instanceof JsonArray extra) for (JsonElement item : extra) appendText(item, text, depth + 1);
        }
    }

    /** Removes § formatting codes (colours, styles, reset). */
    static String stripFormatting(String text) {
        return text.replaceAll("§.?", "");
    }

    /** _minecraft._tcp SRV lookup through the JDK DNS provider; null when there is none or DNS fails. */
    static ServerList.Address lookupSrv(String host) {
        var environment = new Hashtable<String, String>();
        environment.put("java.naming.factory.initial", "com.sun.jndi.dns.DnsContextFactory");
        environment.put("java.naming.provider.url", "dns:");
        environment.put("com.sun.jndi.dns.timeout.initial", "1500");
        environment.put("com.sun.jndi.dns.timeout.retries", "1");
        try {
            var context = new javax.naming.directory.InitialDirContext(environment);
            try {
                var attributes = context.getAttributes("_minecraft._tcp." + host, new String[] {"SRV"});
                var srv = attributes.get("SRV");
                if (srv == null || srv.size() == 0) return null;
                return parseSrv(srv.get(0).toString());
            } finally { context.close(); }
        } catch (javax.naming.NamingException | RuntimeException e) {
            return null;
        }
    }

    /** "priority weight port target." to an address. */
    static ServerList.Address parseSrv(String record) {
        String[] parts = record.strip().split("\\s+");
        if (parts.length != 4) return null;
        try {
            int port = Integer.parseInt(parts[2]);
            String target = parts[3].endsWith(".") ? parts[3].substring(0, parts[3].length() - 1) : parts[3];
            if (port < 1 || port > 65535 || target.isEmpty()) return null;
            return new ServerList.Address(target, port, true);
        } catch (NumberFormatException e) { return null; }
    }
}

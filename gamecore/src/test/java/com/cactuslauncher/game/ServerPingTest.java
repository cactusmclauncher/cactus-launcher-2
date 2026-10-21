package com.cactuslauncher.game;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ServerPingTest {
    @Test
    void varIntEncoding() throws Exception {
        int[] values = {0, 1, 127, 128, 255, 25565, 2097151, Integer.MAX_VALUE, -1};
        String[] hex = {"00", "01", "7f", "8001", "ff01", "ddc701", "ffff7f", "ffffffff07", "ffffffff0f"};
        for (int i = 0; i < values.length; i++) {
            var bytes = new ByteArrayOutputStream();
            ServerPing.writeVarInt(bytes, values[i]);
            assertEquals(hex[i], java.util.HexFormat.of().formatHex(bytes.toByteArray()), "value " + values[i]);
            assertEquals(values[i], ServerPing.readVarInt(new ByteArrayInputStream(bytes.toByteArray())));
        }
        assertThrows(java.io.IOException.class, () -> ServerPing.readVarInt(new ByteArrayInputStream(new byte[] {-1, -1, -1, -1, -1, -1})));
    }

    @Test
    void motdAndStatusParsing() throws Exception {
        String json = """
                {"version":{"name":"§aPaper 1.21.1","protocol":767},
                 "players":{"max":100,"online":7,"sample":[]},
                 "description":{"text":"§6Добро §lпожаловать","extra":[{"text":"\\n  §7Выживание  ","bold":true},{"translate":"!"}]},
                 "favicon":"data:image/png;base64,iVBORw0K\\nGgo="}
                """;
        var status = ServerPing.parseStatus(json, 42);
        assertEquals("Добро пожаловать\nВыживание !", status.motd());
        assertEquals(7, status.online());
        assertEquals(100, status.max());
        assertEquals("Paper 1.21.1", status.version());
        assertEquals(42, status.pingMillis());
        assertEquals("iVBORw0KGgo=", status.favicon());
        var plain = ServerPing.parseStatus("{\"description\":\"§cA §rsimple MOTD\",\"players\":{}}", 1);
        assertEquals("A simple MOTD", plain.motd());
        assertNull(plain.favicon());
        assertEquals("", plain.version());
        assertEquals("x", ServerPing.plainText(com.google.gson.JsonParser.parseString("[\"§kx\"]")));
        assertThrows(java.io.IOException.class, () -> ServerPing.parseStatus("not json", 1));
    }

    @Test
    void srvRecords() {
        assertEquals(new ServerList.Address("node1.example.com", 25600, true), ServerPing.parseSrv("0 5 25600 node1.example.com."));
        assertNull(ServerPing.parseSrv("garbage"));
        assertNull(ServerPing.parseSrv("0 5 0 x."));
    }

    /** A local fake server checks the handshake bytes and answers status and pong. */
    @Test
    void pingsAFakeServer() throws Exception {
        try (var server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            var handshake = new AtomicReference<byte[]>();
            var failure = new AtomicReference<Throwable>();
            var thread = new Thread(() -> {
                try (var socket = server.accept()) {
                    var input = new DataInputStream(socket.getInputStream());
                    var output = new DataOutputStream(socket.getOutputStream());
                    handshake.set(ServerPing.readPacket(input));
                    byte[] request = ServerPing.readPacket(input);
                    assertArrayEquals(new byte[] {0}, request);
                    var body = new ByteArrayOutputStream();
                    byte[] json = "{\"version\":{\"name\":\"1.21.1\"},\"players\":{\"online\":3,\"max\":20},\"description\":\"Тест\"}".getBytes(StandardCharsets.UTF_8);
                    ServerPing.writeVarInt(body, json.length);
                    body.write(json);
                    output.write(ServerPing.packet(0, body.toByteArray()));
                    byte[] ping = ServerPing.readPacket(input);
                    assertEquals(1, ping[0]);
                    output.write(ServerPing.packet(1, java.util.Arrays.copyOfRange(ping, 1, 9)));
                    output.flush();
                } catch (Throwable e) { failure.set(e); }
            });
            thread.start();
            int port = server.getLocalPort();
            var status = ServerPing.ping(new ServerList.Address("127.0.0.1", port, true), 3000, host -> { throw new AssertionError("no SRV for ports"); });
            thread.join(5000);
            if (failure.get() != null) throw new AssertionError(failure.get());
            assertEquals("Тест", status.motd());
            assertEquals(3, status.online());
            assertEquals(20, status.max());
            assertTrue(status.pingMillis() >= 0);
            // Handshake: id 0, protocol -1, host, port, next state 1.
            var expected = new ByteArrayOutputStream();
            expected.write(0);
            expected.write(ServerPing.handshake("127.0.0.1", port));
            assertArrayEquals(expected.toByteArray(), handshake.get());
            assertEquals("00ffffffff0f093132372e302e302e31", java.util.HexFormat.of().formatHex(handshake.get(), 0, 16));
        }
    }

    @Test
    void silentServerTimesOut() throws Exception {
        try (var server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            var thread = new Thread(() -> { try (var socket = server.accept()) { Thread.sleep(2000); } catch (Exception ignored) { } });
            thread.start();
            long started = System.nanoTime();
            assertThrows(java.io.IOException.class, () -> ServerPing.ping(new ServerList.Address("127.0.0.1", server.getLocalPort(), true), 300, host -> null));
            assertTrue((System.nanoTime() - started) / 1_000_000 < 1800);
            thread.interrupt();
        }
    }

    @Test
    void srvIsUsedOnlyWithoutPort() throws Exception {
        try (var server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            var asked = new AtomicReference<String>();
            var thread = new Thread(() -> { try (var socket = server.accept()) { } catch (Exception ignored) { } });
            thread.start();
            assertThrows(java.io.IOException.class, () -> ServerPing.ping(new ServerList.Address("mc.example.test", 25565, false), 1000, host -> {
                asked.set(host);
                return new ServerList.Address("127.0.0.1", server.getLocalPort(), true);
            }));
            assertEquals("mc.example.test", asked.get());
            thread.join(2000);
        }
    }
}

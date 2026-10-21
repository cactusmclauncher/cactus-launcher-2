package com.cactuslauncher.game;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class NbtTest {
    static Map<String, Object> everyTag() {
        var nested = new LinkedHashMap<String, Object>();
        nested.put("deep", new Nbt.ListTag(Nbt.LIST, List.of(new Nbt.ListTag(Nbt.STRING, List.of("a", "ü✓")))));
        var root = new LinkedHashMap<String, Object>();
        root.put("byte", (byte) -5);
        root.put("short", (short) 1234);
        root.put("int", 123456789);
        root.put("long", Long.MIN_VALUE);
        root.put("float", 1.5f);
        root.put("double", -2.25);
        root.put("bytes", new byte[] {1, 2, 3});
        root.put("string", "Привет, мир");
        root.put("emptyList", new Nbt.ListTag(Nbt.INT, List.of()));
        root.put("compound", nested);
        root.put("ints", new int[] {1, -1});
        root.put("longs", new long[] {Long.MAX_VALUE});
        return root;
    }

    @Test
    void roundTripKeepsEveryTagTypeAndOrder() throws Exception {
        for (boolean gzip : new boolean[] {false, true}) {
            var root = new Nbt.Root("", everyTag(), gzip);
            byte[] bytes = Nbt.write(root);
            if (gzip) assertEquals(0x1f, bytes[0] & 0xff);
            var read = Nbt.read(bytes);
            assertEquals(gzip, read.gzip());
            assertArrayEquals(bytes, Nbt.write(read), "byte-identical after a round trip");
            assertEquals(List.copyOf(everyTag().keySet()), List.copyOf(read.value().keySet()));
            assertEquals("Привет, мир", read.value().get("string"));
            assertEquals(Nbt.INT, ((Nbt.ListTag) read.value().get("emptyList")).elementType());
        }
    }

    @Test
    void rejectsBrokenData() {
        assertThrows(java.io.IOException.class, () -> Nbt.read(new byte[] {8, 0, 0}));
        assertThrows(java.io.IOException.class, () -> Nbt.read(new byte[] {10, 0, 0, 1, 0, 1}));
        // A byte array claiming 2 GB must not allocate it.
        assertThrows(java.io.IOException.class, () -> Nbt.read(new byte[] {10, 0, 0, 7, 0, 1, 'a', 0x7f, (byte) 0xff, (byte) 0xff, (byte) 0xff}));
        // Unknown tag type.
        assertThrows(java.io.IOException.class, () -> Nbt.read(new byte[] {10, 0, 0, 42, 0, 1, 'a', 0}));
        var bad = new LinkedHashMap<String, Object>();
        bad.put("x", new Object());
        assertThrows(java.io.IOException.class, () -> Nbt.write(new Nbt.Root("", bad, false)));
    }

    @Test
    void deepNestingIsRefused() throws Exception {
        var bytes = new ByteArrayOutputStream();
        bytes.write(new byte[] {10, 0, 0});
        for (int i = 0; i < 600; i++) bytes.write(new byte[] {10, 0, 1, 'a'});
        assertThrows(java.io.IOException.class, () -> Nbt.read(bytes.toByteArray()));
    }
}

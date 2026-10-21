package com.cactuslauncher.game;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInput;
import java.io.DataInputStream;
import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Minimal Minecraft NBT (Java edition, big-endian) reader and writer. Tags map to Java types so unknown
 * data survives a round trip: Byte, Short, Integer, Long, Float, Double, byte[], String, {@link ListTag},
 * compound as {@code LinkedHashMap<String, Object>}, int[] and long[].
 */
public final class Nbt {
    private Nbt() {}

    /** A list tag keeps its element type, so an empty list is written back unchanged. */
    public record ListTag(int elementType, List<Object> values) {
        public ListTag { values = new ArrayList<>(values); }
    }

    /** The named root compound and whether the file was gzip-compressed. */
    public record Root(String name, Map<String, Object> value, boolean gzip) {}

    static final int END = 0, BYTE = 1, SHORT = 2, INT = 3, LONG = 4, FLOAT = 5, DOUBLE = 6, BYTE_ARRAY = 7,
            STRING = 8, LIST = 9, COMPOUND = 10, INT_ARRAY = 11, LONG_ARRAY = 12;
    private static final int MAX_DEPTH = 512;

    public static Root read(byte[] bytes) throws IOException {
        boolean gzip = bytes.length >= 2 && (bytes[0] & 0xff) == 0x1f && (bytes[1] & 0xff) == 0x8b;
        var raw = gzip ? new GZIPInputStream(new ByteArrayInputStream(bytes)) : new ByteArrayInputStream(bytes);
        // Limits come from the decompressed size; a gzip bomb stops at 32 MB.
        byte[] data = readLimited(new BufferedInputStream(raw), 32L << 20);
        var input = new Reader(new DataInputStream(new ByteArrayInputStream(data)));
        int type = input.in.readUnsignedByte();
        if (type != COMPOUND) throw new IOException("NBT: корневой тег должен быть compound.");
        String name = input.string();
        @SuppressWarnings("unchecked") var value = (Map<String, Object>) input.payload(COMPOUND, 0);
        return new Root(name, value, gzip);
    }

    public static byte[] write(Root root) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var output = new DataOutputStream(root.gzip() ? new GZIPOutputStream(bytes) : bytes)) {
            output.writeByte(COMPOUND);
            output.writeUTF(root.name());
            writePayload(output, root.value(), 0);
        }
        return bytes.toByteArray();
    }

    static int typeOf(Object value) throws IOException {
        if (value instanceof Byte) return BYTE;
        if (value instanceof Short) return SHORT;
        if (value instanceof Integer) return INT;
        if (value instanceof Long) return LONG;
        if (value instanceof Float) return FLOAT;
        if (value instanceof Double) return DOUBLE;
        if (value instanceof byte[]) return BYTE_ARRAY;
        if (value instanceof String) return STRING;
        if (value instanceof ListTag) return LIST;
        if (value instanceof Map<?, ?>) return COMPOUND;
        if (value instanceof int[]) return INT_ARRAY;
        if (value instanceof long[]) return LONG_ARRAY;
        throw new IOException("NBT: неподдерживаемое значение " + (value == null ? "null" : value.getClass().getSimpleName()));
    }

    private static void writePayload(DataOutput output, Object value, int depth) throws IOException {
        if (depth > MAX_DEPTH) throw new IOException("NBT: слишком глубокая вложенность.");
        switch (typeOf(value)) {
            case BYTE -> output.writeByte((Byte) value);
            case SHORT -> output.writeShort((Short) value);
            case INT -> output.writeInt((Integer) value);
            case LONG -> output.writeLong((Long) value);
            case FLOAT -> output.writeFloat((Float) value);
            case DOUBLE -> output.writeDouble((Double) value);
            case BYTE_ARRAY -> { byte[] array = (byte[]) value; output.writeInt(array.length); output.write(array); }
            case STRING -> output.writeUTF((String) value);
            case LIST -> {
                ListTag list = (ListTag) value;
                int type = list.values().isEmpty() ? list.elementType() : typeOf(list.values().get(0));
                output.writeByte(type);
                output.writeInt(list.values().size());
                for (Object element : list.values()) {
                    if (typeOf(element) != type) throw new IOException("NBT: элементы списка разных типов.");
                    writePayload(output, element, depth + 1);
                }
            }
            case COMPOUND -> {
                for (var entry : ((Map<?, ?>) value).entrySet()) {
                    output.writeByte(typeOf(entry.getValue()));
                    output.writeUTF((String) entry.getKey());
                    writePayload(output, entry.getValue(), depth + 1);
                }
                output.writeByte(END);
            }
            case INT_ARRAY -> { int[] array = (int[]) value; output.writeInt(array.length); for (int item : array) output.writeInt(item); }
            case LONG_ARRAY -> { long[] array = (long[]) value; output.writeInt(array.length); for (long item : array) output.writeLong(item); }
            default -> throw new IOException("NBT: неизвестный тип.");
        }
    }

    private static final class Reader {
        final DataInputStream in;

        Reader(DataInputStream in) { this.in = in; }

        /** Array sizes can never exceed the bytes left in the file. */
        int count(int elementSize) throws IOException {
            int count = in.readInt();
            if (count < 0 || (long) count * elementSize > in.available()) throw new IOException("NBT: некорректная длина.");
            return count;
        }

        String string() throws IOException { return ((DataInput) in).readUTF(); }

        Object payload(int type, int depth) throws IOException {
            if (depth > MAX_DEPTH) throw new IOException("NBT: слишком глубокая вложенность.");
            return switch (type) {
                case BYTE -> in.readByte();
                case SHORT -> in.readShort();
                case INT -> in.readInt();
                case LONG -> in.readLong();
                case FLOAT -> in.readFloat();
                case DOUBLE -> in.readDouble();
                case BYTE_ARRAY -> { byte[] array = new byte[count(1)]; in.readFully(array); yield array; }
                case STRING -> string();
                case LIST -> {
                    int elementType = in.readUnsignedByte();
                    int size = count(elementType == END ? 0 : 1);
                    if (elementType == END && size > 0) throw new IOException("NBT: список без типа.");
                    if (elementType > LONG_ARRAY) throw new IOException("NBT: неизвестный тип " + elementType + ".");
                    var values = new ArrayList<Object>(Math.min(size, 4096));
                    for (int i = 0; i < size; i++) values.add(payload(elementType, depth + 1));
                    yield new ListTag(elementType, values);
                }
                case COMPOUND -> {
                    var map = new LinkedHashMap<String, Object>();
                    while (true) {
                        int childType = in.readUnsignedByte();
                        if (childType == END) break;
                        if (childType > LONG_ARRAY) throw new IOException("NBT: неизвестный тип " + childType + ".");
                        String key = string();
                        map.put(key, payload(childType, depth + 1));
                    }
                    yield map;
                }
                case INT_ARRAY -> { int[] array = new int[count(4)]; for (int i = 0; i < array.length; i++) array[i] = in.readInt(); yield array; }
                case LONG_ARRAY -> { long[] array = new long[count(8)]; for (int i = 0; i < array.length; i++) array[i] = in.readLong(); yield array; }
                default -> throw new IOException("NBT: неизвестный тип " + type + ".");
            };
        }
    }

    private static byte[] readLimited(java.io.InputStream input, long limit) throws IOException {
        var output = new ByteArrayOutputStream();
        byte[] buffer = new byte[16384];
        long total = 0;
        for (int count; (count = input.read(buffer)) != -1; ) {
            total += count;
            if (total > limit) throw new IOException("NBT: файл слишком большой.");
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }
}

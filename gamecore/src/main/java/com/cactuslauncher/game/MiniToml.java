package com.cactuslauncher.game;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The TOML subset used by Forge and NeoForge mods.toml files: tables, arrays of tables, dotted and quoted
 * keys, basic/literal/multi-line strings, numbers, booleans, arrays and inline tables. Tables become
 * {@code Map<String, Object>}, arrays {@code List<Object>}; dates and other bare values stay strings.
 */
final class MiniToml {
    private final String text;
    private int pos;

    private MiniToml(String text) { this.text = text; }

    static Map<String, Object> parse(String text) throws IOException {
        return new MiniToml(text.startsWith("\uFEFF") ? text.substring(1) : text).document();
    }

    private Map<String, Object> document() throws IOException {
        Map<String, Object> root = new LinkedHashMap<>();
        Map<String, Object> current = root;
        while (true) {
            skipBlank(true);
            if (pos >= text.length()) return root;
            char c = text.charAt(pos);
            if (c == '[') {
                boolean array = text.startsWith("[[", pos);
                pos += array ? 2 : 1;
                List<String> path = keyPath();
                skipBlank(false);
                expect(array ? "]]" : "]");
                current = array ? appendTable(root, path) : table(root, path);
            } else {
                List<String> path = keyPath();
                skipBlank(false);
                expect("=");
                skipBlank(false);
                Object value = value();
                Map<String, Object> target = path.size() == 1 ? current : table(current, path.subList(0, path.size() - 1));
                target.put(path.get(path.size() - 1), value);
            }
            skipBlank(false);
            if (pos < text.length() && text.charAt(pos) != '\n' && text.charAt(pos) != '\r')
                throw error("ожидался перевод строки");
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> table(Map<String, Object> root, List<String> path) throws IOException {
        Map<String, Object> current = root;
        for (String key : path) {
            Object next = current.get(key);
            if (next == null) { next = new LinkedHashMap<String, Object>(); current.put(key, next); }
            if (next instanceof List<?> list && !list.isEmpty() && list.get(list.size() - 1) instanceof Map<?, ?>) next = list.get(list.size() - 1);
            if (!(next instanceof Map<?, ?>)) throw new IOException("TOML: ключ " + key + " не таблица");
            current = (Map<String, Object>) next;
        }
        return current;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> appendTable(Map<String, Object> root, List<String> path) throws IOException {
        Map<String, Object> parent = table(root, path.subList(0, path.size() - 1));
        String key = path.get(path.size() - 1);
        Object existing = parent.get(key);
        List<Object> list;
        if (existing == null) { list = new ArrayList<>(); parent.put(key, list); }
        else if (existing instanceof List<?>) list = (List<Object>) existing;
        else throw new IOException("TOML: ключ " + key + " не массив таблиц");
        Map<String, Object> table = new LinkedHashMap<>();
        list.add(table);
        return table;
    }

    private List<String> keyPath() throws IOException {
        var path = new ArrayList<String>();
        while (true) {
            skipBlank(false);
            path.add(key());
            skipBlank(false);
            if (pos < text.length() && text.charAt(pos) == '.') { pos++; continue; }
            return path;
        }
    }

    private String key() throws IOException {
        if (pos >= text.length()) throw error("ожидался ключ");
        char c = text.charAt(pos);
        if (c == '"' || c == '\'') return (String) string();
        int start = pos;
        while (pos < text.length()) {
            char k = text.charAt(pos);
            if (Character.isLetterOrDigit(k) || k == '_' || k == '-') pos++;
            else break;
        }
        if (start == pos) throw error("ожидался ключ");
        return text.substring(start, pos);
    }

    private Object value() throws IOException {
        if (pos >= text.length()) throw error("ожидалось значение");
        char c = text.charAt(pos);
        if (c == '"' || c == '\'') return string();
        if (c == '[') {
            pos++;
            var list = new ArrayList<Object>();
            while (true) {
                skipBlank(true);
                if (pos < text.length() && text.charAt(pos) == ']') { pos++; return list; }
                list.add(value());
                skipBlank(true);
                if (pos < text.length() && text.charAt(pos) == ',') { pos++; continue; }
                skipBlank(true);
                expect("]");
                return list;
            }
        }
        if (c == '{') {
            pos++;
            Map<String, Object> table = new LinkedHashMap<>();
            skipBlank(false);
            if (pos < text.length() && text.charAt(pos) == '}') { pos++; return table; }
            while (true) {
                List<String> path = keyPath();
                skipBlank(false);
                expect("=");
                skipBlank(false);
                Object value = value();
                Map<String, Object> target = path.size() == 1 ? table : table(table, path.subList(0, path.size() - 1));
                target.put(path.get(path.size() - 1), value);
                skipBlank(false);
                if (pos < text.length() && text.charAt(pos) == ',') { pos++; continue; }
                expect("}");
                return table;
            }
        }
        int start = pos;
        while (pos < text.length()) {
            char k = text.charAt(pos);
            if (k == ',' || k == ']' || k == '}' || k == '#' || k == '\n' || k == '\r') break;
            pos++;
        }
        String bare = text.substring(start, pos).strip();
        if (bare.isEmpty()) throw error("ожидалось значение");
        if (bare.equals("true")) return Boolean.TRUE;
        if (bare.equals("false")) return Boolean.FALSE;
        return bare;
    }

    private Object string() throws IOException {
        char quote = text.charAt(pos);
        boolean multi = text.startsWith(String.valueOf(quote).repeat(3), pos);
        pos += multi ? 3 : 1;
        if (multi && pos < text.length() && text.charAt(pos) == '\n') pos++;
        else if (multi && text.startsWith("\r\n", pos)) pos += 2;
        var result = new StringBuilder();
        while (true) {
            if (pos >= text.length()) throw error("незакрытая строка");
            char c = text.charAt(pos);
            if (multi ? text.startsWith(String.valueOf(quote).repeat(3), pos) : c == quote) {
                pos += multi ? 3 : 1;
                // """a"""" style: extra closing quotes belong to the string.
                while (multi && pos < text.length() && text.charAt(pos) == quote) { result.append(quote); pos++; }
                return result.toString();
            }
            if (!multi && (c == '\n' || c == '\r')) throw error("перевод строки внутри строки");
            if (quote == '"' && c == '\\') {
                pos++;
                if (pos >= text.length()) throw error("незакрытая строка");
                char e = text.charAt(pos);
                switch (e) {
                    case 'n' -> result.append('\n');
                    case 't' -> result.append('\t');
                    case 'r' -> result.append('\r');
                    case 'b' -> result.append('\b');
                    case 'f' -> result.append('\f');
                    case '"' -> result.append('"');
                    case '\\' -> result.append('\\');
                    case 'u', 'U' -> {
                        int length = e == 'u' ? 4 : 8;
                        if (pos + length >= text.length()) throw error("неверный \\u");
                        try { result.appendCodePoint(Integer.parseInt(text.substring(pos + 1, pos + 1 + length), 16)); }
                        catch (IllegalArgumentException ex) { throw error("неверный \\u"); }
                        pos += length;
                    }
                    case '\n', '\r', ' ', '\t' -> {
                        if (!multi) throw error("неверное экранирование");
                        while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) pos++;
                        continue;
                    }
                    default -> throw error("неверное экранирование");
                }
                pos++;
                continue;
            }
            result.append(c);
            pos++;
        }
    }

    /** Skips spaces, tabs and comments; with [newlines] also line breaks. */
    private void skipBlank(boolean newlines) {
        while (pos < text.length()) {
            char c = text.charAt(pos);
            if (c == ' ' || c == '\t' || (newlines && (c == '\n' || c == '\r'))) pos++;
            else if (c == '#') { while (pos < text.length() && text.charAt(pos) != '\n') pos++; }
            else return;
        }
    }

    private void expect(String token) throws IOException {
        if (!text.startsWith(token, pos)) throw error("ожидалось «" + token + "»");
        pos += token.length();
    }

    private IOException error(String message) {
        int line = 1;
        for (int i = 0; i < Math.min(pos, text.length()); i++) if (text.charAt(i) == '\n') line++;
        return new IOException("TOML, строка " + line + ": " + message);
    }
}

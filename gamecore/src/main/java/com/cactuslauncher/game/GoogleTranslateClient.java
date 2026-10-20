package com.cactuslauncher.game;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Best-effort Google web translator. Its unofficial endpoints can change or be rate-limited. */
public final class GoogleTranslateClient {
    private static final List<String> SINGLE_ENDPOINTS = List.of(
            "https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=ru&dt=t&q=",
            "https://translate.google.com/translate_a/single?client=gtx&sl=auto&tl=ru&dt=t&q=");

    public String toRussian(String text) throws IOException {
        if (text == null || text.isBlank()) return text == null ? "" : text;
        if (text.length() > 10000) throw new IOException("Описание слишком длинное (максимум 10 000 символов).");
        StringBuilder result = new StringBuilder();
        for (String part : split(text, 700)) {
            if (result.length() > 0 && needsSeparator(result, part)) result.append(' ');
            result.append(translatePart(part));
        }
        return result.toString();
    }

    private String translatePart(String text) throws IOException {
        String encoded = URLEncoder.encode(text, StandardCharsets.UTF_8);
        IOException failures = null;
        for (String endpoint : SINGLE_ENDPOINTS) {
            try {
                String response = get(endpoint + encoded);
                String translated = parseSingle(response);
                if (!translated.isBlank()) return translated;
                throw new IOException("Пустой перевод.");
            } catch (IOException e) {
                if (failures == null) failures = new IOException("Не удалось обратиться к Google Translate.");
                failures.addSuppressed(new IOException(endpoint.substring(8, endpoint.indexOf('/', 8)) + ": " + e.getMessage()));
            }
        }
        String chromeEndpoint = "https://clients5.google.com/translate_a/t?client=dict-chrome-ex&sl=auto&tl=ru&q=" + encoded;
        try {
            String translated = parseChrome(get(chromeEndpoint));
            if (!translated.isBlank()) return translated;
            throw new IOException("Пустой перевод.");
        } catch (IOException e) {
            if (failures == null) failures = new IOException("Не удалось обратиться к Google Translate.");
            failures.addSuppressed(new IOException("clients5.google.com: " + e.getMessage()));
        }
        try { return translateWithMyMemory(text); }
        catch (IOException e) {
            if (failures == null) failures = new IOException("Не удалось выполнить перевод.");
            failures.addSuppressed(new IOException("api.mymemory.translated.net: " + e.getMessage()));
            throw failures;
        }
    }

    private String get(String address) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) URI.create(address).toURL().openConnection();
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(10000);
        connection.setRequestMethod("GET");
        connection.setRequestProperty("User-Agent", "Mozilla/5.0 CactusLauncher/2.0");
        try {
            int status = connection.getResponseCode();
            if (status != 200) throw new IOException("HTTP " + status);
            try (var stream = connection.getInputStream()) {
                byte[] bytes = stream.readNBytes(1024 * 1024 + 1);
                if (bytes.length > 1024 * 1024) throw new IOException("слишком большой ответ");
                return new String(bytes, StandardCharsets.UTF_8);
            }
        } finally {
            connection.disconnect();
        }
    }

    private String parseSingle(String response) throws IOException {
        try {
            JsonArray chunks = JsonParser.parseString(response).getAsJsonArray().get(0).getAsJsonArray();
            StringBuilder translated = new StringBuilder();
            for (JsonElement chunk : chunks) {
                if (chunk.isJsonArray() && !chunk.getAsJsonArray().get(0).isJsonNull())
                    translated.append(chunk.getAsJsonArray().get(0).getAsString());
            }
            return translated.toString();
        } catch (RuntimeException e) {
            throw new IOException("неожиданный формат ответа", e);
        }
    }

    private String parseChrome(String response) throws IOException {
        try {
            JsonArray sentences = JsonParser.parseString(response).getAsJsonObject().getAsJsonArray("sentences");
            StringBuilder translated = new StringBuilder();
            for (JsonElement sentence : sentences) translated.append(sentence.getAsJsonObject().get("trans").getAsString());
            return translated.toString();
        } catch (RuntimeException e) {
            throw new IOException("неожиданный формат ответа", e);
        }
    }

    private String translateWithMyMemory(String text) throws IOException {
        StringBuilder result = new StringBuilder();
        for (String part : splitUtf8(text, 450)) {
            String address = "https://api.mymemory.translated.net/get?q="
                    + URLEncoder.encode(part, StandardCharsets.UTF_8)
                    + "&langpair=autodetect%7Cru";
            try {
                String translated = JsonParser.parseString(get(address)).getAsJsonObject()
                        .getAsJsonObject("responseData").get("translatedText").getAsString();
                if (translated.isBlank()) throw new IOException("пустой перевод");
                if (result.length() > 0 && needsSeparator(result, translated)) result.append(' ');
                result.append(decodeEntities(translated));
            } catch (RuntimeException e) {
                throw new IOException("неожиданный формат ответа", e);
            }
        }
        return result.toString();
    }

    private List<String> splitUtf8(String text, int maxBytes) {
        java.util.ArrayList<String> parts = new java.util.ArrayList<>();
        StringBuilder part = new StringBuilder();
        int bytes = 0;
        for (int offset = 0; offset < text.length();) {
            int codePoint = text.codePointAt(offset);
            String chars = new String(Character.toChars(codePoint));
            int charBytes = chars.getBytes(StandardCharsets.UTF_8).length;
            if (bytes + charBytes > maxBytes && part.length() > 0) {
                parts.add(part.toString());
                part.setLength(0);
                bytes = 0;
            }
            part.append(chars);
            bytes += charBytes;
            offset += Character.charCount(codePoint);
        }
        if (part.length() > 0) parts.add(part.toString());
        return parts;
    }

    private String decodeEntities(String text) {
        return text.replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")
                .replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&");
    }

    private List<String> split(String text, int maxLength) {
        java.util.ArrayList<String> parts = new java.util.ArrayList<>();
        int offset = 0;
        while (offset < text.length()) {
            int end = Math.min(offset + maxLength, text.length());
            if (end < text.length()) {
                int boundary = text.lastIndexOf(' ', end);
                if (boundary > offset + maxLength / 2) end = boundary + 1;
            }
            parts.add(text.substring(offset, end));
            offset = end;
        }
        return parts;
    }

    private boolean needsSeparator(StringBuilder previous, String next) {
        return previous.length() > 0 && !Character.isWhitespace(previous.charAt(previous.length() - 1))
                && !next.isEmpty() && !Character.isWhitespace(next.charAt(0));
    }
}

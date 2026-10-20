package com.cactuslauncher.game;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Per-application audio streams exposed by the Linux PulseAudio/PipeWire server. */
public final class AudioMixer {
    public record Session(String id, String name, float volume, boolean muted) {}

    public List<Session> sessions() throws IOException {
        String output = run(List.of("pactl", "list", "sink-inputs"));
        List<Session> result = new ArrayList<>();
        String[] blocks = output.split("(?m)^Sink Input #");
        for (String block : blocks) {
            Matcher idMatcher = Pattern.compile("^([0-9]+)").matcher(block);
            if (!idMatcher.find()) continue;
            String id = idMatcher.group(1);
            String name = property(block, "application.name");
            if (name.isBlank()) name = property(block, "media.name");
            if (name.isBlank()) name = "Приложение " + id;
            Matcher volumeMatcher = Pattern.compile("(?m)^\\s*Volume:.*?([0-9]{1,3})%").matcher(block);
            float volume = volumeMatcher.find()
                    ? Math.max(0f, Math.min(1f, Integer.parseInt(volumeMatcher.group(1)) / 100f))
                    : 1f;
            Matcher muteMatcher = Pattern.compile("(?m)^\\s*Mute: (yes|no)\\s*$").matcher(block);
            boolean muted = muteMatcher.find() && muteMatcher.group(1).equals("yes");
            result.add(new Session(id, name, volume, muted));
        }
        return result;
    }

    public void setVolume(String id, float volume) throws IOException {
        validateId(id);
        int percent = Math.round(Math.max(0f, Math.min(1f, volume)) * 100);
        run(List.of("pactl", "set-sink-input-volume", id, percent + "%"));
    }

    public void setMuted(String id, boolean muted) throws IOException {
        validateId(id);
        run(List.of("pactl", "set-sink-input-mute", id, muted ? "1" : "0"));
    }

    private static String property(String block, String key) {
        Matcher matcher = Pattern.compile("(?m)^\\s*" + Pattern.quote(key) + "\\s*=\\s*\"(.*?)\"\\s*$").matcher(block);
        return matcher.find() ? matcher.group(1) : "";
    }

    private static void validateId(String id) throws IOException {
        if (id == null || !id.matches("[0-9]+")) throw new IOException("Некорректный идентификатор аудиопотока.");
    }

    private static String run(List<String> command) throws IOException {
        Process process;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
        } catch (IOException e) {
            throw new IOException("Не найден аудиосервер PulseAudio/PipeWire или команда pactl.", e);
        }
        try {
            java.util.concurrent.CompletableFuture<byte[]> output = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                try {
                    return process.getInputStream().readAllBytes();
                } catch (IOException e) {
                    throw new java.util.concurrent.CompletionException(e);
                }
            });
            if (!process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly();
                output.cancel(true);
                throw new IOException("Аудиосервер не ответил вовремя.");
            }
            byte[] bytes = output.get(1, java.util.concurrent.TimeUnit.SECONDS);
            if (process.exitValue() != 0) throw new IOException(new String(bytes, StandardCharsets.UTF_8).trim());
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (java.util.concurrent.TimeoutException e) {
            process.destroyForcibly();
            throw new IOException("Не удалось получить ответ аудиосервера вовремя.", e);
        } catch (java.util.concurrent.ExecutionException e) {
            throw new IOException("Не удалось прочитать ответ аудиосервера.", e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Операция с аудиомикшером отменена.", e);
        }
    }
}

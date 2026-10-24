package com.cactuslauncher.game;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Random;
import java.util.function.Consumer;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;

/**
 * Plays one weighted-random C418 track ({@link C418Music#TRACKS}) in the launcher, on a background thread.
 * Finding or downloading the track never blocks the caller; with no network and nothing cached it stays silent.
 */
public final class LauncherMusic {
    /** Linear gain of the music, about -6 dB. */
    public static final float VOLUME = 0.5f;
    private static final int BUFFER_MILLIS = 200;

    private final MusicAssets assets;
    private final Consumer<Exception> errors;
    private final Random random = new Random();
    private Session session;
    private int lastTrackIndex = -1;

    public record Playing(String title, Path file, int trackIndex) {}

    /** [errors] receives failures worth logging; being offline is not one of them. */
    public LauncherMusic(MusicAssets assets, Consumer<Exception> errors) {
        this.assets = assets;
        this.errors = errors;
    }

    /** Starts a track unless one is already playing or starting. */
    public synchronized void play() {
        if (session != null && session.thread.isAlive() && !session.fading()) return;
        replace(new Session(-1, false)).thread.start();
    }

    public synchronized void next() { skip(1); }

    public synchronized void previous() { skip(-1); }

    public synchronized void setPaused(boolean paused) {
        if (session == null || !session.thread.isAlive() || session.fading() || session.playing == null) return;
        session.setPaused(paused);
    }

    public synchronized boolean isPlaying() {
        return session != null && session.thread.isAlive() && !session.fading() && !session.isPaused();
    }

    private void skip(int direction) {
        int current = session == null ? lastTrackIndex : session.trackIndex();
        int count = C418Music.TRACKS.size();
        int target = current < 0
                ? random.nextInt(count)
                : Math.floorMod(current + direction, count);
        boolean keepPaused = session != null && session.isPaused();
        lastTrackIndex = target;
        Session next = new Session(target, keepPaused);
        replace(next).thread.start();
    }

    /** Fades the music out over [fadeMillis] (0 = cut) and stops it. */
    public synchronized void stop(long fadeMillis) {
        if (session != null) session.cancel(fadeMillis);
    }

    private Session replace(Session next) {
        Session previous = session;
        if (previous != null) {
            previous.cancel(0);
            try {
                previous.thread.join(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        session = next;
        return next;
    }

    public synchronized boolean isActive() {
        return session != null && session.thread.isAlive();
    }

    public synchronized Playing currentTrack() {
        if (session == null || session.fading()) return null;
        if (session.playing != null) return session.playing;
        int index = session.trackIndex();
        return index < 0 ? null : new Playing(C418Music.TRACKS.get(index).title(), null, index);
    }

    /** Picks the track: the weighted choice, else (offline) a weighted choice among tracks already on disk. */
    Playing choose(int requestedIndex) throws InterruptedException {
        assets.refresh();
        var first = requestedIndex < 0 ? C418Music.pick(C418Music.TRACKS, random) : C418Music.TRACKS.get(requestedIndex);
        try {
            return new Playing(first.title(), assets.fetch(first), C418Music.TRACKS.indexOf(first));
        } catch (IOException offline) {
            var available = new ArrayList<C418Music.Track>();
            for (var track : C418Music.TRACKS) if (assets.local(track) != null) available.add(track);
            var fallback = C418Music.pick(available, random);
            return fallback == null ? null : new Playing(fallback.title(), assets.local(fallback), C418Music.TRACKS.indexOf(fallback));
        }
    }

    private static final class NoAudioOutput extends IOException {
        NoAudioOutput(Exception cause) { super("Нет аудиовыхода.", cause); }
    }

    private final class Session implements OggVorbisDecoder.Sink {
        final Thread thread = new Thread(this::run, "cactus-launcher-music");
        private final int requestedIndex;
        private volatile boolean cancelled;
        private volatile boolean paused;
        private volatile long fadeStart;
        private volatile long fadeNanos;
        private volatile SourceDataLine line;
        private byte[] bytes = new byte[0];
        private int channels;
        private volatile Playing playing;

        Session(int requestedIndex, boolean paused) {
            this.requestedIndex = requestedIndex;
            this.paused = paused;
            thread.setDaemon(true);
        }

        int trackIndex() { return playing == null ? requestedIndex : playing.trackIndex(); }
        boolean isPaused() { return paused; }

        void setPaused(boolean paused) {
            this.paused = paused;
            SourceDataLine current = line;
            if (current == null) return;
            if (paused) current.stop();
            else current.start();
        }

        boolean fading() { return fadeNanos > 0 || cancelled; }

        void cancel(long fadeMillis) {
            if (fadeMillis <= 0) {
                cancelled = true;
                thread.interrupt();
                SourceDataLine current = line;
                if (current != null) {
                    try { current.stop(); } catch (Exception ignored) { }
                    try { current.flush(); } catch (Exception ignored) { }
                }
                return;
            }
            if (fadeNanos == 0) {
                fadeStart = System.nanoTime();
                fadeNanos = fadeMillis * 1_000_000L;
            }
        }

        private float gain() {
            if (cancelled) return 0f;
            long nanos = fadeNanos;
            if (nanos == 0) return VOLUME;
            float left = 1f - (System.nanoTime() - fadeStart) / (float) nanos;
            if (left <= 0f) { cancelled = true; return 0f; }
            return VOLUME * left;
        }

        private void run() {
            try {
                Playing track = choose(requestedIndex);
                if (track == null || cancelled || fadeNanos > 0) return;
                playing = track;
                synchronized (LauncherMusic.this) { lastTrackIndex = track.trackIndex(); }
                try (InputStream input = new BufferedInputStream(Files.newInputStream(track.file()))) {
                    OggVorbisDecoder.decode(input, this);
                }
                if (line != null && !cancelled) line.drain();
            } catch (InterruptedException e) {
                // stopped while looking for the track
            } catch (NoAudioOutput | IllegalArgumentException e) {
                // no audio device (or a headless machine): stay silent
            } catch (Exception e) {
                if (!cancelled) errors.accept(e);
            } finally {
                playing = null;
                if (line != null) {
                    if (cancelled) line.flush();
                    line.stop();
                    line.close();
                }
            }
        }

        @Override public void start(int sampleRate, int channels) throws IOException {
            this.channels = channels;
            var format = new AudioFormat(sampleRate, 16, channels, true, false);
            try {
                line = AudioSystem.getSourceDataLine(format);
                line.open(format, Math.max(4096, sampleRate * channels * 2 * BUFFER_MILLIS / 1000));
            } catch (LineUnavailableException | IllegalArgumentException | SecurityException e) {
                throw new NoAudioOutput(e);
            }
            if (paused) line.stop(); else line.start();
        }

        @Override public boolean write(float[][] pcm, int[] offsets, int samples) {
            float gain = gain();
            if (cancelled) return false;
            int size = samples * channels * 2;
            if (bytes.length < size) bytes = new byte[size];
            int at = 0;
            for (int i = 0; i < samples; i++) {
                for (int channel = 0; channel < channels; channel++) {
                    int value = Math.round(pcm[channel][offsets[channel] + i] * gain * 32767f);
                    if (value > 32767) value = 32767; else if (value < -32768) value = -32768;
                    bytes[at++] = (byte) value;
                    bytes[at++] = (byte) (value >> 8);
                }
            }
            line.write(bytes, 0, size);
            return !cancelled;
        }
    }
}

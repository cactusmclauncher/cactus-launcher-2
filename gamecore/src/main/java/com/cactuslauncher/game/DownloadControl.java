package com.cactuslauncher.game;

import java.io.InterruptedIOException;

public final class DownloadControl {
    private boolean paused;
    public synchronized void setPaused(boolean value) { paused = value; notifyAll(); }
    public synchronized void await() throws InterruptedIOException {
        try {
            while (paused) wait();
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Скачивание отменено.");
        }
    }
}

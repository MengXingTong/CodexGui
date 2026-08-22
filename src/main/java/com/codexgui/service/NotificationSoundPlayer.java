package com.codexgui.service;

import javazoom.jl.player.Player;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.SourceDataLine;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/** Plays CC-Gui-compatible built-in and custom notification sounds off the UI thread. */
public final class NotificationSoundPlayer implements AutoCloseable {
    public static final Set<String> BUILT_IN_SOUND_IDS = Set.of("default", "chime", "bell", "ding", "success");
    private static final AudioFormat SYNTH_FORMAT = new AudioFormat(44_100, 16, 1, true, false);

    private final AtomicReference<AutoCloseable> activePlayback = new AtomicReference<>();

    public CompletableFuture<Void> play(String soundId, String customSoundPath) {
        stopCurrent();
        return CompletableFuture.runAsync(() -> {
            try {
                if ("custom".equals(soundId)) {
                    playCustom(Path.of(customSoundPath));
                } else {
                    playBuiltIn(BUILT_IN_SOUND_IDS.contains(soundId) ? soundId : "default");
                }
            } catch (Exception error) {
                throw new RuntimeException("无法播放提示音", error);
            }
        });
    }

    private void playBuiltIn(String soundId) throws Exception {
        double[][] notes = switch (soundId) {
            case "chime" -> new double[][]{{1318.5, 110}, {1568, 110}, {2093, 210}};
            case "bell" -> new double[][]{{659.3, 480}};
            case "ding" -> new double[][]{{1046.5, 130}, {1318.5, 240}};
            case "success" -> new double[][]{{523.3, 90}, {659.3, 90}, {784, 90}, {1046.5, 220}};
            default -> new double[][]{{880, 120}, {1174.7, 180}};
        };
        var line = AudioSystem.getSourceDataLine(SYNTH_FORMAT);
        AutoCloseable playback = line::close;
        activePlayback.set(playback);
        line.open(SYNTH_FORMAT);
        line.start();
        for (var note : notes) {
            var bytes = tone(note[0], (int) note[1]);
            line.write(bytes, 0, bytes.length);
        }
        line.drain();
        line.close();
        activePlayback.compareAndSet(playback, null);
    }

    private byte[] tone(double frequency, int durationMs) {
        int sampleCount = (int) (SYNTH_FORMAT.getSampleRate() * durationMs / 1000.0);
        byte[] bytes = new byte[sampleCount * 2];
        for (int index = 0; index < sampleCount; index++) {
            double progress = index / (double) Math.max(1, sampleCount - 1);
            double envelope = Math.min(1, progress * 12) * Math.pow(1 - progress, 1.7);
            short sample = (short) (Math.sin(2 * Math.PI * frequency * index / SYNTH_FORMAT.getSampleRate()) * 11_000 * envelope);
            bytes[index * 2] = (byte) sample;
            bytes[index * 2 + 1] = (byte) (sample >> 8);
        }
        return bytes;
    }

    private void playCustom(Path path) throws Exception {
        if (!Files.isRegularFile(path)) throw new IOException("音频文件不存在：" + path);
        var fileName = path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (fileName.endsWith(".mp3")) {
            try (var input = new BufferedInputStream(Files.newInputStream(path))) {
                var player = new Player(input);
                AutoCloseable playback = player::close;
                activePlayback.set(playback);
                player.play();
                activePlayback.compareAndSet(playback, null);
            }
            return;
        }
        try (AudioInputStream stream = AudioSystem.getAudioInputStream(path.toFile())) {
            Clip clip = AudioSystem.getClip();
            AutoCloseable playback = clip::close;
            activePlayback.set(playback);
            clip.open(stream);
            clip.start();
            Thread.sleep(20);
            while (clip.isRunning()) Thread.sleep(20);
            clip.close();
            activePlayback.compareAndSet(playback, null);
        }
    }

    private void stopCurrent() {
        var playback = activePlayback.getAndSet(null);
        if (playback == null) return;
        try {
            playback.close();
        } catch (Exception ignored) {
        }
    }

    @Override
    public void close() {
        stopCurrent();
    }
}

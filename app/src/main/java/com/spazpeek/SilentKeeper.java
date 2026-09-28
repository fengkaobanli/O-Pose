package com.spazpeek;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;

/**
 * 静音保持器（SilentKeeper）：接管期间持续向音频子系统写入静音 PCM。
 * ====================
 * 目的（软性防冻）：让系统/Hans 把本应用识别为"音频活跃"应用——
 * Oplus Hans 对持有活跃音频输出的应用通常不执行激进冻结
 * （音乐类应用切后台不被冻即此逻辑）。输出全 0 样本，无任何可听声音。
 * 不请求 AudioFocus，不打断用户正在播放的音乐。
 */
public final class SilentKeeper {

    private static AudioTrack track;
    private static Thread thr;
    private static volatile boolean running;

    private SilentKeeper() {}

    public static void start() {
        if (running) return;
        running = true;
        new Thread(SilentKeeper::startInner, "spz-silent-init").start();
    }

    private static void startInner() {
        try {
            int rate = 22050;
            int minBuf = AudioTrack.getMinBufferSize(rate,
                    AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
            final int buf = Math.max(minBuf, rate / 2);
            track = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(rate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build())
                    .setBufferSizeInBytes(buf)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build();
            try { track.setVolume(0f); } catch (Throwable ignored) {}
            track.play();
            final byte[] zeros = new byte[buf];
            thr = new Thread(() -> {
                try {
                    while (running) {
                        track.write(zeros, 0, zeros.length);
                    }
                } catch (Throwable ignored) {}
            }, "spz-silent");
            thr.start();
            HTLog.log("KEEP", "silent keeper started");
        } catch (Throwable t) {
            HTLog.log("KEEP", "silent keeper fail: " + t);
            running = false;
        }
    }

    public static void stop() {
        running = false;
        try {
            if (thr != null) thr.interrupt();
        } catch (Throwable ignored) {}
        try {
            if (track != null) { track.stop(); track.release(); }
        } catch (Throwable ignored) {}
        track = null;
        HTLog.log("KEEP", "silent keeper stopped");
    }
}
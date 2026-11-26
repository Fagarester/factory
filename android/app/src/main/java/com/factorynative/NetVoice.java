package com.factorynative;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * The walkie-talkie: while the mic button is held, 40 ms frames go out at once (no recording first). 16 kHz mono,
 * Opus 24 kbit/s (~3 KB/s) — frames: VoiceCodec. Each speaker plays on its own
 * AudioTrack after a short buffer (~120 ms) against network jitter; quiet for 400 ms, the track stops.
 */
final class NetVoice {
    static final int RATE = VoiceCodec.RATE, FRAME = VoiceCodec.FRAME;
    private static final int PREBUFFER = 3, MAX_QUEUE = 10;  // frames: 120 ms before playing, 400 ms at most behind

    // ------------------------------------------------------------------ recording (the mic button)

    private volatile Object session;  // the press now (null: not recording); a quick press again gets a new one

    /** Starts sending frames to out (on the recorder's thread); false when the mic does not open or Opus is missing. */
    synchronized boolean start(Consumer<byte[]> out) {
        if (session != null) return true;
        VoiceCodec.Encoder enc = new VoiceCodec.Encoder();
        if (!enc.ok()) return false;
        int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        AudioRecord rec;
        try {
            // VOICE_COMMUNICATION: the phone's echo canceller and noise suppressor, where it has them
            rec = new AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, Math.max(min, FRAME * 2 * 4));
            if (rec.getState() != AudioRecord.STATE_INITIALIZED) {
                rec.release();
                enc.close();
                return false;
            }
            rec.startRecording();
        } catch (RuntimeException e) {  // SecurityException without the permission too
            enc.close();
            return false;
        }
        Object mine = new Object();
        session = mine;
        new Thread(() -> {
            short[] pcm = new short[FRAME];
            try {
                while (session == mine) {
                    int got = 0;
                    while (got < FRAME && session == mine) {
                        int r = rec.read(pcm, got, FRAME - got);
                        if (r < 0) return;
                        got += r;
                    }
                    if (got < FRAME) break;  // released mid-frame: the rest is dropped
                    byte[] f = enc.encode(pcm);
                    if (f != null) out.accept(f);
                }
            } finally {
                enc.close();
                try {
                    rec.stop();
                } catch (RuntimeException ignored) {
                    // already stopped
                }
                rec.release();
            }
        }, "fnet-mic").start();
        return true;
    }

    synchronized void stop() {
        session = null;
    }

    boolean recording() {
        return session != null;
    }

    // ------------------------------------------------------------------ playing (other players)

    private final Map<Integer, Speaker> speakers = new HashMap<>();
    private volatile boolean closed;

    /** A frame of speaker src (VoiceCodec; any thread). */
    void play(int src, byte[] frame) {
        if (closed || GameActivity.inBackground) return;  // "Работать в фоне": no sound behind other apps
        Speaker s;
        synchronized (speakers) {
            s = speakers.get(src);
            if (s == null || !s.alive) {
                s = new Speaker(src);
                speakers.put(src, s);
                s.start();
            }
        }
        s.add(frame);
    }

    /** Everything stops: the mic and all the speakers. */
    void close() {
        closed = true;
        stop();
        synchronized (speakers) {
            for (Speaker s : speakers.values()) s.alive = false;
            speakers.clear();
        }
    }

    private final class Speaker extends Thread {
        final int src;
        volatile boolean alive = true;
        private final LinkedBlockingDeque<short[]> queue = new LinkedBlockingDeque<>();
        private final VoiceCodec.Decoder decoder = new VoiceCodec.Decoder();

        Speaker(int src) {
            super("fnet-voice");
            this.src = src;
        }

        synchronized void add(byte[] frame) {
            if (!alive) return;
            queue.addAll(decoder.accept(frame));
            while (queue.size() > MAX_QUEUE) queue.pollFirst();  // too far behind: the oldest go
        }

        @Override public void run() {
            int min = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
            AudioTrack track;
            try {
                track = new AudioTrack.Builder()
                        .setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_GAME)  // the speaker as the game's sound, not the earpiece
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                        .setAudioFormat(new AudioFormat.Builder().setSampleRate(RATE)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                        .setBufferSizeInBytes(Math.max(min, FRAME * 2 * 4))
                        .setTransferMode(AudioTrack.MODE_STREAM).build();
            } catch (RuntimeException e) {
                alive = false;
                synchronized (this) {
                    decoder.close();
                }
                return;
            }
            boolean playing = false;
            long idleSince = System.currentTimeMillis();
            try {
                while (alive && !closed) {
                    short[] f = queue.poll(playing ? 400 : 1000, TimeUnit.MILLISECONDS);
                    if (f == null) {
                        if (playing) {  // the speaker let go of the button (or the line dropped)
                            track.pause();
                            track.flush();
                            playing = false;
                            synchronized (this) {
                                decoder.restart();
                            }
                            idleSince = System.currentTimeMillis();
                        } else if (System.currentTimeMillis() - idleSince > 30000) break;  // a new one comes with the next frame
                        continue;
                    }
                    if (!playing) {  // a short buffer first: frames come unevenly over the network
                        long until = System.currentTimeMillis() + PREBUFFER * 40L;
                        while (queue.size() < PREBUFFER - 1 && System.currentTimeMillis() < until) Thread.sleep(5);
                        track.play();
                        playing = true;
                    }
                    track.write(f, 0, f.length);
                }
            } catch (InterruptedException ignored) {
                // closing
            } finally {
                alive = false;
                synchronized (speakers) {
                    if (speakers.get(src) == this) speakers.remove(src);
                }
                synchronized (this) {
                    decoder.close();
                }
                track.release();
            }
        }
    }
}

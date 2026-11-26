package com.factorynative;

import java.util.ArrayList;
import java.util.List;

/**
 * The walkie-talkie's frames: codec[1] seq[2] data, 40 ms of 16 kHz mono each. Codec 1 = Opus (24 kbit/s, ~130 bytes
 * a frame, in-band FEC); there is no other (0.172's ADPCM is gone since 0.174: its frames are skipped).
 * Plain Java (Opus through JNI): tested on the host with a glibc libfnopus (tests/net-src OpusTest).
 */
final class VoiceCodec {
    static final int RATE = 16000, FRAME = 640;  // 40 ms
    static final int OPUS = 1;
    static final int OPUS_BITRATE = 24000, OPUS_COMPLEXITY = 5;  // speech stays clear; light on the CPU

    private VoiceCodec() {}

    /** One press of the mic: frames numbered from 0. ok() false: no Opus, nothing to send. Not thread-safe. */
    static final class Encoder {
        private long opus;
        private final byte[] buf = new byte[1275];  // Opus's largest packet
        private int seq;

        Encoder() {
            opus = Opus.ok() ? Opus.encCreate(RATE, OPUS_BITRATE, OPUS_COMPLEXITY) : 0;
        }

        boolean ok() {
            return opus != 0;
        }

        /** FRAME samples → a frame, or null (an Opus error: that frame is skipped). */
        byte[] encode(short[] pcm) {
            if (opus == 0) return null;
            int n = Opus.encode(opus, pcm, FRAME, buf);
            if (n <= 0) return null;
            byte[] f = new byte[3 + n];
            f[0] = (byte) OPUS;
            f[1] = (byte) (seq >> 8);
            f[2] = (byte) seq;
            System.arraycopy(buf, 0, f, 3, n);
            seq = (seq + 1) & 0xffff;
            return f;
        }

        void close() {
            if (opus != 0) Opus.encFree(opus);
            opus = 0;
        }
    }

    /** One speaker's frames in → sound out, in order. Lost frames are made up: the last one before a frame from that
     * frame's FEC, the others by Opus's loss concealment. Not thread-safe; close() at the end. */
    static final class Decoder {
        private static final int MAX_FILL = 3;  // lost frames made up at most (120 ms); a longer gap is just skipped
        private long opus;
        private int lastSeq = -1;
        private final short[] big = new short[FRAME * 3];  // Opus may carry up to 120 ms in a packet

        /** A new talk (the speaker let go): numbering starts again. */
        void restart() {
            lastSeq = -1;
        }

        /** The sound of a frame: none (a repeat, late, broken, not Opus), one, or more (made-up lost ones before it). */
        List<short[]> accept(byte[] f) {
            List<short[]> out = new ArrayList<>(1);
            if (f.length < 4 || (f[0] & 0xff) != OPUS || !Opus.ok()) return out;
            int seq = (f[1] & 0xff) << 8 | (f[2] & 0xff);
            int lost = 0;
            if (lastSeq >= 0) {
                int d = (seq - lastSeq) & 0xffff;
                if (d == 0 || d > 0x8000) return out;  // a repeat or late: its place has already played
                lost = d - 1;
            }
            lastSeq = seq;
            if (opus == 0) opus = Opus.decCreate(RATE);
            if (opus == 0) return out;
            if (lost > 0 && lost <= MAX_FILL) {
                for (int i = 0; i < lost - 1; i++) {
                    short[] p = new short[FRAME];
                    if (Opus.decode(opus, null, 0, 0, p, 0) == FRAME) out.add(p);
                }
                short[] p = new short[FRAME];
                if (Opus.decode(opus, f, 3, f.length - 3, p, 1) == FRAME) out.add(p);  // FEC of the one just before
            }
            int n = Opus.decode(opus, f, 3, f.length - 3, big, 0);
            if (n > 0) out.add(java.util.Arrays.copyOf(big, n));
            return out;
        }

        void close() {
            if (opus != 0) Opus.decFree(opus);
            opus = 0;
        }
    }
}

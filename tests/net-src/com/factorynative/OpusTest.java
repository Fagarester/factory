package com.factorynative;

import java.util.ArrayList;
import java.util.List;

/**
 * The walkie-talkie's frames (VoiceCodec) with real libopus: needs the glibc build (HOST=1 tools/build-opus.sh):
 * java -Djava.library.path=work/opus-host -cp classes com.factorynative.OpusTest
 */
public class OpusTest {
    static short[] speechLike(int frame) {
        int n = VoiceCodec.FRAME;
        short[] p = new short[n];
        for (int i = 0; i < n; i++) {
            double t = (frame * n + i) / 16000.0, env = 0.6 + 0.4 * Math.sin(2 * Math.PI * 3 * t);  // syllables
            p[i] = (short) (env * (7000 * Math.sin(2 * Math.PI * 220 * t) + 4000 * Math.sin(2 * Math.PI * 660 * t)
                    + 2500 * Math.sin(2 * Math.PI * 1500 * t) + 1200 * Math.sin(2 * Math.PI * 2900 * t)));
        }
        return p;
    }

    /** SNR (dB) of out against in at the best delay (Opus delays the sound a few ms). */
    static double snr(short[] in, short[] out) {
        double best = -99;
        for (int lag = 0; lag < 400; lag++) {
            double s = 0, e = 0;
            for (int i = 2000; i + lag < out.length && i < in.length - 2000; i++) {
                s += (double) in[i] * in[i];
                double d = in[i] - out[i + lag];
                e += d * d;
            }
            best = Math.max(best, 10 * Math.log10(s / e));
        }
        return best;
    }

    /** Amplitude of one frequency (Goertzel) over the middle of the signal. */
    static double tone(short[] x, int hz) {
        double w = 2 * Math.PI * hz / 16000.0, c = 2 * Math.cos(w), s1 = 0, s2 = 0;
        int from = 4000, to = x.length - 4000;
        for (int i = from; i < to; i++) {
            double s0 = x[i] + c * s1 - s2;
            s2 = s1;
            s1 = s0;
        }
        return Math.sqrt(s1 * s1 + s2 * s2 - c * s1 * s2) / (to - from);
    }

    static short[] join(List<short[]> parts) {
        int n = 0;
        for (short[] p : parts) n += p.length;
        short[] all = new short[n];
        int at = 0;
        for (short[] p : parts) {
            System.arraycopy(p, 0, all, at, p.length);
            at += p.length;
        }
        return all;
    }

    public static void main(String[] a) {
        NetTest.check(Opus.ok(), "libfnopus loads");
        int frames = 50;  // 2 s
        List<byte[]> sent = new ArrayList<>();
        List<short[]> in = new ArrayList<>();
        VoiceCodec.Encoder enc = new VoiceCodec.Encoder();
        NetTest.check(enc.ok(), "the Opus encoder opens");
        long t0 = System.nanoTime();
        for (int f = 0; f < frames; f++) {
            short[] p = speechLike(f);
            in.add(p);
            sent.add(enc.encode(p));
        }
        double encMs = (System.nanoTime() - t0) / 1e6 / frames;
        enc.close();
        int bytes = 0;
        for (byte[] f : sent) bytes += f.length;
        NetTest.check(bytes / frames < 160, String.format("Opus frame: %d bytes on average, %.1f KB/s", bytes / frames, bytes / (frames * 0.04) / 1024));

        VoiceCodec.Decoder dec = new VoiceCodec.Decoder();
        List<short[]> out = new ArrayList<>();
        long t1 = System.nanoTime();
        for (byte[] f : sent) out.addAll(dec.accept(f));
        double decMs = (System.nanoTime() - t1) / 1e6 / frames;
        NetTest.check(out.size() == frames, "every frame decodes: " + out.size());
        double q = snr(join(in), join(out));
        short[] ji = join(in), jo = join(out);
        StringBuilder tones = new StringBuilder();
        boolean same = true;
        for (int hz : new int[] {220, 660, 1500, 2900}) {
            double db = 20 * Math.log10(tone(jo, hz) / tone(ji, hz));
            tones.append(String.format(" %d Hz %+.1f dB", hz, db));
            same &= Math.abs(db) < 3;
        }
        NetTest.check(same, "each tone keeps its loudness (±3 dB):" + tones + String.format("; waveform SNR %.1f dB", q));
        NetTest.check(encMs < 4 && decMs < 2, String.format("CPU: encode %.2f ms, decode %.2f ms per 40 ms frame (%.1f %% of one core)",
                encMs, decMs, (encMs + decMs) / 40 * 100));
        dec.close();

        // losses: one lost → FEC + the frame; two lost → concealment + FEC + the frame; repeats and late ones dropped
        dec = new VoiceCodec.Decoder();
        int[] plan = {0, 1, 3, 6, 6, 5, 7};
        int[] expect = {1, 1, 2, 3, 0, 0, 1};
        boolean ok = true;
        StringBuilder got = new StringBuilder();
        for (int i = 0; i < plan.length; i++) {
            int n = dec.accept(sent.get(plan[i])).size();
            got.append(n).append(' ');
            ok &= n == expect[i];
        }
        NetTest.check(ok, "lost frames made up, repeats and late frames dropped: " + got.toString().trim());
        int gap = dec.accept(sent.get(20)).size();
        NetTest.check(gap == 1, "a long gap (12 frames) is skipped, not filled: " + gap);
        dec.restart();
        NetTest.check(dec.accept(sent.get(2)).size() == 1, "after restart() the numbering starts again");
        dec.close();

        // only Opus: 0.172's ADPCM frames (codec 0) and unknown ones play nothing
        NetTest.check(new VoiceCodec.Decoder().accept(new byte[] {0, 0, 0, 1, 2, 3}).isEmpty()
                && new VoiceCodec.Decoder().accept(new byte[] {9, 0, 0, 1, 2}).isEmpty(), "ADPCM and unknown codecs are skipped");
        System.out.println(NetTest.fails == 0 ? "ALL PASS" : NetTest.fails + " FAILED");
        System.exit(NetTest.fails == 0 ? 0 : 1);
    }
}

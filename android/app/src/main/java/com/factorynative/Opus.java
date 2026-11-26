package com.factorynative;

/**
 * libopus 1.5.2 through libfnopus.so (fnopus/opus_jni.c, tools/build-opus.sh): the walkie-talkie's codec. Handles
 * are native pointers (0: none); one handle is used by one thread at a time. Without the library ok() is false and
 * the mic does not work (NetChat says so).
 */
final class Opus {
    private static final boolean OK;

    static {
        boolean ok;
        try {
            System.loadLibrary("fnopus");
            ok = true;
        } catch (UnsatisfiedLinkError e) {
            ok = false;
        }
        OK = ok;
    }

    private Opus() {}

    static boolean ok() {
        return OK;
    }

    static native long encCreate(int rate, int bitrate, int complexity);

    static native void encFree(long enc);

    /** n samples → bytes in out: their count, or < 0. */
    static native int encode(long enc, short[] pcm, int n, byte[] out);

    static native long decCreate(int rate);

    static native void decFree(long dec);

    /** data null: a lost packet made up; fec 1: the packet before this one from its FEC. Samples, or < 0. */
    static native int decode(long dec, byte[] data, int off, int len, short[] out, int fec);
}

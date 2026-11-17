package com.factorynative;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ArrayBlockingQueue;

/**
 * .xz decoding with liblzma (libfnxz.so, tools/build-xz.sh; from the Factory app): about 4x faster than the
 * pure-Java decoder (factorio demo 2.1.23: 28.5 s against 118 s). Decoded in its own thread, a few chunks ahead,
 * so the tar reader and the file writers don't wait for it.
 */
final class NativeXz {
    private static native long open();
    /** consumed << 32 | produced, bit 62 at the end of the data, or -(liblzma error). */
    private static native long decode(long handle, byte[] in, int inOff, int inLen, byte[] out, int outOff, int outLen, boolean inputEnded);
    private static native void close(long handle);

    private static Boolean loaded;

    static synchronized boolean available() {
        if (loaded == null) {
            try {
                System.loadLibrary("fnxz");
                loaded = true;
            } catch (Throwable e) {
                android.util.Log.w("FNative", "no native xz, the Java decoder is used", e);
                loaded = false;
            }
        }
        return loaded;
    }

    /** The decoded stream of [source]: native liblzma, read ahead in its own thread. */
    static InputStream decoder(InputStream source) throws IOException {
        return new Prefetch(new Decoder(source));
    }

    private static final class Decoder extends InputStream {
        private final InputStream source;
        private final byte[] in = new byte[1 << 20];
        private int inPos, inLen;
        private boolean inputEnded, finished;
        private long handle;

        Decoder(InputStream source) throws IOException {
            this.source = source;
            handle = open();
            if (handle == 0) throw new IOException("liblzma: no memory");
        }

        @Override
        public int read() throws IOException {
            byte[] b = new byte[1];
            return read(b, 0, 1) < 0 ? -1 : b[0] & 0xFF;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (finished) return -1;
            while (true) {
                if (inPos == inLen && !inputEnded) {
                    int n = source.read(in, 0, in.length);
                    if (n < 0) inputEnded = true;
                    else {
                        inPos = 0;
                        inLen = n;
                    }
                }
                long r = decode(handle, in, inPos, inLen - inPos, b, off, len, inputEnded);
                if (r < 0) throw new IOException("xz: liblzma error " + (-r));
                int consumed = (int) ((r >>> 32) & 0x3FFFFFFF), produced = (int) (r & 0xFFFFFFFFL);
                inPos += consumed;
                if ((r & (1L << 62)) != 0) finished = true;
                if (produced > 0) return produced;
                if (finished) return -1;
                if (inputEnded && consumed == 0) throw new IOException("xz: the archive is cut off");
            }
        }

        @Override
        public void close() throws IOException {
            if (handle != 0) {
                NativeXz.close(handle);
                handle = 0;
            }
            source.close();
        }
    }

    /** Reads [source] in its own thread, up to 8 chunks of 1 MiB ahead. */
    private static final class Prefetch extends InputStream {
        private static final byte[] END = new byte[0];
        private final ArrayBlockingQueue<Object> chunks = new ArrayBlockingQueue<>(8);
        private final Thread reader;
        private final InputStream source;
        private volatile boolean closed;
        private byte[] current;
        private int pos, len;

        Prefetch(InputStream source) {
            this.source = source;
            reader = new Thread(() -> {
                try {
                    while (!closed) {
                        byte[] b = new byte[1 << 20];
                        int n = 0;
                        while (n < b.length) {
                            int r = source.read(b, n, b.length - n);
                            if (r < 0) break;
                            n += r;
                        }
                        if (n > 0) put(n == b.length ? b : java.util.Arrays.copyOf(b, n));
                        if (n < b.length) break;
                    }
                    put(END);
                } catch (Throwable e) {
                    try {
                        put(e);
                    } catch (InterruptedException ignored) {
                        // closed
                    }
                }
            }, "fnative-xz");
            reader.start();
        }

        private void put(Object o) throws InterruptedException {
            while (!closed && !chunks.offer(o, 200, java.util.concurrent.TimeUnit.MILLISECONDS)) { }
        }

        @Override
        public int read() throws IOException {
            byte[] b = new byte[1];
            return read(b, 0, 1) < 0 ? -1 : b[0] & 0xFF;
        }

        @Override
        public int read(byte[] b, int off, int n) throws IOException {
            while (current == null || pos == len) {
                if (current == END) return -1;
                Object o;
                try {
                    o = chunks.take();
                } catch (InterruptedException e) {
                    throw new java.io.InterruptedIOException();
                }
                if (o instanceof Throwable) {
                    current = END;
                    Throwable t = (Throwable) o;
                    throw t instanceof IOException ? (IOException) t : new IOException(t);
                }
                current = (byte[]) o;
                pos = 0;
                len = current.length;
                if (current == END) return -1;
            }
            int c = Math.min(n, len - pos);
            System.arraycopy(current, pos, b, off, c);
            pos += c;
            return c;
        }

        @Override
        public void close() throws IOException {
            closed = true;
            reader.interrupt();
            source.close();
        }
    }
}

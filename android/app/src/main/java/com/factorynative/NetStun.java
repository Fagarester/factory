package com.factorynative;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.security.SecureRandom;

/**
 * STUN binding requests (RFC 5389): a public server answers with the address and port it saw, i.e. the outside
 * address of our UDP socket behind NATs. Only the address goes there, never game traffic.
 */
final class NetStun {
    static final int COOKIE = 0x2112A442;
    private static final SecureRandom RNG = new SecureRandom();

    private NetStun() {}

    /** A binding request; txid (12 bytes) is filled in. */
    static byte[] request(byte[] txid) {
        RNG.nextBytes(txid);
        ByteBuffer b = ByteBuffer.allocate(20);
        b.putShort((short) 0x0001).putShort((short) 0).putInt(COOKIE).put(txid);
        return b.array();
    }

    /** Is this datagram a STUN message (the first two bits are 0 and the cookie is there)? Ours start with 0xF_. */
    static boolean isStun(byte[] p, int len) {
        return len >= 20 && (p[0] & 0xC0) == 0 && ByteBuffer.wrap(p, 4, 4).getInt() == COOKIE;
    }

    static boolean sameTx(byte[] p, byte[] txid) {
        for (int i = 0; i < 12; i++)
            if (p[8 + i] != txid[i]) return false;
        return true;
    }

    /** The mapped address of a binding success response, or null. */
    static InetSocketAddress mapped(byte[] p, int len) {
        if (!isStun(p, len) || (((p[0] & 0xff) << 8) | (p[1] & 0xff)) != 0x0101) return null;
        int end = Math.min(len, 20 + (((p[2] & 0xff) << 8) | (p[3] & 0xff)));
        InetSocketAddress plain = null;
        for (int i = 20; i + 4 <= end; ) {
            int type = ((p[i] & 0xff) << 8) | (p[i + 1] & 0xff), alen = ((p[i + 2] & 0xff) << 8) | (p[i + 3] & 0xff);
            int v = i + 4;
            if (v + alen > end) break;
            if ((type == 0x0020 || type == 0x0001) && alen >= 8) {
                boolean xor = type == 0x0020;
                int fam = p[v + 1];
                int port = ((p[v + 2] & 0xff) << 8) | (p[v + 3] & 0xff);
                if (xor) port ^= COOKIE >>> 16;
                int n = fam == 1 ? 4 : fam == 2 ? 16 : 0;
                if (n > 0 && alen >= 4 + n) {
                    byte[] a = new byte[n];
                    for (int k = 0; k < n; k++) {
                        a[k] = p[v + 4 + k];
                        if (xor) a[k] ^= p[4 + k];  // the cookie, then the transaction id
                    }
                    try {
                        InetSocketAddress s = new InetSocketAddress(InetAddress.getByAddress(a), port);
                        if (xor) return s;
                        plain = s;
                    } catch (java.net.UnknownHostException ignored) {
                        // bad length
                    }
                }
            }
            i = v + ((alen + 3) & ~3);
        }
        return plain;
    }
}

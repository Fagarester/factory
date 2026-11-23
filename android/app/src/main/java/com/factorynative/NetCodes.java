package com.factorynative;

import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * The texts inside the QR codes. Invite (host → guest): "FNJ1" + base64url(version, flags, room id, 32-byte secret,
 * expiry, candidate addresses). Answer (guest → host, when the host must knock first): "FNA1" + base64url(version,
 * room id, guest id, candidates, MAC with the room key). The secret travels only in the invite: whoever has the QR
 * may join (and, with a PIN, also knows the PIN).
 */
final class NetCodes {
    static final int LAN = 1, IPV6 = 2, UPNP = 3, STUN = 4, SEEN = 5, NET4 = 6;
    static final String[] TYPE_NAMES = {"?", "локальная сеть", "IPv6", "роутер (UPnP)", "пробивка NAT", "адрес отправителя",
            "интернет (IPv4)"};

    static final class Cand {
        final int type;
        final InetSocketAddress addr;

        Cand(int type, InetSocketAddress addr) {
            this.type = type;
            this.addr = addr;
        }

        @Override public String toString() {
            return TYPE_NAMES[type] + " " + NetTunnel.str(addr);
        }
    }

    static final class Invite {
        int flags;              // bit 0: a PIN is asked
        byte[] room = new byte[4];
        byte[] secret = new byte[32];
        long expiry;            // unix seconds, 0 = none
        // version 2: the host's settings the guest follows (no presets on the guest's side)
        int pingSeconds = 1, punchSeconds = 120;
        int paths = 15;         // bits: 1 LAN, 2 IPv6, 4 UPnP, 8 STUN — what the guest offers in its answer
        List<Cand> cands = new ArrayList<>();

        boolean pin() {
            return (flags & 1) != 0;
        }
    }

    static final class Answer {
        byte[] room = new byte[4];
        long guestId;
        List<Cand> cands = new ArrayList<>();
        byte[] signed;  // the bytes the MAC covers, and the MAC
        byte[] mac;
    }

    private NetCodes() {}

    static String encode(Invite v) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(2);
        o.write(v.flags);
        o.write(v.room, 0, 4);
        o.write(v.secret, 0, 32);
        putInt(o, (int) v.expiry);
        o.write(v.pingSeconds);
        o.write(v.punchSeconds >> 8);
        o.write(v.punchSeconds);
        o.write(v.paths);
        putCands(o, v.cands);
        return "FNJ1" + Base64.getUrlEncoder().withoutPadding().encodeToString(o.toByteArray());
    }

    /** The invite in a scanned or pasted text, or null. */
    static Invite decodeInvite(String text) {
        byte[] b = body(text, "FNJ1");
        if (b == null) return null;
        try {
            ByteBuffer in = ByteBuffer.wrap(b);
            int ver = in.get();
            if (ver != 1 && ver != 2) return null;
            Invite v = new Invite();
            v.flags = in.get() & 0xff;
            in.get(v.room);
            in.get(v.secret);
            v.expiry = in.getInt() & 0xffffffffL;
            if (ver >= 2) {
                v.pingSeconds = Math.max(1, Math.min(25, in.get() & 0xff));
                v.punchSeconds = Math.max(5, in.getShort() & 0xffff);
                v.paths = in.get() & 0xff;
            }
            v.cands = getCands(in);
            return v;
        } catch (RuntimeException e) {
            return null;
        }
    }

    static String encode(Answer a, byte[] key) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(1);
        o.write(a.room, 0, 4);
        putLong(o, a.guestId);
        putCands(o, a.cands);
        byte[] body = o.toByteArray();
        byte[] mac = NetTunnel.mac(key, body);
        o.write(mac, 0, 16);
        return "FNA1" + Base64.getUrlEncoder().withoutPadding().encodeToString(o.toByteArray());
    }

    /** The answer in a text, or null; the MAC is checked by the host (NetTunnel.acceptAnswer). */
    static Answer decodeAnswer(String text) {
        byte[] b = body(text, "FNA1");
        if (b == null || b.length < 1 + 4 + 8 + 1 + 16) return null;
        try {
            ByteBuffer in = ByteBuffer.wrap(b, 0, b.length - 16);
            if (in.get() != 1) return null;
            Answer a = new Answer();
            in.get(a.room);
            a.guestId = in.getLong();
            a.cands = getCands(in);
            a.signed = java.util.Arrays.copyOf(b, b.length - 16);
            a.mac = java.util.Arrays.copyOfRange(b, b.length - 16, b.length);
            return a;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Which code a text holds: 'J' invite, 'A' answer, 0 neither. */
    static char kind(String text) {
        String t = text == null ? "" : text.trim();
        int i = t.indexOf("FNJ1"), j = t.indexOf("FNA1");
        if (i >= 0 && (j < 0 || i < j)) return 'J';
        return j >= 0 ? 'A' : 0;
    }

    private static byte[] body(String text, String magic) {
        if (text == null) return null;
        int i = text.indexOf(magic);
        if (i < 0) return null;
        int e = i + 4;
        while (e < text.length() && (Character.isLetterOrDigit(text.charAt(e)) || text.charAt(e) == '-' || text.charAt(e) == '_')) e++;
        try {
            return Base64.getUrlDecoder().decode(text.substring(i + 4, e));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static void putCands(ByteArrayOutputStream o, List<Cand> cands) {
        int n = Math.min(cands.size(), 12);
        o.write(n);
        for (int i = 0; i < n; i++) {
            Cand c = cands.get(i);
            byte[] a = c.addr.getAddress().getAddress();
            o.write(c.type);
            o.write(a.length);
            o.write(a, 0, a.length);
            o.write(c.addr.getPort() >> 8);
            o.write(c.addr.getPort());
        }
    }

    private static List<Cand> getCands(ByteBuffer in) {
        int n = in.get() & 0xff;
        List<Cand> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            int type = in.get() & 0xff, len = in.get() & 0xff;
            if (len != 4 && len != 16) throw new IllegalArgumentException("address length");
            byte[] a = new byte[len];
            in.get(a);
            int port = in.getShort() & 0xffff;
            try {
                out.add(new Cand(type >= 1 && type <= NET4 ? type : SEEN, new InetSocketAddress(InetAddress.getByAddress(a), port)));
            } catch (java.net.UnknownHostException e) {
                throw new IllegalArgumentException(e);
            }
        }
        return out;
    }

    static void putInt(ByteArrayOutputStream o, int v) {
        o.write(v >>> 24);
        o.write(v >>> 16);
        o.write(v >>> 8);
        o.write(v);
    }

    static void putLong(ByteArrayOutputStream o, long v) {
        putInt(o, (int) (v >>> 32));
        putInt(o, (int) v);
    }
}

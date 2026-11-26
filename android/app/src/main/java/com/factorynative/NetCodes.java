package com.factorynative;

import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * The texts inside the QR codes, both encrypted (AES-256-GCM): without the key they are random bytes, also to
 * someone who knows this code.
 * Invite (host → guest): "FNJ2" + base64url(16-byte salt, 12-byte nonce, sealed(version, flags, room id, 32-byte
 * secret, expiry, settings, candidate addresses)); the key comes from a password the host gives for each invite
 * (PBKDF2-HMAC-SHA256, PASSWORD_ROUNDS), told apart from the QR (by voice, a message).
 * Answer (guest → host, when the host must knock first): "FNA2" + base64url(room id, 12-byte nonce, sealed(version,
 * guest id, candidates)); the key comes from the room key (the guest's join key). Until 0.159 both were plain
 * ("FNJ1"/"FNA1"): a seen QR gave away the room key and the host's addresses.
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
    }

    private NetCodes() {}

    static final int PASSWORD_ROUNDS = 200_000;  // PBKDF2: a guess costs this many HMACs (offline guessing)
    private static final String PASSWORD_CHARS = "abcdefghijkmnpqrstuvwxyz23456789";  // no 0/o, 1/l: read aloud

    /** A new invite password: 12 characters in groups of 4 (32 symbols: 60 bits), e.g. "k7mq-2xp9-ra4v". */
    static String newPassword(java.security.SecureRandom rng) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 12; i++) {
            if (i > 0 && i % 4 == 0) b.append('-');
            b.append(PASSWORD_CHARS.charAt(rng.nextInt(PASSWORD_CHARS.length())));
        }
        return b.toString();
    }

    /** Spaces, dashes and case do not matter when the password is typed. */
    static String normalPassword(String p) {
        return p == null ? "" : p.replaceAll("[\\s-]", "").toLowerCase(java.util.Locale.ROOT);
    }

    /** The invite key of a password (slow on purpose: about 0.1–0.5 s on a phone). */
    static byte[] passwordKey(String password, byte[] salt) {
        try {
            javax.crypto.SecretKeyFactory f = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            return f.generateSecret(new javax.crypto.spec.PBEKeySpec(normalPassword(password).toCharArray(), salt,
                    PASSWORD_ROUNDS, 256)).getEncoded();
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] seal(byte[] key, byte[] nonce, byte[] aad, byte[] plain) {
        try {
            javax.crypto.Cipher c = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
            c.init(javax.crypto.Cipher.ENCRYPT_MODE, new javax.crypto.spec.SecretKeySpec(key, "AES"),
                    new javax.crypto.spec.GCMParameterSpec(128, nonce));
            c.updateAAD(aad);
            return c.doFinal(plain);
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** null: wrong key or a changed text. */
    private static byte[] open(byte[] key, byte[] nonce, byte[] aad, byte[] sealed) {
        try {
            javax.crypto.Cipher c = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
            c.init(javax.crypto.Cipher.DECRYPT_MODE, new javax.crypto.spec.SecretKeySpec(key, "AES"),
                    new javax.crypto.spec.GCMParameterSpec(128, nonce));
            c.updateAAD(aad);
            return c.doFinal(sealed);
        } catch (java.security.GeneralSecurityException e) {
            return null;
        }
    }

    private static final byte[] INVITE_AAD = "FNJ2".getBytes(java.nio.charset.StandardCharsets.US_ASCII);

    /** The invite sealed with [key] (passwordKey of the invite's password and [salt]); a new nonce each time. */
    static String encode(Invite v, byte[] salt, byte[] key, java.security.SecureRandom rng) {
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
        byte[] nonce = new byte[12];
        rng.nextBytes(nonce);
        byte[] sealed = seal(key, nonce, INVITE_AAD, o.toByteArray());
        ByteBuffer out = ByteBuffer.allocate(16 + 12 + sealed.length);
        out.put(salt, 0, 16).put(nonce).put(sealed);
        return "FNJ2" + Base64.getUrlEncoder().withoutPadding().encodeToString(out.array());
    }

    /** The invite in a scanned or pasted text opened with the host's password, or null (not an invite, a wrong
     * password, a changed text). Slow: the password key (PASSWORD_ROUNDS); not on the UI thread. */
    static Invite decodeInvite(String text, String password) {
        byte[] b = body(text, "FNJ2");
        if (b == null || b.length < 16 + 12 + 16) return null;
        byte[] plain = open(passwordKey(password, java.util.Arrays.copyOf(b, 16)), java.util.Arrays.copyOfRange(b, 16, 28),
                INVITE_AAD, java.util.Arrays.copyOfRange(b, 28, b.length));
        if (plain == null) return null;
        try {
            ByteBuffer in = ByteBuffer.wrap(plain);
            int ver = in.get();
            if (ver != 2) return null;
            Invite v = new Invite();
            v.flags = in.get() & 0xff;
            in.get(v.room);
            in.get(v.secret);
            v.expiry = in.getInt() & 0xffffffffL;
            v.pingSeconds = Math.max(1, Math.min(25, in.get() & 0xff));
            v.punchSeconds = Math.max(5, in.getShort() & 0xffff);
            v.paths = in.get() & 0xff;
            v.cands = getCands(in);
            return v;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The answer key of a join key (the room key, or a known guest's own). */
    private static byte[] answerKey(byte[] joinKey) {
        return NetTunnel.mac(joinKey, "fnet answer".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    }

    static String encode(Answer a, byte[] joinKey, java.security.SecureRandom rng) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(2);
        putLong(o, a.guestId);
        putCands(o, a.cands);
        byte[] nonce = new byte[12];
        rng.nextBytes(nonce);
        byte[] sealed = seal(answerKey(joinKey), nonce, a.room, o.toByteArray());
        ByteBuffer out = ByteBuffer.allocate(4 + 12 + sealed.length);
        out.put(a.room, 0, 4).put(nonce).put(sealed);
        return "FNA2" + Base64.getUrlEncoder().withoutPadding().encodeToString(out.array());
    }

    /** The room id of an answer text (in the clear: which invite it answers), or null. */
    static byte[] answerRoom(String text) {
        byte[] b = body(text, "FNA2");
        return b == null || b.length < 4 + 12 + 16 ? null : java.util.Arrays.copyOf(b, 4);
    }

    /** The answer opened with [joinKey], or null (not an answer, another key, a changed text). */
    static Answer openAnswer(String text, byte[] joinKey) {
        byte[] b = body(text, "FNA2");
        if (b == null || b.length < 4 + 12 + 16) return null;
        byte[] room = java.util.Arrays.copyOf(b, 4);
        byte[] plain = open(answerKey(joinKey), java.util.Arrays.copyOfRange(b, 4, 16), room, java.util.Arrays.copyOfRange(b, 16, b.length));
        if (plain == null) return null;
        try {
            ByteBuffer in = ByteBuffer.wrap(plain);
            if (in.get() != 2) return null;
            Answer a = new Answer();
            a.room = room;
            a.guestId = in.getLong();
            a.cands = getCands(in);
            return a;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Which code a text holds: 'J' invite, 'A' answer, 0 neither. */
    static char kind(String text) {
        String t = text == null ? "" : text.trim();
        int i = t.indexOf("FNJ2"), j = t.indexOf("FNA2");
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

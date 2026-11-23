package com.factorynative;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Host-side test of NetTunnel on loopback: java -cp classes com.factorynative.NetTest */
public class NetTest {
    static int fails;
    static final InetAddress LO;

    static {
        try {
            LO = InetAddress.getByName("127.0.0.1");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    static void check(boolean ok, String what) {
        System.out.println((ok ? "PASS " : "FAIL ") + what);
        if (!ok) fails++;
    }

    static class L implements NetTunnel.Listener {
        final String tag;
        final List<Long> joins = new ArrayList<>();
        L(String tag) { this.tag = tag; }
        public void log(String s) { System.out.println("  [" + tag + "] " + s); }
        public void changed() {}
        public synchronized void joinRequest(long gid, String name) { joins.add(gid); }
    }

    /** The fake game server: answers "S:" + payload; counts packets and sources. */
    static class Server extends Thread {
        final DatagramSocket s;
        final AtomicInteger got = new AtomicInteger();
        final Set<Integer> ports = ConcurrentHashMap.newKeySet();
        Server(int port) throws Exception {
            s = new DatagramSocket(new InetSocketAddress(LO, port));
            setDaemon(true);
            start();
        }
        public void run() {
            byte[] b = new byte[70000];
            try {
                for (;;) {
                    DatagramPacket p = new DatagramPacket(b, b.length);
                    s.receive(p);
                    got.incrementAndGet();
                    ports.add(p.getPort());
                    byte[] r = ("S:" + new String(b, 0, p.getLength(), StandardCharsets.ISO_8859_1)).getBytes(StandardCharsets.ISO_8859_1);
                    if (p.getLength() == 3 && (b[0] & 0x1f) == 0) r = new byte[] {0x21, b[1], b[2]};  // Factorio's PingReply
                    s.send(new DatagramPacket(r, r.length, p.getSocketAddress()));
                }
            } catch (Exception e) {
                // closed
            }
        }
    }

    /** A game client: sends to the guest's local port, returns the answer or null. */
    static String roundTrip(int localPort, String msg) throws Exception {
        try (DatagramSocket c = new DatagramSocket()) {
            c.setSoTimeout(400);
            byte[] m = msg.getBytes(StandardCharsets.ISO_8859_1);
            for (int i = 0; i < 8; i++) {
                c.send(new DatagramPacket(m, m.length, LO, localPort));
                try {
                    byte[] b = new byte[70000];
                    DatagramPacket p = new DatagramPacket(b, b.length);
                    c.receive(p);
                    return new String(b, 0, p.getLength(), StandardCharsets.ISO_8859_1);
                } catch (java.net.SocketTimeoutException e) {
                    // again
                }
            }
        }
        return null;
    }

    static boolean waitState(NetTunnel t, int st, int ms) throws Exception {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            if (t.state == st) return true;
            Thread.sleep(20);
        }
        return t.state == st;
    }

    static NetConfig lanCfg(int gamePort, int localPort) {
        NetConfig c = NetConfig.preset(1);
        c.gamePort = gamePort;
        c.localPort = localPort;
        c.punchSeconds = 6;
        return c;
    }

    /** Invite with the host's candidates replaced by 127.0.0.1:port (proot's interfaces may be odd). */
    static NetCodes.Invite inviteTo(NetTunnel host, int port) {
        NetCodes.Invite v = NetCodes.decodeInvite(host.inviteText);
        v.cands.clear();
        v.cands.add(new NetCodes.Cand(NetCodes.LAN, new InetSocketAddress(LO, port)));
        return v;
    }

    static NetTunnel hostUp(NetConfig c) throws Exception {
        NetTunnel h = new NetTunnel(c, "host", new L("host"));
        h.start();
        check(waitState(h, NetTunnel.HOSTING, 8000) && h.inviteText != null, "host makes an invite");
        return h;
    }

    public static void main(String[] a) throws Exception {
        Server srv = new Server(45197);

        // 1. two guests, LAN preset (no approval)
        NetTunnel h = hostUp(lanCfg(45197, 0));
        NetCodes.Invite parsed = NetCodes.decodeInvite(h.inviteText);
        check(parsed != null && !parsed.pin(), "invite text decodes: " + h.inviteText.length() + " chars, cands " + (parsed == null ? null : parsed.cands));
        NetTunnel g1 = new NetTunnel(lanCfg(45197, 46200), "guest1", inviteTo(h, h.boundPort), "", new L("g1"));
        g1.start();
        check(waitState(g1, NetTunnel.CONNECTED, 3000), "guest 1 connects");
        check("S:hello".equals(roundTrip(46200, "hello")), "guest 1 game packet goes to the host's game and back");
        NetTunnel g2 = new NetTunnel(lanCfg(45197, 46201), "guest2", inviteTo(h, h.boundPort), "", new L("g2"));
        g2.start();
        check(waitState(g2, NetTunnel.CONNECTED, 3000), "guest 2 connects");
        check("S:two".equals(roundTrip(46201, "two")), "guest 2 round trip");
        check(srv.ports.size() == 2, "the game sees two players from two sockets: " + srv.ports);
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 1400; i++) big.append((char) ('a' + i % 26));
        check(("S:" + big).equals(roundTrip(46200, big.toString())), "1400-byte packet");
        Thread.sleep(2500);
        check(h.stats.size() == 2 && h.stats.get(0).rttMs >= 0, "host statistics: 2 players, rtt " + (h.stats.isEmpty() ? -1 : h.stats.get(0).rttMs) + " ms");

        // 2. duplicated, garbage and tampered packets through a middlebox
        Middle m = new Middle(new InetSocketAddress(LO, h.boundPort));
        NetTunnel g3 = new NetTunnel(lanCfg(45197, 46202), "guest3", inviteTo(h, m.port()), "", new L("g3"));
        g3.start();
        check(waitState(g3, NetTunnel.CONNECTED, 3000), "guest 3 connects through the duplicating middlebox");
        int before = srv.got.get();
        long foreignBefore = h.foreign;
        String r = roundTrip(46202, "dup");
        Thread.sleep(300);
        check("S:dup".equals(r), "round trip through the middlebox");
        check(srv.got.get() - before == 1, "duplicate delivered once (game got " + (srv.got.get() - before) + ")");
        long replays = 0;
        for (NetTunnel.PeerStat s : h.stats) replays += s.replays;
        Thread.sleep(1200);
        replays = 0;
        for (NetTunnel.PeerStat s : h.stats) replays += s.replays;
        check(replays > 0, "replays counted: " + replays);
        m.tamper = true;
        before = srv.got.get();
        try (DatagramSocket c = new DatagramSocket()) {
            byte[] msg = "bad".getBytes();
            c.send(new DatagramPacket(msg, msg.length, LO, 46202));
        }
        Thread.sleep(500);
        check(srv.got.get() == before && h.foreign > foreignBefore, "tampered packet dropped (foreign " + foreignBefore + " -> " + h.foreign + ")");
        m.tamper = false;
        // a stranger: random bytes and a fake HELLO to the host port
        try (DatagramSocket c = new DatagramSocket()) {
            c.setSoTimeout(500);
            byte[] junk = NetTunnel.random(80);
            junk[0] = (byte) 0xF1;
            junk[1] = 1;
            c.send(new DatagramPacket(junk, junk.length, LO, h.boundPort));
            boolean answered;
            try {
                c.receive(new DatagramPacket(new byte[2000], 2000));
                answered = true;
            } catch (java.net.SocketTimeoutException e) {
                answered = false;
            }
            check(!answered, "a fake HELLO gets no answer at all");
        }
        g3.stop();
        m.close();

        // 3. kick
        h.kick(g2.stats.isEmpty() ? 0 : findGid(h, "guest2"), false);
        check(waitState(g2, NetTunnel.FAILED, 2000) && g2.status.contains("отключил"), "kicked guest learns it: " + g2.status);
        g1.stop();
        h.stop();
        check(h.state == NetTunnel.STOPPED, "host stops");

        // 4. PIN
        NetConfig pc = lanCfg(45197, 0);
        pc.pin = true;
        h = hostUp(pc);
        check(h.pinCode.length() == 6 && NetCodes.decodeInvite(h.inviteText).pin(), "PIN made: " + h.pinCode);
        String wrong = h.pinCode.equals("000000") ? "111111" : "000000";
        NetConfig gc = lanCfg(45197, 46203);
        gc.punchSeconds = 2;
        g1 = new NetTunnel(gc, "wrongpin", inviteTo(h, h.boundPort), wrong, new L("wp"));
        g1.start();
        check(!waitState(g1, NetTunnel.CONNECTED, 1500) && h.foreign > 0, "wrong PIN: no connection, host counts " + h.foreign + " foreign");
        g1.stop();
        g1 = new NetTunnel(lanCfg(45197, 46203), "rightpin", inviteTo(h, h.boundPort), h.pinCode, new L("rp"));
        g1.start();
        check(waitState(g1, NetTunnel.CONNECTED, 3000) && "S:pin".equals(roundTrip(46203, "pin")), "right PIN connects");
        g1.stop();
        h.stop();

        // 5. approval, refusal, player limit, one-use invite
        NetConfig ac = lanCfg(45197, 0);
        ac.approve = true;
        ac.maxPlayers = 2;
        ac.oneUse = true;
        L hl = new L("host");
        h = new NetTunnel(ac, "host", hl);
        h.start();
        waitState(h, NetTunnel.HOSTING, 8000);
        NetCodes.Invite inv1 = inviteTo(h, h.boundPort);
        g1 = new NetTunnel(lanCfg(45197, 46204), "asks", inv1, "", new L("a1"));
        g1.start();
        Thread.sleep(800);
        check(g1.state == NetTunnel.CONNECTING && g1.status.contains("подтвердить") && hl.joins.size() == 1, "waits for approval: " + g1.status);
        String text1 = h.inviteText;
        h.approve(hl.joins.get(0), true);
        check(waitState(g1, NetTunnel.CONNECTED, 2000), "approved guest connects");
        Thread.sleep(200);
        check(!text1.equals(h.inviteText), "one-use invite replaced after the join");
        NetConfig short1 = lanCfg(45197, 46205);
        short1.punchSeconds = 2;
        g2 = new NetTunnel(short1, "old invite", inv1, "", new L("a2"));
        g2.start();
        check(!waitState(g2, NetTunnel.CONNECTED, 2500), "the used invite lets nobody else in");
        g2.stop();
        g2 = new NetTunnel(lanCfg(45197, 46205), "refused", inviteTo(h, h.boundPort), "", new L("a3"));
        g2.start();
        Thread.sleep(800);
        h.approve(hl.joins.get(hl.joins.size() - 1), false);
        check(waitState(g2, NetTunnel.FAILED, 2000) && g2.status.contains("отказал"), "refused guest learns it: " + g2.status);
        g2.stop();
        NetTunnel g4 = new NetTunnel(lanCfg(45197, 46206), "third", inviteTo(h, h.boundPort), "", new L("a4"));
        g4.start();
        Thread.sleep(800);
        h.approve(hl.joins.get(hl.joins.size() - 1), true);
        waitState(g4, NetTunnel.CONNECTED, 2000);
        NetTunnel g5 = new NetTunnel(lanCfg(45197, 46207), "fourth", inviteTo(h, h.boundPort), "", new L("a5"));
        g5.start();
        check(waitState(g5, NetTunnel.FAILED, 2000) && g5.status.contains("мест"), "limit of 2 players: " + g5.status);
        g5.stop();
        h.inviteExpiry = System.currentTimeMillis() / 1000 - 1;
        g5 = new NetTunnel(lanCfg(45197, 46207), "late", inviteTo(h, h.boundPort), "", new L("a6"));
        h.kick(findGid(h, "third"), false);
        Thread.sleep(300);
        g5.start();
        check(waitState(g5, NetTunnel.FAILED, 2000) && g5.status.contains("устарело"), "expired invite: " + g5.status);
        g5.stop();
        g4.stop();
        h.stop();
        check(waitState(g1, NetTunnel.FAILED, 2000), "host stop tells the guests: " + g1.status);
        g1.stop();

        // 6. the answer code: the host's address in the invite is dead, the host knocks first
        h = hostUp(lanCfg(45197, 0));
        NetCodes.Invite dead = inviteTo(h, 9);  // discard port: nothing answers
        NetConfig lc = lanCfg(45197, 46208);
        lc.punchSeconds = 20;
        g1 = new NetTunnel(lc, "answer", dead, "", new L("an"));
        g1.start();
        long end = System.currentTimeMillis() + 5000;
        while (g1.answerText == null && System.currentTimeMillis() < end) Thread.sleep(50);
        check(g1.answerText != null && NetCodes.kind(g1.answerText) == 'A', "guest makes an answer code: " + g1.answerText);
        NetCodes.Answer ans = NetCodes.decodeAnswer(g1.answerText);
        // proot's interfaces: point the answer at loopback, re-signed by the guest's key is not possible here, so
        // check the MAC refusal first, then use a properly signed loopback answer
        String bad = g1.answerText.substring(0, g1.answerText.length() - 3) + (g1.answerText.endsWith("A") ? "BBB" : "AAA");
        check(h.acceptAnswer(bad) != null, "a forged answer is refused: " + h.acceptAnswer(bad));
        ans.cands.clear();
        ans.cands.add(new NetCodes.Cand(NetCodes.LAN, new InetSocketAddress(LO, g1.boundPort)));
        String lo = NetCodes.encode(ans, NetTunnel.roomKey(dead.secret, null));
        check(h.acceptAnswer(lo) == null, "a signed answer is accepted");
        check(waitState(g1, NetTunnel.CONNECTED, 3000), "host knocked, guest connected: " + g1.status);
        check("S:punch".equals(roundTrip(46208, "punch")), "round trip after the knock");
        g1.stop();
        h.stop();
        srv.s.close();
        System.out.println(fails == 0 ? "ALL PASSED" : fails + " FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }

    static long findGid(NetTunnel h, String name) {
        for (NetTunnel.PeerStat s : h.stats) if (s.name.equals(name)) return s.gid;
        return 0;
    }

    /** A UDP middlebox: client side port; forwards both ways, sends every datagram twice, can flip a byte. */
    static class Middle {
        final DatagramSocket front;
        volatile DatagramSocket back;
        final InetSocketAddress server;
        volatile InetSocketAddress client;
        volatile boolean tamper, drop;
        /** A new outside address towards the host (the phone moved to another network). */
        void rebind() throws Exception {
            DatagramSocket old = back, b = new DatagramSocket();
            b.connect(server);
            back = b;
            Thread t = new Thread(() -> pump(b, false));
            t.setDaemon(true);
            t.start();
            old.close();
        }
        Middle(InetSocketAddress server) throws Exception {
            this.server = server;
            front = new DatagramSocket(new InetSocketAddress(LO, 0));
            back = new DatagramSocket();
            back.connect(server);
            Thread a = new Thread(() -> pump(front, true)), b = new Thread(() -> pump(back, false));
            a.setDaemon(true);
            b.setDaemon(true);
            a.start();
            b.start();
        }
        int port() { return front.getLocalPort(); }
        void pump(DatagramSocket from, boolean up) {
            byte[] buf = new byte[70000];
            try {
                for (;;) {
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    from.receive(p);
                    if (up) client = (InetSocketAddress) p.getSocketAddress();
                    if (drop) continue;
                    byte[] d = java.util.Arrays.copyOf(buf, p.getLength());
                    if (tamper && up && (d[0] & 0xff) == 0xF4 && d.length > 20) d[d.length - 20] ^= 1;
                    for (int i = 0; i < 2; i++) {
                        if (up) back.send(new DatagramPacket(d, d.length));
                        else if (client != null) front.send(new DatagramPacket(d, d.length, client));
                    }
                }
            } catch (Exception e) {
                // closed
            }
        }
        void close() {
            front.close();
            back.close();
        }
    }
}

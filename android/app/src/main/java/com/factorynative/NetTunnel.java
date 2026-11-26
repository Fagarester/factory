package com.factorynative;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * The network tunnel between FNative phones (gear menu, "Сеть"). One phone hosts: its game hosts a multiplayer
 * game on 127.0.0.1:gamePort, the tunnel brings each guest's packets there from its own loopback socket. A guest's
 * game connects to 127.0.0.1:localPort; the tunnel carries the packets to the host. No server of anyone else: the
 * QR invite holds the host's addresses and the room key.
 *
 * Security: every tunnel packet is AES-256-GCM with per-guest session keys (HMAC-SHA256 from the room key and both
 * sides' random nonces); handshakes carry a MAC with the room key (with a PIN mixed in when asked). Packets that do
 * not verify are dropped without an answer, so the port gives nothing away. Counters stop replays.
 *
 * Packets (first byte ≥ 0xF0, so STUN on the same socket is told apart):
 * HELLO   F1 ver room[4] gid[8] ts[8] nonceG[16] nameLen name mac[16]          guest → host, repeated (hole punching)
 * WELCOME F2 ver room[4] gid[8] nonceH[16] sid[4] mac[16] (mac also over nonceG) host → guest
 * WAIT    F3 room[4] gid[8] code mac[16] (also over nonceG): 1 approval, 2 refused, 3 full, 4 invite expired
 * DATA    F4 sid[4] ctr[8] AES-GCM(kind, payload): kind 0 game, 1 ping, 2 pong, 3 bye, 4 part of a big game packet,
 *         5 chat (nameLen name text, UTF-8), 7 voice (src[4] nameLen name frame; the frame starts with its codec:
 *         VoiceCodec; 6 was 0.172's ADPCM voice, skipped now), 8 typing (src[4] nameLen name: a player types a chat line); the host passes chat and voice on to the other players with the
 *         sender's name and sid (a guest's own name and src are not trusted). Older versions skip kinds they do not know.
 * PUNCH   F5 room[4] gid[8] ts[8] mac[16]  host → guest's answer addresses, opens the host's NAT; guest answers HELLO
 *
 * One thread (selector); the UI calls post work into it. Plain Java: tests run on the host.
 */
final class NetTunnel {
    interface Listener {
        void log(String line);

        void changed();  // state, invite, players or statistics changed (any thread)

        void joinRequest(long gid, String name);  // host: a player waits for approval

        default void chat(String from, String text) {}  // a chat line of another player

        default void voice(int src, String from, byte[] frame) {}  // a voice frame of another player (src: who)

        default void typing(int src, String from) {}  // another player types a chat line (repeated every ~2 s)
    }

    static final int GATHERING = 1, HOSTING = 2, CONNECTING = 3, CONNECTED = 4, FAILED = 5, STOPPED = 6;
    private static final int HELLO = 0xF1, WELCOME = 0xF2, WAIT = 0xF3, DATA = 0xF4, PUNCH = 0xF5;
    private static final int K_GAME = 0, K_PING = 1, K_PONG = 2, K_BYE = 3, K_PART = 4, K_CHAT = 5, K_VOICE = 7, K_TYPING = 8;
    static final int MAX_CHAT = 300;  // chars of one chat line (≤ 900 bytes: one datagram, no parts)
    // A game packet bigger than this goes in parts (K_PART: id[2] index[1] count[1] bytes), so no tunnel datagram
    // is over ~1180 bytes with IPv6 and UDP headers: VPNs (WireGuard 1420, many 1280 or less) and paths with a
    // small MTU drop bigger UDP datagrams silently, and the game sends the same size again (2026-11-25: a guest
    // behind a VPN downloaded the map but never caught up; the cause is not measured, this removes it).
    static final int MAX_PART = 1100;
    private static final SecureRandom RNG = new SecureRandom();

    final boolean host;
    final NetConfig cfg;
    private final Listener listener;
    private final String myName;
    volatile int state;
    volatile String status = "";
    volatile String natNote = "";  // what STUN / UPnP found
    volatile long foreign;          // packets that did not verify (other people, scanners, wrong PIN)
    volatile int boundPort;

    // host
    private NetCodes.Invite invite;
    private byte[] roomKey;          // the invite's key (with the PIN)
    volatile String inviteText, pinCode = "";
    // host: the invite's password (new with each invite; the host tells it apart from the QR) and its key;
    // read both with inviteAndPassword
    volatile String invitePassword = "";
    private byte[] inviteSalt, inviteSealKey;
    volatile long inviteExpiry;      // unix seconds, 0 = none
    private final Set<Long> refused = new HashSet<>();
    private final List<long[]> punchUntil = new ArrayList<>();  // {gid, deadline}
    private final Map<Long, List<NetCodes.Cand>> punchTargets = new LinkedHashMap<>();

    // guest
    private final NetCodes.Invite target;
    private final long guestId;
    private byte[] guestKey;
    volatile String answerText;
    private final List<NetCodes.Cand> helloTargets = new ArrayList<>();
    private long punchDeadline, lastHello, helloTs, connectStart, lastLanAnnounce, probeStart;
    volatile boolean probing;  // guest: the host went quiet (or the phone's network changed), looking for it again
    private byte[] nonceG;
    private DatagramChannel local;   // the guest game's side
    private SocketAddress gameAddr;  // the guest game's socket
    private SocketAddress pingAddr;  // the HUD's pinger (loader/netping.c): its PingMessage replies go back to it
    volatile long gameDropped;        // game packets while not connected

    private final List<NetCodes.Cand> myCands = Collections.synchronizedList(new ArrayList<>());
    private final Map<Long, Peer> peers = new LinkedHashMap<>();  // host: by gid; guest: the host
    volatile List<PeerStat> stats = new ArrayList<>();

    private DatagramChannel ext;
    private Selector selector;
    private Thread thread;
    private final ConcurrentLinkedQueue<Runnable> tasks = new ConcurrentLinkedQueue<>();
    private volatile boolean running;
    private NetUpnp upnp;
    private boolean upnpDone, stunDone, inviteMade;
    private long gatherStart, nextStun, lastStatsMs;
    private final Map<String, InetSocketAddress> stunSeen = new LinkedHashMap<>();  // server → mapped
    private final List<Object[]> stunPending = new ArrayList<>();  // {txid, server InetSocketAddress, name}
    private List<InetSocketAddress> stunResolved;

    private final ByteBuffer rx = ByteBuffer.allocate(65536);

    static final class Peer {
        long gid;
        int sid;
        String name = "";
        byte[] joinKey, room, nonceG, nonceH, kTx, kRx;  // room, joinKey: of the invite the player came with
        long helloTs;
        InetSocketAddress ep;
        int path;
        boolean active, pending, lost;  // lost: quiet longer than timeoutSeconds, kept for rejoinMinutes
        long ctrTx, rxMax = -1, rxMask;
        DatagramChannel game;  // host: towards the game server
        long pktIn, pktOut, bytesIn, bytesOut, replays, since, lastRx, lastPing, pendingSince;
        int pingSeq;
        int partId;  // the last game packet sent in parts
        final java.util.LinkedHashMap<Integer, byte[][]> parts = new java.util.LinkedHashMap<>();  // id -> parts in
        long pings, pongs;
        double rtt = -1, rttAvg = -1;
        long lastBytesIn, lastBytesOut;
        double rateIn, rateOut;
        boolean gameDown;  // the host's game does not listen on gamePort
        final Cipher enc, dec;

        Peer() {
            try {
                enc = Cipher.getInstance("AES/GCM/NoPadding");
                dec = Cipher.getInstance("AES/GCM/NoPadding");
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
    }

    /** What the UI shows of one connection. */
    static final class PeerStat {
        long gid;
        String name, path;  // no addresses: nothing on the screen may give them away (streams, screenshots)
        boolean pending, gameDown, lost;
        int rttMs, lossPct, idleS, minutes;
        long bytesIn, bytesOut, replays;
        double kbIn, kbOut;
    }

    /** A host. */
    NetTunnel(NetConfig cfg, String name, Listener l) {
        this.host = true;
        this.cfg = cfg;
        this.listener = l;
        this.myName = name;
        this.target = null;
        this.guestId = 0;
    }

    /** A guest of the host in the invite (pin: the digits the host told, or ""). */
    NetTunnel(NetConfig cfg, String name, NetCodes.Invite invite, String pin, Listener l) {
        this.host = false;
        this.cfg = cfg;
        this.listener = l;
        this.myName = name;
        this.target = invite;
        this.guestId = RNG.nextLong();
        this.guestKey = roomKey(invite.secret, invite.pin() ? pin : null);
    }

    // ------------------------------------------------------------------ public (any thread)

    void start() throws IOException {
        ext = DatagramChannel.open();
        ext.bind(new InetSocketAddress(cfg.port));
        ext.configureBlocking(false);
        boundPort = ((InetSocketAddress) ext.getLocalAddress()).getPort();
        selector = Selector.open();
        ext.register(selector, SelectionKey.OP_READ);
        if (!host) {
            local = DatagramChannel.open();
            try {
                local.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), cfg.localPort));
            } catch (IOException e) {
                ext.close();
                local.close();
                throw new IOException("порт для игры гостя занят (другая сеть FNative ещё работает?)");
            }
            local.configureBlocking(false);
            local.register(selector, SelectionKey.OP_READ, "local");
        }
        running = true;
        thread = new Thread(this::loop, host ? "fnet-host" : "fnet-guest");
        thread.setDaemon(true);
        thread.start();
    }

    void stop() {
        if (!running) return;
        post(() -> {
            for (Peer p : peers.values()) if (p.active && p.kTx != null) sendData(p, K_BYE, new byte[0]);
            running = false;
        });
        try {
            thread.join(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Host: lets a waiting player in (true) or refuses them. */
    void approve(long gid, boolean yes) {
        post(() -> {
            Peer p = peers.get(gid);
            if (p == null || !p.pending) return;
            p.pending = false;
            if (yes) activate(p);
            else {
                refused.add(gid);
                peers.remove(gid);
                sendWait(p, 2);
                log("отказ: " + p.name);
            }
            publish();
        });
    }

    /** Host: disconnects a player (they may come back with a valid invite unless refused). */
    void kick(long gid, boolean ban) {
        post(() -> {
            Peer p = peers.remove(gid);
            if (p == null) return;
            if (p.active) sendData(p, K_BYE, new byte[0]);
            closeGame(p);
            if (ban) refused.add(gid);
            log((ban ? "заблокирован: " : "отключён: ") + p.name);
            publish();
        });
    }

    /** Host: a new invite (new room key and PIN); the old one stops letting new players in. */
    void newInvite() {
        post(() -> {
            makeInvite();
            log("новое приглашение; старое больше не пускает новых игроков");
        });
    }

    /** Host: the guest's answer code; the host knocks at its addresses. null = fine, else the reason. */
    String acceptAnswer(String text) {
        byte[] room = NetCodes.answerRoom(text);
        if (room == null) return "это не ответный код FNative";
        String[] err = {null};
        Object lock = new Object();
        boolean[] done = {false};
        post(() -> {
            // the room key, or a known player's own join key (a player back after a break, from an older invite)
            NetCodes.Answer a = invite != null && Arrays.equals(room, invite.room) ? NetCodes.openAnswer(text, roomKey) : null;
            for (Peer p : peers.values())
                if (a == null) {
                    a = NetCodes.openAnswer(text, p.joinKey);
                    if (a != null && a.guestId != p.gid) a = null;
                }
            if (invite == null) err[0] = "код от другого приглашения";
            else if (a == null) err[0] = "код не от этого приглашения или изменён";
            else {
                punchTargets.put(a.guestId, a.cands);
                punchUntil.add(new long[] {a.guestId, now() + cfg.punchSeconds * 1000L});
                log("ответный код: стучусь к " + a.cands.size() + " адресам гостя");
            }
            synchronized (lock) {
                done[0] = true;
                lock.notifyAll();
            }
        });
        synchronized (lock) {
            long end = now() + 3000;
            while (!done[0] && now() < end) {
                try {
                    lock.wait(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        return done[0] ? err[0] : "туннель не отвечает";
    }

    /** Someone to talk to: a guest connected to the host, a host with a player that is on the line. */
    boolean linked() {
        if (!host && state != CONNECTED) return false;
        for (PeerStat s : stats) if (!s.pending && !s.lost) return true;
        return false;
    }

    /** A chat line to all the players (a guest: to the host, which passes it on). */
    void sendChat(String text) {
        String t = clean(text);
        if (t.isEmpty()) return;
        if (t.length() > MAX_CHAT) t = t.substring(0, MAX_CHAT);
        byte[] b = t.getBytes(StandardCharsets.UTF_8);
        post(() -> relayChat(null, myName, b));
    }

    /** A voice frame (NetVoice: codec[1] seq[2] data) to all the players. */
    void sendVoice(byte[] frame) {
        post(() -> relayNamed(null, K_VOICE, 0, myName, frame));
    }

    /** "I am typing" to all the players (NetChat repeats it while the line is not sent). */
    void sendTyping() {
        post(() -> relayNamed(null, K_TYPING, 0, myName, new byte[0]));
    }

    List<NetCodes.Cand> candidates() {
        synchronized (myCands) {
            return new ArrayList<>(myCands);
        }
    }

    // ------------------------------------------------------------------ the loop

    private void post(Runnable r) {
        tasks.add(r);
        if (selector != null) selector.wakeup();
    }

    private void loop() {
        gatherStart = now();
        setState(host ? GATHERING : CONNECTING, host ? "собираю адреса этого телефона…" : "соединяюсь с хостом…");
        gatherLocal();
        if (!host) {
            nonceG = random(16);
            helloTs = System.currentTimeMillis();
            connectStart = now();
            punchDeadline = now() + cfg.punchSeconds * 1000L;
            synchronized (helloTargets) {
                helloTargets.addAll(target.cands);
            }
            if (target.expiry != 0 && target.expiry < System.currentTimeMillis() / 1000)
                log("приглашение уже устарело по часам этого телефона — пробую всё равно");
            log("адреса хоста: " + target.cands);
        }
        startStun();
        startUpnp();
        try {
            while (running) {
                for (Runnable r; (r = tasks.poll()) != null; ) r.run();
                if (!running) break;
                selector.select(50);
                for (SelectionKey k : selector.selectedKeys()) {
                    if (!k.isValid() || !k.isReadable()) continue;
                    Object a = k.attachment();
                    if (a == null) readExt();
                    else if ("local".equals(a)) readLocal();
                    else readGame((Peer) a);
                }
                selector.selectedKeys().clear();
                tick();
            }
        } catch (Exception e) {
            log("ошибка туннеля: " + e);
            setState(FAILED, "ошибка: " + e.getMessage());
        } finally {
            for (Peer p : peers.values()) closeGame(p);
            if (upnp != null) {
                upnp.unmap(boundPort);
                log("UPnP: проброс порта снят");
            }
            closeQuietly(ext);
            closeQuietly(local);
            try {
                selector.close();
            } catch (IOException ignored) {
                // closing
            }
            if (state != FAILED) setState(STOPPED, "сеть остановлена");
            running = false;
        }
    }

    private void tick() {
        long t = now();
        if (host && invite == null && (stunDone && upnpDone || t - gatherStart > 6000)) makeInvite();  // once
        if (!host && answerText == null && stunDone && upnpDone) makeAnswer();
        if (stunResolved != null && cfg.stun && t >= nextStun && (host ? inviteMade : state == CONNECTING)) {
            sendStun();  // keeps the NAT mapping and notices a new outside address
            nextStun = t + 20000;
        }
        if (!stunDone && t - gatherStart > 2500) finishStun();
        if (host) {
            for (java.util.Iterator<long[]> it = punchUntil.iterator(); it.hasNext(); ) {
                long[] pu = it.next();
                Peer p = peers.get(pu[0]);
                if (t > pu[1] || p != null && p.active) {
                    it.remove();
                    continue;
                }
                if (t - lastHello >= 250) sendPunch(pu[0], punchTargets.get(pu[0]));
            }
            if (!punchUntil.isEmpty() && t - lastHello >= 250) lastHello = t;
            for (java.util.Iterator<Peer> it = peers.values().iterator(); it.hasNext(); ) {
                Peer p = it.next();
                if (p.pending && t - p.pendingSince > 120000) {
                    it.remove();
                    log("не дождался подтверждения: " + p.name);
                    continue;
                }
                if (!p.active) continue;
                if (t - p.lastRx > cfg.rejoinMinutes * 60000L) {
                    it.remove();
                    closeGame(p);
                    log("не вернулся за " + cfg.rejoinMinutes + " мин: " + p.name);
                    continue;
                }
                if (t - p.lastRx > cfg.timeoutSeconds * 1000L) {
                    // kept: approval, slot and the game socket stay, so the player comes back without asking and the
                    // game sees the same client
                    if (!p.lost) log("нет связи с " + p.name + ": жду возвращения до " + cfg.rejoinMinutes + " мин");
                    p.lost = true;
                    continue;
                }
                ping(p, t);
            }
        } else {
            Peer h = peers.get(0L);
            if (state == CONNECTING) {
                if (t - lastHello >= 250 && t < punchDeadline) {
                    lastHello = t;
                    sendHellos();
                }
                if (t >= punchDeadline) setState(FAILED, "не удалось соединиться за " + cfg.punchSeconds + " с");
                else if (t - connectStart > 3000 && answerText != null && !status.startsWith("хост"))
                    setStatus("напрямую хост не отвечает: покажи хосту ответный QR (или жди)");
            } else if (state == CONNECTED && h != null) {
                long quiet = t - h.lastRx;
                if (!probing && quiet > cfg.pingSeconds * 2000L + 500) startProbe("хост молчит " + quiet / 1000.0 + " с");
                if (probing) {
                    if (quiet < 1000 && t - probeStart > 3000) {
                        probing = false;  // the old path came back by itself
                        setStatus(connectedText());
                    } else if (t - probeStart > cfg.punchSeconds * 1000L) {
                        probing = false;
                        setState(FAILED, "связь с хостом потеряна: не нашёл его за " + cfg.punchSeconds + " с");
                    } else if (t - lastHello >= 250) {
                        lastHello = t;
                        sendHellos();
                    }
                }
                ping(h, t);
                announceLan(t);
            }
        }
        if (t - lastStatsMs >= 1000) {
            lastStatsMs = t;
            publish();
        }
    }

    /**
     * Guest: the host in the game's "Browse LAN games" list. A LAN server sends Factorio's LANBroadcastMessage to
     * UDP 34196 about once a second: 3 bytes, type 15 and the game port (little-endian); the list takes the sender's
     * address with that port (captured from a 2.1.19 server, 2026-11-23). Sent from 127.0.0.1:localPort to this phone
     * only, so the list shows 127.0.0.1:localPort — the tunnel.
     */
    private void announceLan(long t) {
        if (t - lastLanAnnounce < 1000) return;
        lastLanAnnounce = t;
        byte[] m = {0x0f, (byte) cfg.localPort, (byte) (cfg.localPort >> 8)};
        try {
            local.send(ByteBuffer.wrap(m), new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 34196));
        } catch (IOException ignored) {
            // the list is not open: nobody listens
        }
    }

    /**
     * Guest: looks for the host again while the session goes on. A fresh HELLO (new nonce, newer time) goes to the
     * host's last address and every invite address; the host knows this guest (no approval), makes new keys, takes the
     * new address, and keeps the game socket — the game sees the same player.
     */
    private void startProbe(String why) {
        probing = true;
        probeStart = now();
        lastHello = 0;
        nonceG = random(16);
        helloTs = System.currentTimeMillis();
        Peer h = peers.get(0L);
        if (h != null && h.ep != null) synchronized (helloTargets) {
            helloTargets.removeIf(c -> c.addr.equals(h.ep));
            helloTargets.add(0, new NetCodes.Cand(NetCodes.SEEN, h.ep));
        }
        setStatus("связь прервалась (" + why + "), восстанавливаю…");
    }

    /** The phone's network changed (Wi-Fi ↔ mobile, a new address): look for the other side at once. */
    void networkChanged() {
        post(() -> {
            synchronized (myCands) {
                myCands.removeIf(c -> c.type == NetCodes.LAN || c.type == NetCodes.IPV6 || c.type == NetCodes.NET4);
            }
            gatherLocal();
            nextStun = 0;
            if (host) {
                for (Peer p : peers.values()) p.lastPing = 0;  // pings from the new address at once: guests follow it
                if (inviteMade) {
                    invite.cands = orderedCands();
                    sealInvite();
                    listener.changed();
                }
            } else {
                if (answerText != null) answerText = null;  // made again with the new addresses
                if (state == CONNECTED) startProbe("сеть телефона сменилась");
                else lastHello = 0;
            }
        });
    }

    private String connectedText() {
        return "соединён; в игре: Мультиплеер → Локальные игры";
    }

    // ------------------------------------------------------------------ gathering

    private void gatherLocal() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback() || ni.getName().startsWith("dummy")) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    int cls = classify(a);
                    if (cls == NetCodes.LAN && cfg.lan && count(NetCodes.LAN) < 3 && !a.isLinkLocalAddress())
                        myCands.add(new NetCodes.Cand(NetCodes.LAN, new InetSocketAddress(a, boundPort)));
                    if (cls == NetCodes.IPV6 && cfg.ipv6 && count(NetCodes.IPV6) < 3)
                        myCands.add(new NetCodes.Cand(NetCodes.IPV6, new InetSocketAddress(stripScope(a), boundPort)));
                    if (cls == 0 && a instanceof Inet4Address && count(NetCodes.NET4) < 2)  // a public IPv4 on the phone itself
                        myCands.add(new NetCodes.Cand(NetCodes.NET4, new InetSocketAddress(a, boundPort)));
                }
            }
        } catch (Exception e) {
            log("адреса устройства: " + e);
        }
        log("порт туннеля " + boundPort + ", свои адреса: " + candidates());
    }

    private int count(int type) {
        int n = 0;
        synchronized (myCands) {
            for (NetCodes.Cand c : myCands) if (c.type == type) n++;
        }
        return n;
    }

    private void startStun() {
        if (!cfg.stun) {
            stunDone = true;
            return;
        }
        Thread t = new Thread(() -> {
            List<InetSocketAddress> list = new ArrayList<>();
            for (InetSocketAddress u : cfg.stunList()) {
                try {
                    for (InetAddress a : InetAddress.getAllByName(u.getHostString()))
                        if (a instanceof Inet4Address) {
                            list.add(new InetSocketAddress(a, u.getPort()));
                            break;
                        }
                } catch (Exception e) {
                    log("STUN " + u.getHostString() + ": не найден (" + e.getMessage() + ")");
                }
            }
            post(() -> {
                stunResolved = list;
                if (list.isEmpty()) finishStun();
                else {
                    sendStun();
                    nextStun = now() + 20000;
                }
            });
        }, "fnet-dns");
        t.setDaemon(true);
        t.start();
    }

    private void sendStun() {
        for (InetSocketAddress s : stunResolved) {
            byte[] tx = new byte[12];
            byte[] req = NetStun.request(tx);
            stunPending.add(new Object[] {tx, s});
            if (stunPending.size() > 32) stunPending.remove(0);
            send(req, s);
        }
    }

    private void onStun(byte[] p, int len, InetSocketAddress from) {
        for (Object[] sp : stunPending) {
            if (!NetStun.sameTx(p, (byte[]) sp[0])) continue;
            InetSocketAddress m = NetStun.mapped(p, len);
            if (m == null) return;
            InetSocketAddress server = (InetSocketAddress) sp[1];
            InetSocketAddress before = stunSeen.put(str(server), m);
            if (before != null && !before.equals(m)) log("STUN: внешний адрес сменился " + str(before) + " → " + str(m));
            if (stunDone && (before == null || !before.equals(m))) updateStunCand();
            if (!stunDone && stunSeen.size() >= stunResolved.size()) finishStun();
            return;
        }
    }

    private void finishStun() {
        if (stunDone) return;
        stunDone = true;
        updateStunCand();
        if (cfg.stun) {
            if (stunSeen.isEmpty()) natNote += "STUN не ответил (UDP в интернет закрыт или серверы недоступны). ";
            else {
                Set<Integer> ports = new HashSet<>();
                for (InetSocketAddress m : stunSeen.values()) ports.add(m.getPort());
                InetSocketAddress m = stunSeen.values().iterator().next();
                if (isLocal(m.getAddress())) natNote += "NAT нет: телефон виден из интернета напрямую. ";
                else if (stunSeen.size() < 2) natNote += "STUN: ответил 1 сервер, тип NAT неизвестен. ";
                else if (ports.size() == 1) natNote += "NAT «конусный»: пробивка вероятна. ";
                else natNote += "NAT симметричный (порт меняется для каждого адресата): пробивка вряд ли — нужен IPv6 или UPnP у одной из сторон. ";
            }
            log("STUN: " + stunSeen);
        }
    }

    private void updateStunCand() {
        synchronized (myCands) {
            myCands.removeIf(c -> c.type == NetCodes.STUN);
            if (!stunSeen.isEmpty()) {
                InetSocketAddress m = stunSeen.values().iterator().next();
                if (!isLocal(m.getAddress())) myCands.add(new NetCodes.Cand(NetCodes.STUN, m));
            }
        }
        if (host && inviteMade) {
            invite.cands = orderedCands();
            sealInvite();
            listener.changed();
        }
    }

    private void startUpnp() {
        if (!cfg.upnp) {
            upnpDone = true;
            return;
        }
        Thread t = new Thread(() -> {
            String[] why = {null};
            NetUpnp u = NetUpnp.find(2000, why);
            boolean mapped = u != null && u.map(boundPort);
            if (mapped && !running) {  // stopped meanwhile: no forgotten mapping on the router
                u.unmap(boundPort);
                return;
            }
            post(() -> {
                upnpDone = true;
                if (u == null) {
                    natNote += noAddr("UPnP: " + why[0]) + ". ";
                    log("UPnP: " + why[0]);
                    return;
                }
                if (!mapped) {
                    natNote += "UPnP: роутер отказал в пробросе. ";
                    log("UPnP: AddPortMapping не удался: " + u.error);
                    return;
                }
                upnp = u;
                if (!running) return;
                String cg = u.behindCgnat() ? " — но сам роутер за CGNAT провайдера, снаружи не достать" : "";
                natNote += "UPnP: порт на роутере проброшен" + cg + ". ";
                log("UPnP: проброшен порт " + boundPort + " (" + u.externalIp + ")" + cg);
                try {
                    if (!u.behindCgnat() && !u.externalIp.isEmpty())
                        myCands.add(new NetCodes.Cand(NetCodes.UPNP, new InetSocketAddress(InetAddress.getByName(u.externalIp), boundPort)));
                } catch (Exception ignored) {
                    // no address
                }
                if (host && inviteMade) {
                    invite.cands = orderedCands();
                    sealInvite();
                    listener.changed();
                }
            });
        }, "fnet-upnp");
        t.setDaemon(true);
        t.start();
    }

    /** LAN, IPv6, public IPv4, UPnP, STUN: the order the guest tries (all at once, the first answer wins). */
    private List<NetCodes.Cand> orderedCands() {
        List<NetCodes.Cand> out = new ArrayList<>();
        for (int type : new int[] {NetCodes.LAN, NetCodes.IPV6, NetCodes.NET4, NetCodes.UPNP, NetCodes.STUN})
            synchronized (myCands) {
                for (NetCodes.Cand c : myCands) {
                    boolean dup = false;
                    for (NetCodes.Cand o : out) dup |= o.addr.equals(c.addr);  // UPnP and STUN often give the same address
                    if (c.type == type && !dup) out.add(c);
                }
            }
        return out;
    }

    private void makeInvite() {
        NetCodes.Invite v = new NetCodes.Invite();
        RNG.nextBytes(v.room);
        RNG.nextBytes(v.secret);
        pinCode = cfg.pin ? String.format(java.util.Locale.ROOT, "%06d", RNG.nextInt(1000000)) : "";
        v.flags = cfg.pin ? 1 : 0;
        v.expiry = cfg.inviteMinutes > 0 ? System.currentTimeMillis() / 1000 + cfg.inviteMinutes * 60L : 0;
        v.pingSeconds = cfg.pingSeconds;
        v.punchSeconds = Math.min(65535, cfg.punchSeconds);
        v.paths = (cfg.lan ? 1 : 0) | (cfg.ipv6 ? 2 : 0) | (cfg.upnp ? 4 : 0) | (cfg.stun ? 8 : 0);
        v.cands = orderedCands();
        invite = v;
        roomKey = roomKey(v.secret, cfg.pin ? pinCode : null);
        inviteExpiry = v.expiry;
        passwordKeyed(NetCodes.newPassword(RNG));  // the first QR there: then the host is ready (HOSTING)
        listener.changed();
    }

    /** The invite under a new password. Its key is slow on purpose (PBKDF2, ~0.2–0.5 s): made on its own thread,
     * not this one (the players' packets would wait); then the password and the QR text change together here.
     * A newer password wins over a key still being made for an older one. */
    private long passwordSeq;

    private void passwordKeyed(String password) {
        long seq = ++passwordSeq;
        NetCodes.Invite forInvite = invite;
        Thread t = new Thread(() -> {
            byte[] salt = new byte[16];
            RNG.nextBytes(salt);
            byte[] key = NetCodes.passwordKey(password, salt);
            post(() -> {
                if (seq != passwordSeq || invite != forInvite || !running) return;
                synchronized (this) {
                    invitePassword = password;
                    inviteSalt = salt;
                    inviteSealKey = key;
                    inviteText = NetCodes.encode(invite, salt, key, RNG);
                }
                if (!inviteMade) {
                    inviteMade = true;
                    if (invite.cands.isEmpty()) setState(HOSTING, "нет ни одного адреса: включи Wi-Fi или мобильный интернет");
                    else setState(HOSTING, "жду игроков; в игре создай мультиплеерную игру");
                }
                listener.changed();
            });
        }, "fnet-password");
        t.setDaemon(true);
        t.start();
    }

    private synchronized void sealInvite() {
        if (inviteSealKey != null) inviteText = NetCodes.encode(invite, inviteSalt, inviteSealKey, RNG);
    }

    /** Host: {QR text, its password} of the same moment (they change together). */
    synchronized String[] inviteAndPassword() {
        return new String[] {inviteText, invitePassword};
    }

    /** Host: the invite's own password instead of the made one (at least 8 characters; spaces and dashes do not
     * count). false: too short. The QR changes; the room key does not (players in the game stay). */
    boolean setInvitePassword(String password) {
        if (NetCodes.normalPassword(password).length() < 8) return false;
        post(() -> {
            if (invite == null) return;
            passwordKeyed(password.trim());
        });
        return true;
    }

    private void makeAnswer() {
        NetCodes.Answer a = new NetCodes.Answer();
        a.room = target.room;
        a.guestId = guestId;
        a.cands = orderedCands();
        answerText = NetCodes.encode(a, guestKey, RNG);
        listener.changed();
    }

    // ------------------------------------------------------------------ receiving

    private void readExt() throws IOException {
        for (int i = 0; i < 256; i++) {
            rx.clear();
            SocketAddress from;
            try {
                from = ext.receive(rx);
            } catch (java.net.PortUnreachableException e) {
                continue;
            }
            if (from == null) return;
            int len = rx.position();
            byte[] p = Arrays.copyOf(rx.array(), len);
            InetSocketAddress src = (InetSocketAddress) from;
            if (NetStun.isStun(p, len)) {
                onStun(p, len, src);
                continue;
            }
            if (len < 1) continue;
            try {
                switch (p[0] & 0xff) {
                    case HELLO: if (host) onHello(p, src); else foreign++; break;
                    case WELCOME: if (!host) onWelcome(p, src); else foreign++; break;
                    case WAIT: if (!host) onWait(p); else foreign++; break;
                    case PUNCH: if (!host) onPunch(p, src); else foreign++; break;
                    case DATA: onData(p, src); break;
                    default: foreign++; break;
                }
            } catch (RuntimeException e) {
                foreign++;  // short or malformed
            }
        }
    }

    private void onHello(byte[] p, InetSocketAddress src) {
        ByteBuffer b = ByteBuffer.wrap(p);
        b.get();
        if (b.get() != 1) return;
        byte[] room = new byte[4];
        b.get(room);
        long gid = b.getLong(), ts = b.getLong();
        byte[] ng = new byte[16];
        b.get(ng);
        int nl = b.get() & 0xff;
        byte[] nb = new byte[Math.min(nl, 64)];
        b.get(nb);
        int body = b.position();
        Peer p0 = peers.get(gid);
        byte[] key = p0 != null ? p0.joinKey : roomKey;
        if (key == null || p0 == null && (invite == null || !Arrays.equals(room, invite.room))
                || !MessageDigest.isEqual(Arrays.copyOf(mac(key, Arrays.copyOf(p, body)), 16), Arrays.copyOfRange(p, body, body + 16))) {
            foreign++;
            return;
        }
        String name = clean(new String(nb, StandardCharsets.UTF_8));
        if (p0 != null) {
            if (ts < p0.helloTs) return;  // an old HELLO again
            if (ts == p0.helloTs && Arrays.equals(ng, p0.nonceG)) {  // a repeat: answer the same
                if (p0.ep == null || !p0.active) p0.ep = src;
                if (p0.active) sendWelcome(p0);
                else if (p0.pending) sendWait(p0, 1);
                return;
            }
            // the guest started again (new nonce): new session keys, approval stays
            p0.helloTs = ts;
            p0.nonceG = ng;
            p0.ep = src;
            if (p0.active) {
                p0.lost = false;
                activate(p0);
                log("вернулся без подтверждения (уже был в игре): " + p0.name + " с " + str(src));
            }
            return;
        }
        if (refused.contains(gid)) {
            Peer tmp = new Peer();
            tmp.gid = gid;
            tmp.nonceG = ng;
            tmp.joinKey = key;
            tmp.room = room;
            tmp.ep = src;
            sendWait(tmp, 2);
            return;
        }
        Peer np = new Peer();
        np.gid = gid;
        np.name = name.isEmpty() ? "игрок" : name;
        np.joinKey = key;
        np.room = room;
        np.nonceG = ng;
        np.helloTs = ts;
        np.ep = src;
        np.since = now();
        if (inviteExpiry != 0 && System.currentTimeMillis() / 1000 > inviteExpiry) {
            sendWait(np, 4);
            return;
        }
        if (cfg.maxPlayers > 0 && peers.size() >= cfg.maxPlayers) {
            sendWait(np, 3);
            log("мест нет: " + np.name);
            return;
        }
        peers.put(gid, np);
        if (cfg.approve) {
            np.pending = true;
            np.pendingSince = now();
            sendWait(np, 1);
            log("просится в игру: " + np.name + " (" + str(src) + ")");
            listener.joinRequest(gid, np.name);
        } else activate(np);
        publish();
    }

    /** Host: session keys, the game socket, WELCOME. */
    private void activate(Peer p) {
        p.nonceH = random(16);
        p.sid = RNG.nextInt();
        byte[] prk = mac(p.joinKey, "fnet keys".getBytes(StandardCharsets.US_ASCII), p.nonceG, p.nonceH);
        p.kRx = mac(prk, new byte[] {1});  // guest → host
        p.kTx = mac(prk, new byte[] {2});
        p.ctrTx = 0;
        p.rxMax = -1;
        p.rxMask = 0;
        p.lastRx = now();
        boolean first = !p.active;
        p.active = true;
        int cls = classify(p.ep.getAddress());
        p.path = cls == NetCodes.LAN || cls == NetCodes.IPV6 ? cls : NetCodes.NET4;
        if (p.game == null) {
            try {
                p.game = DatagramChannel.open();
                p.game.connect(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), cfg.gamePort));
                p.game.configureBlocking(false);
                p.game.register(selector, SelectionKey.OP_READ, p);
            } catch (IOException e) {
                log("сокет к игре: " + e);
            }
        }
        sendWelcome(p);
        if (first) {
            log("подключён: " + p.name + " через " + NetCodes.TYPE_NAMES[p.path] + " (" + str(p.ep) + ")");
            if (cfg.oneUse) {
                makeInvite();
                log("приглашение одноразовое: сделано новое");
            }
        }
        publish();
    }

    private void onWelcome(byte[] p, InetSocketAddress src) {
        ByteBuffer b = ByteBuffer.wrap(p);
        b.get();
        if (b.get() != 1) return;
        byte[] room = new byte[4];
        b.get(room);
        long gid = b.getLong();
        byte[] nh = new byte[16];
        b.get(nh);
        int sid = b.getInt();
        int body = b.position();
        if (gid != guestId || !Arrays.equals(room, target.room)
                || !MessageDigest.isEqual(Arrays.copyOf(mac(guestKey, Arrays.copyOf(p, body), nonceG), 16), Arrays.copyOfRange(p, body, body + 16))) {
            foreign++;
            return;
        }
        Peer h = peers.get(0L);
        if (h != null && h.active && Arrays.equals(h.nonceH, nh)) return;  // the same WELCOME from another address
        if (h == null) {
            h = new Peer();
            h.name = "хост";
            h.since = now();
            peers.put(0L, h);
        }
        h.nonceG = nonceG;
        h.nonceH = nh;
        h.sid = sid;
        byte[] prk = mac(guestKey, "fnet keys".getBytes(StandardCharsets.US_ASCII), nonceG, nh);
        h.kTx = mac(prk, new byte[] {1});
        h.kRx = mac(prk, new byte[] {2});
        h.ctrTx = 0;
        h.rxMax = -1;
        h.rxMask = 0;
        h.ep = src;
        h.lastRx = now();
        h.active = true;
        h.path = 0;
        synchronized (helloTargets) {
            for (NetCodes.Cand c : helloTargets) if (c.addr.equals(src)) h.path = c.type;
        }
        if (h.path == 0 || h.path == NetCodes.SEEN) h.path = classify(src.getAddress()) == NetCodes.LAN ? NetCodes.LAN : NetCodes.STUN;
        if (probing) log("связь восстановлена через " + NetCodes.TYPE_NAMES[h.path] + " (" + str(src) + "), " + (now() - probeStart) + " мс");
        else log("соединён с хостом через " + NetCodes.TYPE_NAMES[h.path] + " (" + str(src) + "), " + (now() - connectStart) + " мс");
        probing = false;
        setState(CONNECTED, connectedText());
    }

    private void onWait(byte[] p) {
        ByteBuffer b = ByteBuffer.wrap(p);
        b.get();
        byte[] room = new byte[4];
        b.get(room);
        long gid = b.getLong();
        int code = b.get() & 0xff;
        int body = b.position();
        if (gid != guestId || !MessageDigest.isEqual(Arrays.copyOf(mac(guestKey, Arrays.copyOf(p, body), nonceG), 16),
                Arrays.copyOfRange(p, body, body + 16))) {
            foreign++;
            return;
        }
        if (state != CONNECTING) return;
        switch (code) {
            case 1: setStatus("хост видит тебя и должен подтвердить вход"); break;
            case 2: setState(FAILED, "хост отказал во входе"); break;
            case 3: setState(FAILED, "у хоста нет свободных мест"); break;
            case 4: setState(FAILED, "приглашение устарело: попроси у хоста новое"); break;
            default: break;
        }
    }

    private void onPunch(byte[] p, InetSocketAddress src) {
        ByteBuffer b = ByteBuffer.wrap(p);
        b.get();
        byte[] room = new byte[4];
        b.get(room);
        long gid = b.getLong();
        b.getLong();
        int body = b.position();
        if (gid != guestId || !Arrays.equals(room, target.room)
                || !MessageDigest.isEqual(Arrays.copyOf(mac(guestKey, Arrays.copyOf(p, body)), 16), Arrays.copyOfRange(p, body, body + 16))) {
            foreign++;
            return;
        }
        if (state != CONNECTING) return;
        synchronized (helloTargets) {
            boolean known = false;
            for (NetCodes.Cand c : helloTargets) known |= c.addr.equals(src);
            if (!known) {
                helloTargets.add(0, new NetCodes.Cand(NetCodes.STUN, src));
                log("хост достучался с " + str(src) + ", отвечаю");
            }
        }
        send(hello(), src);
    }

    private void onData(byte[] p, InetSocketAddress src) {
        if (p.length < 13 + 16 + 1) {
            foreign++;
            return;
        }
        int sid = ByteBuffer.wrap(p, 1, 4).getInt();
        long ctr = ByteBuffer.wrap(p, 5, 8).getLong();
        Peer peer = null;
        for (Peer q : peers.values())
            if (q.active && q.sid == sid && q.kRx != null) {
                peer = q;
                break;
            }
        if (peer == null) {
            foreign++;
            return;
        }
        if (ctr <= peer.rxMax - 64 || ctr <= peer.rxMax && (peer.rxMask & (1L << (peer.rxMax - ctr))) != 0) {
            peer.replays++;
            return;
        }
        byte[] plain;
        try {
            peer.dec.init(Cipher.DECRYPT_MODE, new SecretKeySpec(peer.kRx, "AES"), new GCMParameterSpec(128, iv(host ? 1 : 2, ctr)));
            peer.dec.updateAAD(p, 0, 13);
            plain = peer.dec.doFinal(p, 13, p.length - 13);
        } catch (Exception e) {
            foreign++;
            return;
        }
        if (ctr > peer.rxMax) {
            long shift = ctr - peer.rxMax;
            peer.rxMask = shift >= 64 ? 0 : peer.rxMask << shift;
            peer.rxMask |= 1;
            peer.rxMax = ctr;
            if (!src.equals(peer.ep)) {
                log((host ? peer.name : "хост") + ": адрес сменился " + str(peer.ep) + " → " + str(src));
                peer.ep = src;
            }
        } else peer.rxMask |= 1L << (peer.rxMax - ctr);
        peer.lastRx = now();
        if (peer.lost) {
            peer.lost = false;
            log("снова на связи: " + peer.name);
        }
        peer.pktIn++;
        peer.bytesIn += p.length;
        if (plain.length < 1) return;
        int kind = plain[0];
        switch (kind) {
            case K_GAME:
                toGame(peer, plain, 1, plain.length - 1);
                break;
            case K_PART: {
                byte[] whole = joinPart(peer, plain);
                if (whole != null) toGame(peer, whole, 0, whole.length);
                break;
            }
            case K_PING:
                sendData(peer, K_PONG, Arrays.copyOfRange(plain, 1, plain.length));
                break;
            case K_PONG:
                if (plain.length >= 13) {
                    long sent = ByteBuffer.wrap(plain, 5, 8).getLong();
                    peer.rtt = (System.nanoTime() - sent) / 1e6;
                    peer.rttAvg = peer.rttAvg < 0 ? peer.rtt : peer.rttAvg * 0.8 + peer.rtt * 0.2;
                    peer.pongs++;
                }
                break;
            case K_CHAT: {
                if (plain.length < 2) break;
                int n = plain[1] & 0xff;
                if (2 + n > plain.length) break;
                String name = host ? peer.name : new String(plain, 2, n, StandardCharsets.UTF_8);
                byte[] text = Arrays.copyOfRange(plain, 2 + n, plain.length);
                if (host) relayChat(peer, name, text);
                listener.chat(name, clean(new String(text, StandardCharsets.UTF_8)));
                break;
            }
            case K_VOICE:
            case K_TYPING: {
                if (plain.length < 6) break;
                int n = plain[5] & 0xff;
                if (6 + n > plain.length) break;
                int speaker = host ? peer.sid : ByteBuffer.wrap(plain, 1, 4).getInt();
                String name = host ? peer.name : clean(new String(plain, 6, n, StandardCharsets.UTF_8));
                byte[] frame = Arrays.copyOfRange(plain, 6 + n, plain.length);
                if (host) relayNamed(peer, kind, speaker, name, frame);
                if (kind == K_TYPING) {
                    listener.typing(speaker, name);
                    break;
                }
                listener.voice(speaker, name, frame);
                break;
            }
            case K_BYE:
                if (host) {
                    peers.remove(peer.gid);
                    closeGame(peer);
                    log("вышел: " + peer.name);
                    publish();
                } else {
                    peer.active = false;
                    setState(FAILED, "хост закрыл сеть или отключил тебя");
                }
                break;
            default:
                break;
        }
    }

    /** The guest's game → the host. */
    private void readLocal() throws IOException {
        for (int i = 0; i < 256; i++) {
            rx.clear();
            SocketAddress from = local.receive(rx);
            if (from == null) return;
            int n = rx.position();
            // Factorio's PingMessage (3 bytes, type 0) from another socket than the game's: the HUD's pinger
            if (n == 3 && (rx.get(0) & 0x1f) == 0 && gameAddr != null && !from.equals(gameAddr)) pingAddr = from;
            else gameAddr = from;
            Peer h = peers.get(0L);
            if (state != CONNECTED || h == null || !h.active) {
                gameDropped++;
                continue;
            }
            sendGame(h, Arrays.copyOf(rx.array(), rx.position()));
        }
    }

    /** The host's game → one guest. */
    private void readGame(Peer p) {
        for (int i = 0; i < 256; i++) {
            rx.clear();
            try {
                if (p.game.read(rx) <= 0) return;
            } catch (IOException e) {
                p.gameDown = true;  // nothing listens on gamePort
                return;
            }
            p.gameDown = false;
            if (p.active) sendGame(p, Arrays.copyOf(rx.array(), rx.position()));
        }
    }

    // ------------------------------------------------------------------ sending

    private byte[] hello() {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(HELLO);
        o.write(1);
        o.write(target.room, 0, 4);
        NetCodes.putLong(o, guestId);
        NetCodes.putLong(o, helloTs);
        o.write(nonceG, 0, 16);
        byte[] nb = myName.getBytes(StandardCharsets.UTF_8);
        int nl = Math.min(nb.length, 32);
        o.write(nl);
        o.write(nb, 0, nl);
        byte[] body = o.toByteArray();
        o.write(mac(guestKey, body), 0, 16);
        return o.toByteArray();
    }

    private void sendHellos() {
        byte[] h = hello();
        List<NetCodes.Cand> list;
        synchronized (helloTargets) {
            list = new ArrayList<>(helloTargets);
        }
        for (NetCodes.Cand c : list) send(h, c.addr);
    }

    private void sendWelcome(Peer p) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(WELCOME);
        o.write(1);
        o.write(p.room, 0, 4);
        NetCodes.putLong(o, p.gid);
        o.write(p.nonceH, 0, 16);
        NetCodes.putInt(o, p.sid);
        byte[] body = o.toByteArray();
        o.write(mac(p.joinKey, body, p.nonceG), 0, 16);
        send(o.toByteArray(), p.ep);
    }

    private void sendWait(Peer p, int code) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(WAIT);
        o.write(p.room, 0, 4);
        NetCodes.putLong(o, p.gid);
        o.write(code);
        byte[] body = o.toByteArray();
        o.write(mac(p.joinKey, body, p.nonceG), 0, 16);
        send(o.toByteArray(), p.ep);
    }

    private void sendPunch(long gid, List<NetCodes.Cand> to) {
        if (to == null) return;
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(PUNCH);
        o.write(invite.room, 0, 4);
        NetCodes.putLong(o, gid);
        NetCodes.putLong(o, System.currentTimeMillis());
        byte[] body = o.toByteArray();
        o.write(mac(roomKey, body), 0, 16);
        byte[] pkt = o.toByteArray();
        for (NetCodes.Cand c : to) send(pkt, c.addr);
    }

    private void ping(Peer p, long t) {
        if (t - p.lastPing < cfg.pingSeconds * 1000L) return;
        p.lastPing = t;
        ByteBuffer b = ByteBuffer.allocate(12);
        b.putInt(++p.pingSeq).putLong(System.nanoTime());
        p.pings++;
        sendData(p, K_PING, b.array());
    }

    /** A chat line to every player on the line but the one it came from (a guest has the host alone). */
    private void relayChat(Peer from, String name, byte[] text) {
        byte[] n = name.getBytes(StandardCharsets.UTF_8);
        if (n.length > 64) n = Arrays.copyOf(n, 64);  // a cut letter is shown as one wrong mark
        byte[] b = new byte[1 + n.length + text.length];
        b[0] = (byte) n.length;
        System.arraycopy(n, 0, b, 1, n.length);
        System.arraycopy(text, 0, b, 1 + n.length, text.length);
        for (Peer p : peers.values()) if (p.active && !p.lost && p != from) sendData(p, K_CHAT, b);
    }

    /** Voice or typing to every player on the line but its sender; src: who (the host is 0). */
    private void relayNamed(Peer from, int kind, int src, String name, byte[] frame) {
        byte[] n = name.getBytes(StandardCharsets.UTF_8);
        if (n.length > 64) n = Arrays.copyOf(n, 64);
        byte[] b = new byte[5 + n.length + frame.length];
        ByteBuffer.wrap(b).putInt(src).put((byte) n.length).put(n).put(frame);
        for (Peer p : peers.values()) if (p.active && !p.lost && p != from) sendData(p, kind, b);
    }

    /** A game packet to the other side: whole, or in parts of MAX_PART bytes (K_PART). */
    private void sendGame(Peer p, byte[] d) {
        if (d.length <= MAX_PART) {
            sendData(p, K_GAME, d);
            return;
        }
        int count = (d.length + MAX_PART - 1) / MAX_PART, id = p.partId = (p.partId + 1) & 0xffff;
        for (int i = 0; i < count; i++) {
            int off = i * MAX_PART, len = Math.min(MAX_PART, d.length - off);
            byte[] b = new byte[4 + len];
            b[0] = (byte) (id >> 8);
            b[1] = (byte) id;
            b[2] = (byte) i;
            b[3] = (byte) count;
            System.arraycopy(d, off, b, 4, len);
            sendData(p, K_PART, b);
        }
    }

    /** A part (kind byte first): the whole game packet once all its parts are in, else null. A packet with a lost
     * part is given up when 32 newer ones are waiting (the game sends again what it needs). */
    private static byte[] joinPart(Peer p, byte[] plain) {
        if (plain.length < 6) return null;
        int id = (plain[1] & 0xff) << 8 | (plain[2] & 0xff), i = plain[3] & 0xff, count = plain[4] & 0xff;
        if (count < 2 || i >= count) return null;
        byte[][] got = p.parts.get(id);
        if (got == null || got.length != count) {
            got = new byte[count][];
            p.parts.put(id, got);
            while (p.parts.size() > 32) p.parts.remove(p.parts.keySet().iterator().next());
        }
        got[i] = Arrays.copyOfRange(plain, 5, plain.length);
        int total = 0;
        for (byte[] g : got) {
            if (g == null) return null;
            total += g.length;
        }
        p.parts.remove(id);
        byte[] whole = new byte[total];
        int at = 0;
        for (byte[] g : got) {
            System.arraycopy(g, 0, whole, at, g.length);
            at += g.length;
        }
        return whole;
    }

    /** A game packet from the other side to the game here (host: the guest's socket to the server). */
    private void toGame(Peer peer, byte[] b, int off, int len) {
        try {
            if (host) {
                if (peer.game != null) peer.game.write(ByteBuffer.wrap(b, off, len));
            } else {
                // PingReplyMessage (3 bytes, type 1) goes to the pinger, everything else to the game
                boolean reply = len == 3 && (b[off] & 0x1f) == 1 && pingAddr != null;
                SocketAddress to = reply ? pingAddr : gameAddr;
                if (to != null) local.send(ByteBuffer.wrap(b, off, len), to);
            }
        } catch (IOException e) {  // PortUnreachableException too: nothing listens
            peer.gameDown = true;
        }
    }

    private void sendData(Peer p, int kind, byte[] payload) {
        if (p.kTx == null || p.ep == null) return;
        long ctr = p.ctrTx++;
        ByteBuffer pkt = ByteBuffer.allocate(13 + 1 + payload.length + 16);
        pkt.put((byte) DATA).putInt(p.sid).putLong(ctr);
        try {
            p.enc.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(p.kTx, "AES"), new GCMParameterSpec(128, iv(host ? 2 : 1, ctr)));
            p.enc.updateAAD(pkt.array(), 0, 13);
            byte[] in = new byte[1 + payload.length];
            in[0] = (byte) kind;
            System.arraycopy(payload, 0, in, 1, payload.length);
            p.enc.doFinal(in, 0, in.length, pkt.array(), 13);
        } catch (Exception e) {
            log("шифрование: " + e);
            return;
        }
        p.pktOut++;
        p.bytesOut += pkt.capacity();
        send(pkt.array(), p.ep);
    }

    private void send(byte[] pkt, InetSocketAddress to) {
        try {
            ext.send(ByteBuffer.wrap(pkt), to);
        } catch (IOException | RuntimeException e) {
            // no route (e.g. IPv6 without IPv6): this candidate does not work
        }
    }

    // ------------------------------------------------------------------ statistics

    private void publish() {
        long t = now();
        List<PeerStat> out = new ArrayList<>();
        for (Peer p : peers.values()) {
            PeerStat s = new PeerStat();
            s.gid = p.gid;
            s.name = p.name;
            s.pending = p.pending;
            s.lost = p.lost;
            s.path = p.lost ? "нет связи, жду возвращения" : p.active ? NetCodes.TYPE_NAMES[p.path] : "ждёт подтверждения";
            s.rttMs = (int) Math.round(p.rttAvg);
            long sent = Math.max(0, p.pings - 1);  // the last one may still be on its way
            s.lossPct = sent > 0 ? (int) Math.max(0, Math.round(100.0 * (sent - Math.min(p.pongs, sent)) / sent)) : 0;
            s.idleS = p.active ? (int) ((t - p.lastRx) / 1000) : 0;
            s.minutes = (int) ((t - p.since) / 60000);
            s.bytesIn = p.bytesIn;
            s.bytesOut = p.bytesOut;
            s.replays = p.replays;
            s.gameDown = p.gameDown;
            p.rateIn = (p.bytesIn - p.lastBytesIn) / 1024.0;
            p.rateOut = (p.bytesOut - p.lastBytesOut) / 1024.0;
            p.lastBytesIn = p.bytesIn;
            p.lastBytesOut = p.bytesOut;
            s.kbIn = p.rateIn;
            s.kbOut = p.rateOut;
            out.add(s);
        }
        stats = out;
        listener.changed();
    }

    // ------------------------------------------------------------------ helpers

    private void setState(int s, String text) {
        state = s;
        status = noAddr(text);
        log(text);
        listener.changed();
    }

    private void setStatus(String text) {
        text = noAddr(text);
        if (text.equals(status)) return;
        status = text;
        log(text);
        listener.changed();
    }

    private void log(String s) {
        listener.log(s);
    }

    private void closeGame(Peer p) {
        closeQuietly(p.game);
        p.game = null;
    }

    private static void closeQuietly(java.io.Closeable c) {
        try {
            if (c != null) c.close();
        } catch (IOException ignored) {
            // closing
        }
    }

    private static long now() {
        return System.nanoTime() / 1000000;
    }

    static byte[] random(int n) {
        byte[] b = new byte[n];
        RNG.nextBytes(b);
        return b;
    }

    static byte[] roomKey(byte[] secret, String pin) {
        return pin == null ? secret : mac(secret, ("fnative pin " + pin.trim()).getBytes(StandardCharsets.UTF_8));
    }

    static byte[] mac(byte[] key, byte[]... parts) {
        try {
            Mac m = Mac.getInstance("HmacSHA256");
            m.init(new SecretKeySpec(key, "HmacSHA256"));
            for (byte[] p : parts) m.update(p);
            return m.doFinal();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] iv(int dir, long ctr) {
        return ByteBuffer.allocate(12).putInt(dir).putLong(ctr).array();
    }

    /** LAN (private / link-local / ULA), IPV6 (global IPv6), 0 = public IPv4 or other. */
    static int classify(InetAddress a) {
        if (a instanceof Inet4Address) {
            byte[] b = a.getAddress();
            int b0 = b[0] & 0xff, b1 = b[1] & 0xff;
            if (b0 == 10 || b0 == 172 && b1 >= 16 && b1 < 32 || b0 == 192 && b1 == 168 || a.isLinkLocalAddress()) return NetCodes.LAN;
            if (b0 == 100 && b1 >= 64 && b1 < 128) return -1;  // the operator's CGNAT inside: useless for others
            return 0;
        }
        if (a instanceof Inet6Address) {
            byte[] b = a.getAddress();
            if (a.isLinkLocalAddress() || (b[0] & 0xfe) == 0xfc) return NetCodes.LAN;
            if ((b[0] & 0xe0) == 0x20) return NetCodes.IPV6;  // 2000::/3 global unicast
        }
        return -1;
    }

    private static boolean isLocal(InetAddress a) {
        try {
            return NetworkInterface.getByInetAddress(a) != null;
        } catch (Exception e) {
            return false;
        }
    }

    private static InetAddress stripScope(InetAddress a) {
        try {
            return InetAddress.getByAddress(a.getAddress());
        } catch (Exception e) {
            return a;
        }
    }

    private static final java.util.regex.Pattern ADDR = java.util.regex.Pattern.compile(
            "\\[?(?<![\\w:.])(?:[0-9A-Fa-f]{0,4}:){2,7}[0-9A-Fa-f]{0,4}(?:%[\\w.]+)?\\]?(?::\\d{1,5})?(?![\\w:])"  // IPv6
            + "|(?<![\\w.])\\d{1,3}(?:\\.\\d{1,3}){3}(?::\\d{1,5})?(?![\\w.])"                              // IPv4[:port]
            + "|(?i)(?<=порт |port )\\d{1,5}");
    /** Text for the screen with every IP address and port number taken out (the status line, notes, errors). */
    static String noAddr(String s) {
        return s == null ? "" : ADDR.matcher(s).replaceAll("…");
    }

    private static String clean(String s) {
        StringBuilder b = new StringBuilder();
        for (char c : s.toCharArray()) if (c >= ' ' && c != 0x7f) b.append(c);
        return b.toString();
    }

    static String str(InetSocketAddress a) {
        if (a == null) return "—";
        InetAddress ia = a.getAddress();
        if (ia == null) return a.getHostString() + ":" + a.getPort();
        String h = ia.getHostAddress();
        int pct = h.indexOf('%');
        if (pct > 0) h = h.substring(0, pct);
        return ia instanceof Inet6Address ? "[" + h + "]:" + a.getPort() : h + ":" + a.getPort();
    }
}

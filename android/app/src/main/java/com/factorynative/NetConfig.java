package com.factorynative;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Settings of the play-over-the-internet network (gear menu, "Сеть"): which paths to try, ports, the invite's
 * protection. Presets fill all fields; "manual" keeps the user's own. Plain Java (tested on the host).
 */
final class NetConfig {
    // paths the host offers in its invite and the guest offers in its answer
    boolean lan = true;    // the local network's addresses (same Wi-Fi)
    boolean ipv6 = true;   // public IPv6 addresses (no NAT)
    boolean upnp = true;   // a port mapping on the home router (UPnP)
    boolean stun = true;   // the outside address from public STUN servers: NAT hole punching
    String stunServers = "stun.l.google.com:19302 stun.cloudflare.com:3478";

    int port = 0;          // the tunnel's UDP port, 0 = random
    int gamePort = 34197;  // host: the game's server port (Factorio's default)
    int localPort = 34200; // guest: the game connects to 127.0.0.1:localPort
    int maxPlayers = 16;   // guests at once, 0 = no limit
    boolean approve = true;     // the host confirms each new player
    boolean pin = false;        // a 6-digit PIN made with each invite, not in the QR: the host tells it apart
    int inviteMinutes = 30;     // new players after that are refused, 0 = no limit
    boolean oneUse = false;     // an invite lets one player in, then a new one is made
    int pingSeconds = 1;        // keepalive and ping; a guest looks for the host after 2 × this + 0.5 s of quiet
    int rejoinMinutes = 10;     // host: a player who went quiet comes back without approval within this time
    int punchSeconds = 120;     // how long both sides keep knocking at each other's addresses
    int timeoutSeconds = 30;    // no packet that long: the connection is lost

    static final String[] PRESETS = {"Стандарт", "Дом (одна Wi-Fi)", "Мобильный интернет", "Без сторонних серверов",
            "Строгая защита", "Вручную"};
    static final String[] PRESET_NOTES = {
            "Все пути: локальная сеть, IPv6, роутер (UPnP), пробивка NAT через STUN. Хост подтверждает новых игроков. "
                    + "Приглашение действует 30 минут.",
            "Только локальная сеть: телефоны в одной Wi-Fi. Никаких запросов в интернет. Игроки входят без подтверждения.",
            "Для сотовой сети: IPv6 и пробивка NAT, долгая пробивка (5 минут). "
                    + "Чаще всего понадобится ответный QR гостя.",
            "Локальная сеть, IPv6 и роутер, без обращений к чужим STUN-серверам. За CGNAT оператора не соединится.",
            "Как «Стандарт», плюс PIN (сообщи его голосом), приглашение на 10 минут и на одного игрока.",
            "Свои настройки ниже."};
    static final int MANUAL = 5;

    static NetConfig preset(int id) {
        NetConfig c = new NetConfig();
        switch (id) {
            case 1:
                c.ipv6 = c.upnp = c.stun = false;
                c.approve = false;
                c.inviteMinutes = 0;
                break;
            case 2:
                c.lan = false;
                c.upnp = false;
                c.punchSeconds = 300;
                break;
            case 3:
                c.stun = false;
                break;
            case 4:
                c.pin = true;
                c.inviteMinutes = 10;
                c.oneUse = true;
                c.maxPlayers = 8;
                break;
            default:
                break;
        }
        return c;
    }

    /** A guest's settings: the host's from its invite (the guest has no presets). */
    static NetConfig forGuest(NetCodes.Invite v) {
        NetConfig c = new NetConfig();
        c.lan = (v.paths & 1) != 0;
        c.ipv6 = (v.paths & 2) != 0;
        c.upnp = (v.paths & 4) != 0;
        c.stun = (v.paths & 8) != 0;
        c.pingSeconds = v.pingSeconds;
        c.punchSeconds = v.punchSeconds;
        return c;
    }

    List<java.net.InetSocketAddress> stunList() {
        List<java.net.InetSocketAddress> out = new ArrayList<>();
        for (String s : stunServers.split("[\\s,;]+")) {
            if (s.isEmpty()) continue;
            int colon = s.lastIndexOf(':');
            String host = colon > 0 ? s.substring(0, colon) : s;
            int p = 3478;
            try {
                if (colon > 0) p = Integer.parseInt(s.substring(colon + 1));
            } catch (NumberFormatException ignored) {
                // default port
            }
            out.add(java.net.InetSocketAddress.createUnresolved(host, p));
        }
        return out;
    }

    /** One line for the preferences: key=value;... */
    String save() {
        return String.format(Locale.ROOT,
                "lan=%b;ipv6=%b;upnp=%b;stun=%b;stunServers=%s;port=%d;gamePort=%d;localPort=%d;maxPlayers=%d;approve=%b;"
                        + "pin=%b;inviteMinutes=%d;oneUse=%b;pingSeconds=%d;punchSeconds=%d;timeoutSeconds=%d;rejoinMinutes=%d",
                lan, ipv6, upnp, stun, stunServers.replace(';', ' '), port, gamePort, localPort, maxPlayers, approve,
                pin, inviteMinutes, oneUse, pingSeconds, punchSeconds, timeoutSeconds, rejoinMinutes);
    }

    static NetConfig load(String s) {
        NetConfig c = new NetConfig();
        if (s == null) return c;
        for (String kv : s.split(";")) {
            int eq = kv.indexOf('=');
            if (eq <= 0) continue;
            String k = kv.substring(0, eq), v = kv.substring(eq + 1);
            try {
                switch (k) {
                    case "lan": c.lan = Boolean.parseBoolean(v); break;
                    case "ipv6": c.ipv6 = Boolean.parseBoolean(v); break;
                    case "upnp": c.upnp = Boolean.parseBoolean(v); break;
                    case "stun": c.stun = Boolean.parseBoolean(v); break;
                    case "stunServers": c.stunServers = v; break;
                    case "port": c.port = clamp(Integer.parseInt(v), 0, 65535); break;
                    case "gamePort": c.gamePort = clamp(Integer.parseInt(v), 1, 65535); break;
                    case "localPort": c.localPort = clamp(Integer.parseInt(v), 1024, 65535); break;
                    case "maxPlayers": c.maxPlayers = clamp(Integer.parseInt(v), 0, 1000); break;
                    case "approve": c.approve = Boolean.parseBoolean(v); break;
                    case "pin": c.pin = Boolean.parseBoolean(v); break;
                    case "inviteMinutes": c.inviteMinutes = clamp(Integer.parseInt(v), 0, 100000); break;
                    case "oneUse": c.oneUse = Boolean.parseBoolean(v); break;
                    case "pingSeconds": c.pingSeconds = clamp(Integer.parseInt(v), 1, 25); break;
                    case "punchSeconds": c.punchSeconds = clamp(Integer.parseInt(v), 5, 3600); break;
                    case "rejoinMinutes": c.rejoinMinutes = clamp(Integer.parseInt(v), 1, 1440); break;
                    case "timeoutSeconds": c.timeoutSeconds = clamp(Integer.parseInt(v), 5, 600); break;
                    default: break;
                }
            } catch (NumberFormatException ignored) {
                // keeps the default
            }
        }
        return c;
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}

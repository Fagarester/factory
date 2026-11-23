package com.factorynative;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The network test (gear menu, "Сеть" → "Тест сети"): which ways in this phone has as a host. Takes ~5 s, blocking
 * (run it off the UI thread). Plain Java; the page adds the Android network type.
 */
final class NetProbe {
    private NetProbe() {}

    static String run(NetConfig cfg) {
        StringBuilder r = new StringBuilder();
        List<String> good = new ArrayList<>(), bad = new ArrayList<>();
        // 1. addresses
        r.append("Адреса:\n");
        List<String> v6 = new ArrayList<>();
        boolean lan = false;
        String publicV4 = null;
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                StringBuilder line = new StringBuilder();
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    int c = NetTunnel.classify(a);
                    String h = a.getHostAddress().replaceAll("%.*", "");
                    String kind = c == NetCodes.IPV6 ? "публичный IPv6" : c == NetCodes.LAN ? (a.isLinkLocalAddress() ? "link-local" : "локальный")
                            : c == 0 ? "публичный IPv4" : a instanceof Inet4Address ? "сеть оператора (CGNAT)" : "служебный";
                    line.append("  ").append(h).append(" — ").append(kind).append('\n');
                    if (c == NetCodes.IPV6) v6.add(h);
                    if (c == NetCodes.LAN && !a.isLinkLocalAddress()) lan = true;
                    if (c == 0 && a instanceof Inet4Address) publicV4 = h;
                }
                if (line.length() > 0) r.append(' ').append(ni.getName()).append(":\n").append(line);
            }
        } catch (Exception e) {
            r.append("  ошибка: ").append(e).append('\n');
        }
        if (!v6.isEmpty()) good.add("публичный IPv6 есть: гости с IPv6 могут подключиться напрямую (если оператор не режет входящие)");
        else bad.add("публичного IPv6 нет");
        if (publicV4 != null) good.add("у телефона публичный IPv4 " + publicV4 + ": NAT нет");
        if (lan) good.add("локальная сеть есть: игроки в той же Wi-Fi подключатся");

        // 2. STUN: the outside address from two servers through one socket
        r.append("\nSTUN (внешний адрес и тип NAT):\n");
        Map<String, InetSocketAddress> seen = new LinkedHashMap<>();
        int localPort = 0;
        try (DatagramSocket s = new DatagramSocket()) {
            localPort = s.getLocalPort();
            s.setSoTimeout(400);
            List<InetSocketAddress> servers = new ArrayList<>();
            for (InetSocketAddress u : cfg.stunList()) {
                try {
                    for (InetAddress a : InetAddress.getAllByName(u.getHostString()))
                        if (a instanceof Inet4Address) {
                            servers.add(new InetSocketAddress(a, u.getPort()));
                            break;
                        }
                } catch (Exception e) {
                    r.append("  ").append(u.getHostString()).append(": имя не найдено\n");
                }
            }
            for (InetSocketAddress srv : servers) {
                byte[] tx = new byte[12];
                long t0 = System.nanoTime();
                InetSocketAddress m = null;
                for (int attempt = 0; attempt < 3 && m == null; attempt++) {
                    byte[] req = NetStun.request(tx);
                    s.send(new DatagramPacket(req, req.length, srv));
                    long end = System.currentTimeMillis() + 500;
                    while (m == null && System.currentTimeMillis() < end) {
                        byte[] b = new byte[1500];
                        DatagramPacket p = new DatagramPacket(b, b.length);
                        try {
                            s.receive(p);
                        } catch (java.net.SocketTimeoutException e) {
                            break;
                        }
                        if (NetStun.sameTx(b, tx)) m = NetStun.mapped(b, p.getLength());
                    }
                }
                String name = NetTunnel.str(srv);
                if (m != null) {
                    seen.put(name, m);
                    r.append(String.format(java.util.Locale.ROOT, "  %s → %s (%d мс)\n", name, NetTunnel.str(m), (System.nanoTime() - t0) / 1000000));
                } else r.append("  ").append(name).append(": нет ответа\n");
            }
        } catch (Exception e) {
            r.append("  ошибка: ").append(e).append('\n');
        }
        String nat;
        if (!cfg.stun) nat = "STUN выключен в настройках";
        else if (seen.isEmpty()) {
            nat = "STUN не ответил: UDP в интернет закрыт";
            bad.add(nat);
        } else {
            java.util.Set<Integer> ports = new java.util.HashSet<>();
            for (InetSocketAddress m : seen.values()) ports.add(m.getPort());
            InetSocketAddress m = seen.values().iterator().next();
            String keep = m.getPort() == localPort ? ", порт сохраняется" : ", порт меняется (" + localPort + " → " + m.getPort() + ")";
            if (seen.size() < 2) nat = "ответил 1 сервер — тип NAT неизвестен" + keep;
            else if (ports.size() == 1) {
                nat = "NAT «конусный» (один внешний порт для всех адресатов)" + keep + ": пробивка NAT вероятна";
                good.add("пробивка NAT вероятна (при необходимости с ответным QR гостя)");
            } else {
                nat = "NAT симметричный (внешний порт свой для каждого адресата): пробивка вряд ли";
                bad.add("симметричный NAT: пробивка вряд ли");
            }
        }
        r.append("  итог: ").append(nat).append('\n');

        // 3. UPnP
        r.append("\nUPnP (роутер):\n");
        if (!cfg.upnp) r.append("  выключен в настройках\n");
        else {
            String[] why = {null};
            NetUpnp u = NetUpnp.find(2000, why);
            if (u == null) r.append("  ").append(why[0]).append('\n');
            else {
                r.append("  роутер найден, внешний IP ").append(u.externalIp.isEmpty() ? "неизвестен" : u.externalIp).append('\n');
                int port = 20000 + new java.util.Random().nextInt(40000);
                boolean ok = u.map(port);
                if (ok) u.unmap(port);
                r.append("  проброс порта: ").append(ok ? "работает (проверен и снят)" : "отказ: " + u.error).append('\n');
                if (ok && u.behindCgnat()) {
                    r.append("  но внешний IP роутера частный: провайдер держит его за CGNAT\n");
                    bad.add("роутер за CGNAT провайдера: UPnP не поможет");
                } else if (ok) good.add("роутер пробрасывает порт (UPnP): гости подключатся из любой сети");
            }
        }

        // 4. verdict
        r.append("\nИтог для хоста:\n");
        for (String g : good) r.append("  + ").append(g).append('\n');
        for (String b : bad) r.append("  − ").append(b).append('\n');
        boolean direct = !v6.isEmpty() || publicV4 != null || good.stream().anyMatch(g -> g.startsWith("роутер"));
        boolean punch = good.stream().anyMatch(g -> g.startsWith("пробивка"));
        r.append(direct ? "  Этот телефон хорошо подходит как хост: к нему можно подключиться напрямую.\n"
                : punch ? "  Хостом быть можно: гостям из интернета, скорее всего, понадобится ответный QR.\n"
                : lan ? "  Из интернета к этому телефону, скорее всего, не подключиться; в одной Wi-Fi — можно.\n"
                : "  Подключиться к этому телефону, скорее всего, не выйдет.\n");
        return r.toString();
    }
}

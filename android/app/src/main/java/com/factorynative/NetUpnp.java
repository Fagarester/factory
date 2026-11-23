package com.factorynative;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * UPnP IGD: asks the home router to forward a UDP port to this phone. HTTP is spoken over a plain socket (the
 * router is on the LAN; Android's cleartext rule for HttpURLConnection does not apply to sockets).
 */
final class NetUpnp {
    String control;      // control URL of WANIPConnection / WANPPPConnection
    String service;      // its service type
    InetAddress lanIp;   // our address towards the router
    String externalIp;   // the router's outside address ("" if unknown)
    String error = "";

    private NetUpnp() {}

    /** Finds the router (≈2 s), or returns null with no router; reason in why[0]. */
    static NetUpnp find(int timeoutMs, String[] why) {
        Set<String> locations = new LinkedHashSet<>();
        String[] targets = {"urn:schemas-upnp-org:device:InternetGatewayDevice:1",
                "urn:schemas-upnp-org:device:InternetGatewayDevice:2", "urn:schemas-upnp-org:service:WANIPConnection:1"};
        try (DatagramSocket s = new DatagramSocket()) {
            s.setSoTimeout(300);
            InetSocketAddress group = new InetSocketAddress("239.255.255.250", 1900);
            for (String st : targets) {
                byte[] m = ("M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: " + st
                        + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
                s.send(new DatagramPacket(m, m.length, group));
            }
            long end = System.currentTimeMillis() + timeoutMs;
            byte[] buf = new byte[2048];
            while (System.currentTimeMillis() < end) {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                try {
                    s.receive(p);
                } catch (java.net.SocketTimeoutException e) {
                    if (!locations.isEmpty()) break;
                    continue;
                }
                Matcher mm = Pattern.compile("(?im)^location:\\s*(\\S+)").matcher(new String(buf, 0, p.getLength(), StandardCharsets.US_ASCII));
                if (mm.find()) locations.add(mm.group(1));
            }
        } catch (Exception e) {
            why[0] = "SSDP: " + e;
            return null;
        }
        if (locations.isEmpty()) {
            why[0] = "роутер не ответил на поиск UPnP (выключен в роутере или это не Wi-Fi)";
            return null;
        }
        for (String loc : locations) {
            try {
                URL url = new URL(loc);
                String xml = http(url, "GET", null, null);
                Matcher sm = Pattern.compile("<serviceType>\\s*(urn:schemas-upnp-org:service:WAN(?:IP|PPP)Connection:\\d)\\s*</serviceType>"
                        + "[\\s\\S]*?<controlURL>\\s*([^<\\s]+)\\s*</controlURL>").matcher(xml);
                if (!sm.find()) continue;
                NetUpnp u = new NetUpnp();
                u.service = sm.group(1);
                u.control = new URL(url, sm.group(2)).toString();
                try (Socket probe = new Socket()) {
                    probe.connect(new InetSocketAddress(url.getHost(), url.getPort() > 0 ? url.getPort() : 80), 2000);
                    u.lanIp = probe.getLocalAddress();
                }
                String r = u.soap("GetExternalIPAddress", "");
                Matcher em = Pattern.compile("<NewExternalIPAddress>\\s*([^<\\s]*)\\s*</NewExternalIPAddress>").matcher(r);
                u.externalIp = em.find() ? em.group(1) : "";
                return u;
            } catch (Exception e) {
                why[0] = "UPnP " + loc + ": " + e.getMessage();
            }
        }
        if (why[0] == null) why[0] = "роутер без службы WANIPConnection";
        return null;
    }

    /** Forwards external UDP port → this phone's port; true on success (error has the reason otherwise). */
    boolean map(int port) {
        for (int lease : new int[] {3600, 0}) {  // some routers take only unlimited leases
            try {
                soap("AddPortMapping", "<NewRemoteHost></NewRemoteHost><NewExternalPort>" + port + "</NewExternalPort>"
                        + "<NewProtocol>UDP</NewProtocol><NewInternalPort>" + port + "</NewInternalPort><NewInternalClient>"
                        + lanIp.getHostAddress() + "</NewInternalClient><NewEnabled>1</NewEnabled>"
                        + "<NewPortMappingDescription>FNative</NewPortMappingDescription><NewLeaseDuration>" + lease
                        + "</NewLeaseDuration>");
                return true;
            } catch (Exception e) {
                error = e.getMessage();
            }
        }
        return false;
    }

    void unmap(int port) {
        try {
            soap("DeletePortMapping", "<NewRemoteHost></NewRemoteHost><NewExternalPort>" + port
                    + "</NewExternalPort><NewProtocol>UDP</NewProtocol>");
        } catch (Exception ignored) {
            // the lease runs out by itself
        }
    }

    /** Is the router's outside address itself private (the provider's CGNAT): then a mapping does not help. */
    boolean behindCgnat() {
        try {
            if (externalIp == null || externalIp.isEmpty()) return false;
            byte[] a = InetAddress.getByName(externalIp).getAddress();
            int b0 = a[0] & 0xff, b1 = a[1] & 0xff;
            return b0 == 10 || (b0 == 172 && b1 >= 16 && b1 < 32) || (b0 == 192 && b1 == 168) || (b0 == 100 && b1 >= 64 && b1 < 128)
                    || b0 == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private String soap(String action, String args) throws Exception {
        String body = "<?xml version=\"1.0\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" "
                + "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body><u:" + action + " xmlns:u=\"" + service
                + "\">" + args + "</u:" + action + "></s:Body></s:Envelope>";
        return http(new URL(control), "POST", body, "\"" + service + "#" + action + "\"");
    }

    /** A small HTTP/1.0 request; throws with the status line on an error status. */
    private static String http(URL url, String method, String body, String soapAction) throws Exception {
        int port = url.getPort() > 0 ? url.getPort() : 80;
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(url.getHost(), port), 2000);
            s.setSoTimeout(3000);
            byte[] b = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
            String path = url.getFile().isEmpty() ? "/" : url.getFile();
            StringBuilder h = new StringBuilder();
            h.append(method).append(' ').append(path).append(" HTTP/1.0\r\nHost: ").append(url.getHost()).append(':').append(port)
                    .append("\r\nConnection: close\r\n");
            if (body != null)
                h.append("Content-Type: text/xml; charset=\"utf-8\"\r\nContent-Length: ").append(b.length).append("\r\nSOAPAction: ")
                        .append(soapAction).append("\r\n");
            h.append("\r\n");
            OutputStream o = s.getOutputStream();
            o.write(h.toString().getBytes(StandardCharsets.US_ASCII));
            o.write(b);
            o.flush();
            InputStream in = s.getInputStream();
            ByteArrayOutputStream r = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            for (int n; (n = in.read(buf)) > 0 && r.size() < 1 << 20; ) r.write(buf, 0, n);
            String resp = r.toString("UTF-8");
            int eol = resp.indexOf("\r\n");
            String status = eol > 0 ? resp.substring(0, eol) : resp;
            if (!status.matches("HTTP/1\\.\\d 2\\d\\d.*")) {
                Matcher em = Pattern.compile("<errorDescription>([^<]*)</errorDescription>").matcher(resp);
                throw new Exception(status + (em.find() ? " (" + em.group(1) + ")" : ""));
            }
            int hdr = resp.indexOf("\r\n\r\n");
            return hdr >= 0 ? resp.substring(hdr + 4) : "";
        }
    }
}

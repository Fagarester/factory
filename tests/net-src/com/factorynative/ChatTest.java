package com.factorynative;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Chat, typing and voice over NetTunnel on loopback (host + 2 guests: the host passes them on), and the screen's
 * address filter (NetTunnel.noAddr): java -cp classes com.factorynative.ChatTest
 */
public class ChatTest {
    static class L extends NetTest.L {
        final List<String> chat = new CopyOnWriteArrayList<>();
        final List<String> voice = new CopyOnWriteArrayList<>();
        final List<String> typing = new CopyOnWriteArrayList<>();
        @Override public void typing(int src, String from) { typing.add(src + "/" + from); }
        L(String tag) { super(tag); }
        @Override public void log(String s) {}
        @Override public void chat(String from, String text) { chat.add(from + ": " + text); }
        @Override public void voice(int src, String from, byte[] frame) { voice.add(src + "/" + from + "/" + frame.length); }
    }

    static boolean waitFor(java.util.function.BooleanSupplier ok, int ms) throws InterruptedException {
        long end = System.currentTimeMillis() + ms;
        while (!ok.getAsBoolean() && System.currentTimeMillis() < end) Thread.sleep(20);
        return ok.getAsBoolean();
    }

    public static void main(String[] a) throws Exception {
        privacy();

        new NetTest.Server(45297);
        L hl = new L("host"), l1 = new L("g1"), l2 = new L("g2");
        NetTunnel h = new NetTunnel(NetTest.lanCfg(45297, 0), "Хост", hl);
        h.start();
        NetTest.check(NetTest.waitState(h, NetTunnel.HOSTING, 8000), "host up");
        NetTest.check(!h.linked(), "a host alone is not linked");
        NetTunnel g1 = new NetTunnel(NetTest.lanCfg(45297, 46300), "Гость1", NetTest.inviteTo(h, h.boundPort), "", l1);
        g1.start();
        NetTunnel g2 = new NetTunnel(NetTest.lanCfg(45297, 46301), "guest2", NetTest.inviteTo(h, h.boundPort), "", l2);
        g2.start();
        NetTest.check(NetTest.waitState(g1, NetTunnel.CONNECTED, 3000) && NetTest.waitState(g2, NetTunnel.CONNECTED, 3000), "two guests connect");
        NetTest.check(waitFor(() -> h.linked() && g1.linked() && g2.linked(), 3000), "all linked");

        g1.sendTyping();
        g2.sendTyping();
        NetTest.check(waitFor(() -> hl.typing.size() == 2 && l1.typing.size() == 1 && l2.typing.size() == 1, 2000),
                "typing: the host sees both guests, each guest the other: " + hl.typing + " " + l1.typing + " " + l2.typing);
        NetTest.check(l1.typing.get(0).endsWith("/guest2") && l2.typing.get(0).endsWith("/Гость1")
                && !l1.typing.get(0).split("/")[0].equals(l2.typing.get(0).split("/")[0]), "two typing players are told apart");
        h.sendTyping();
        NetTest.check(waitFor(() -> l1.typing.contains("0/Хост") && l2.typing.contains("0/Хост"), 2000), "the host's typing reaches both");
        g1.sendChat("привет, строим?\u0007");
        NetTest.check(waitFor(() -> hl.chat.size() == 1 && l2.chat.size() == 1, 2000),
                "guest 1's line reaches the host and guest 2: " + hl.chat + " " + l2.chat);
        NetTest.check(hl.chat.contains("Гость1: привет, строим?") && l2.chat.contains("Гость1: привет, строим?"),
                "the sender's name from the host, control chars dropped");
        NetTest.check(l1.chat.isEmpty(), "the sender does not get its own line back");
        h.sendChat("да");
        NetTest.check(waitFor(() -> l1.chat.contains("Хост: да") && l2.chat.contains("Хост: да"), 2000), "the host's line reaches both guests");
        StringBuilder longText = new StringBuilder();
        for (int i = 0; i < 1000; i++) longText.append('ж');
        g2.sendChat(longText.toString());
        NetTest.check(waitFor(() -> hl.chat.size() == 2 && l1.chat.size() == 2, 2000)
                && hl.chat.get(1).length() == "guest2: ".length() + NetTunnel.MAX_CHAT, "a long line is cut to " + NetTunnel.MAX_CHAT + " chars");

        byte[] frame = new byte[2 + 3 + NetVoiceFrame.BYTES];
        for (int i = 0; i < 25; i++) g2.sendVoice(frame);
        NetTest.check(waitFor(() -> hl.voice.size() == 25 && l1.voice.size() == 25, 3000),
                "25 voice frames of guest 2 reach the host and guest 1: " + hl.voice.size() + ", " + l1.voice.size());
        NetTest.check(!hl.voice.isEmpty() && hl.voice.get(0).endsWith("/guest2/" + frame.length) && l1.voice.get(0).equals(hl.voice.get(0)),
                "same speaker id and name on both: " + (hl.voice.isEmpty() ? "" : hl.voice.get(0)) + " / " + (l1.voice.isEmpty() ? "" : l1.voice.get(0)));
        NetTest.check(l2.voice.isEmpty(), "the speaker does not hear itself");
        h.sendVoice(frame);
        NetTest.check(waitFor(() -> l1.voice.contains("0/Хост/" + frame.length) && l2.voice.contains("0/Хост/" + frame.length), 2000), "the host's voice: speaker 0");

        g1.stop();
        g2.stop();
        h.stop();
        System.out.println(NetTest.fails == 0 ? "ALL PASS" : NetTest.fails + " FAILED");
        System.exit(NetTest.fails == 0 ? 0 : 1);
    }

    /** One 40 ms Opus frame is about this big; the tunnel does not look inside. */
    static final class NetVoiceFrame {
        static final int BYTES = 130;
    }

    static void privacy() {
        String[][] cases = {
                {"адрес сменился 192.168.1.23:34197 → [2a02:6b8::1]:34197", "адрес сменился … → …"},
                {"Внешний IPv4 85.140.2.7 совпадает", "Внешний IPv4 … совпадает"},
                {"UPnP http://192.168.0.1:5000/rootDesc.xml: отказ", "UPnP http://…/rootDesc.xml: отказ"},
                {"fe80::1%wlan0 и 2001:db8:0:0:1:0:0:1", "… и …"},
                {"порт 34200 занят", "порт … занят"},
                {"Действует до 18:45 · пинг 120 мс, версия 2.1.19", "Действует до 18:45 · пинг 120 мс, версия 2.1.19"},
        };
        for (String[] c : cases) {
            String got = NetTunnel.noAddr(c[0]);
            NetTest.check(got.equals(c[1]), "no address on the screen: «" + c[0] + "» → «" + got + "»");
        }
    }
}

package com.factorynative;
import java.net.*;
public class LanTest { public static void main(String[] a) throws Exception {
  DatagramSocket l = new DatagramSocket(null); l.setReuseAddress(true); l.bind(new InetSocketAddress(34196)); l.setSoTimeout(4000);
  NetTunnel h = NetTest.hostUp(NetTest.lanCfg(45555, 0));
  NetTunnel g = new NetTunnel(NetTest.lanCfg(45555, 46400), "g", NetTest.inviteTo(h, h.boundPort), "", new NetTest.L("g")); g.start();
  NetTest.check(NetTest.waitState(g, NetTunnel.CONNECTED, 3000), "connected");
  byte[] b = new byte[100]; DatagramPacket p = new DatagramPacket(b, b.length); l.receive(p);
  StringBuilder hex = new StringBuilder(); for (int i = 0; i < p.getLength(); i++) hex.append(String.format("%02x", b[i]));
  NetTest.check(hex.toString().equals("0f40b5") && p.getSocketAddress().toString().equals("/127.0.0.1:46400"), "LAN announce " + hex + " from " + p.getSocketAddress());
  g.stop(); h.stop(); System.exit(NetTest.fails);
}}

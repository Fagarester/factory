package com.factorynative;
import java.net.*;
public class PingRouteTest { public static void main(String[] a) throws Exception {
  NetTest.Server srv = new NetTest.Server(45197);
  NetTunnel h = NetTest.hostUp(NetTest.lanCfg(45197, 0));
  NetTunnel g = new NetTunnel(NetTest.lanCfg(45197, 46600), "g", NetTest.inviteTo(h, h.boundPort), "", new NetTest.L("g")); g.start();
  NetTest.waitState(g, NetTunnel.CONNECTED, 3000);
  DatagramSocket game = new DatagramSocket(), pinger = new DatagramSocket(); game.setSoTimeout(600); pinger.setSoTimeout(600);
  byte[] m = "join".getBytes(); game.send(new DatagramPacket(m, m.length, NetTest.LO, 46600));
  byte[] b = new byte[100]; DatagramPacket p = new DatagramPacket(b, 100); game.receive(p);
  NetTest.check(new String(b, 0, p.getLength()).equals("S:join"), "game round trip");
  pinger.send(new DatagramPacket(new byte[] {0, 0x34, 0x12}, 3, NetTest.LO, 46600));
  p = new DatagramPacket(b, 100); pinger.receive(p);
  NetTest.check(p.getLength() == 3 && b[0] == 0x21 && b[1] == 0x34, "the pinger gets the PingReply through the tunnel");
  boolean leaked; try { game.receive(new DatagramPacket(b, 100)); leaked = true; } catch (SocketTimeoutException e) { leaked = false; }
  NetTest.check(!leaked, "the game does not get it");
  game.send(new DatagramPacket(m, m.length, NetTest.LO, 46600)); p = new DatagramPacket(b, 100); game.receive(p);
  NetTest.check(new String(b, 0, p.getLength()).equals("S:join"), "game traffic still goes to the game");
  g.stop(); h.stop(); System.exit(NetTest.fails);
}}

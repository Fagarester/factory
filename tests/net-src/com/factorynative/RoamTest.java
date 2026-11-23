package com.factorynative;
import java.net.*;
public class RoamTest { public static void main(String[] a) throws Exception {
  NetTest.Server srv = new NetTest.Server(45197);
  NetConfig hc = NetTest.lanCfg(45197, 0); hc.approve = true; hc.timeoutSeconds = 3;
  NetTest.L hl = new NetTest.L("host");
  NetTunnel h = new NetTunnel(hc, "host", hl); h.start(); NetTest.waitState(h, NetTunnel.HOSTING, 8000);
  NetTest.Middle m = new NetTest.Middle(new InetSocketAddress(NetTest.LO, h.boundPort));
  NetConfig gc = NetTest.lanCfg(45197, 46500); gc.punchSeconds = 60;
  java.util.concurrent.atomic.AtomicInteger restored = new java.util.concurrent.atomic.AtomicInteger();
  NetTunnel g = new NetTunnel(gc, "roamer", NetTest.inviteTo(h, m.port()), "", new NetTest.L("g") {
    @Override public void log(String l) { super.log(l); if (l.contains("восстановлена")) restored.incrementAndGet(); } }); g.start();
  Thread.sleep(800); h.approve(hl.joins.get(0), true);
  NetTest.check(NetTest.waitState(g, NetTunnel.CONNECTED, 3000) && "S:a".equals(NetTest.roundTrip(46500, "a")), "connected after approval");
  int ports = srv.ports.size();
  // 1. a new outside address, no outage
  m.rebind();
  NetTest.check("S:b".equals(NetTest.roundTrip(46500, "b")), "new address: traffic goes on");
  // 2. outage longer than the host's timeout, then a new address
  m.drop = true;
  long t0 = System.currentTimeMillis();
  while (!g.probing && System.currentTimeMillis() - t0 < 6000) Thread.sleep(50);
  NetTest.check(g.probing, "guest notices the quiet after " + (System.currentTimeMillis() - t0) + " ms");
  Thread.sleep(5000);
  NetTest.check(!h.stats.isEmpty() && h.stats.get(0).lost, "host keeps the player as lost (not removed)");
  m.rebind(); m.drop = false; long t1 = System.currentTimeMillis();
  while (g.probing && System.currentTimeMillis() - t1 < 5000) Thread.sleep(20);
  NetTest.check(!g.probing && g.state == NetTunnel.CONNECTED, "back in " + (System.currentTimeMillis() - t1) + " ms after the network returned");
  NetTest.check("S:c".equals(NetTest.roundTrip(46500, "c")), "round trip after the outage");
  NetTest.check(hl.joins.size() == 1, "no second approval asked (" + hl.joins.size() + " requests)");
  NetTest.check(srv.ports.size() == ports, "the game sees the same player socket (" + srv.ports + ")");
  NetTest.check(!h.stats.get(0).lost, "host: player not lost any more");
  // 3. the phone says its network changed: looks at once
  m.rebind();
  long t2 = System.currentTimeMillis(); int before = restored.get();
  g.networkChanged();
  while (restored.get() == before && System.currentTimeMillis() - t2 < 3000) Thread.sleep(5);
  NetTest.check(restored.get() > before && !g.probing && "S:d".equals(NetTest.roundTrip(46500, "d")), "network change: new handshake in " + (System.currentTimeMillis() - t2) + " ms");
  NetTest.check(hl.joins.size() == 1, "still no approval asked");
  g.stop(); h.stop(); m.close();
  System.out.println(NetTest.fails == 0 ? "ALL PASSED" : NetTest.fails + " FAILED"); System.exit(NetTest.fails);
}}

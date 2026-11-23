package com.factorynative;
public class InviteV2Test { public static void main(String[] a) throws Exception {
  NetConfig hc = NetConfig.preset(2); hc.gamePort = 45197;  // «Мобильный интернет»: no LAN, no UPnP, 300 s punching
  NetTunnel h = new NetTunnel(hc, "h", new NetTest.L("h")); h.start(); NetTest.waitState(h, NetTunnel.HOSTING, 9000);
  NetCodes.Invite v = NetCodes.decodeInvite(h.inviteText); NetConfig g = NetConfig.forGuest(v);
  NetTest.check(!g.lan && g.ipv6 && !g.upnp && g.stun && g.punchSeconds == 300 && g.pingSeconds == 1, "guest takes the host's settings from the QR");
  h.stop(); System.exit(NetTest.fails);
}}

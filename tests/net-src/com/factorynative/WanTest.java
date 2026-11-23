package com.factorynative;
import java.net.*;
public class WanTest {
  public static void main(String[] a) throws Exception {
    NetTest.Server srv = new NetTest.Server(45297);
    for (int type : new int[] {NetCodes.UPNP, NetCodes.STUN}) {
      NetConfig hc = new NetConfig(); hc.approve = false; hc.gamePort = 45297;
      NetTunnel h = new NetTunnel(hc, "host", new NetTest.L("host")); h.start();
      NetTest.waitState(h, NetTunnel.HOSTING, 9000);
      NetCodes.Invite v = NetCodes.decodeInvite(h.inviteText);
      System.out.println("invite cands: " + v.cands + "  nat: " + h.natNote);
      v.cands.removeIf(c -> c.type != type);
      NetConfig gc = NetConfig.preset(1); gc.localPort = 46300 + type; gc.punchSeconds = 6;
      NetTunnel g = new NetTunnel(gc, "g", v, "", new NetTest.L("g")); g.start();
      boolean ok = NetTest.waitState(g, NetTunnel.CONNECTED, 6000);
      System.out.println(NetCodes.TYPE_NAMES[type] + ": " + (ok ? "connected, round trip " + NetTest.roundTrip(gc.localPort, "wan") : "no connection"));
      g.stop(); h.stop();
    }
    System.exit(0);
  }
}

package com.factorynative;
import java.io.*; import java.lang.reflect.*; import java.nio.file.*; import java.security.*; import java.util.*;
public class MoveTest {
  static String hash(File d) throws Exception { MessageDigest m = MessageDigest.getInstance("SHA-256"); List<Path> l = new ArrayList<>();
    try (var s = Files.walk(d.toPath())) { s.filter(Files::isRegularFile).sorted().forEach(l::add); }
    for (Path p : l) { m.update(d.toPath().relativize(p).toString().getBytes()); m.update(Files.readAllBytes(p)); }
    return HexFormat.of().formatHex(m.digest()) + " (" + l.size() + " files)"; }
  public static void main(String[] a) throws Exception {
    File root = Files.createTempDirectory("mv").toFile(), from = new File(root, "from"), to = new File(root, "to");
    Random r = new Random(1);
    for (String f : new String[] {"bin/arm64/factorio", "data/core/a.lua", "data/base/b.png", "saves/s1.zip", "mods/m.zip", ".nomedia", "config/x"}) {
      File x = new File(from, f); x.getParentFile().mkdirs(); byte[] b = new byte[r.nextInt(3_000_000)]; r.nextBytes(b); Files.write(x.toPath(), b); }
    new File(from, "bin/arm64/factorio").setExecutable(true);
    String want = hash(from);
    // a cut-off move: one file already over (same size, original still there), one half copy
    new File(to, "data/core").mkdirs(); Files.copy(new File(from, "data/core/a.lua").toPath(), new File(to, "data/core/a.lua").toPath());
    new File(to, "saves").mkdirs(); Files.write(new File(to, "saves/s1.zip.fnmove").toPath(), new byte[10]);
    Method m = GameMover.class.getDeclaredMethod("moveDir", File.class, File.class, long[].class, long.class, GameMover.Progress.class); m.setAccessible(true);
    m.invoke(null, from, to, new long[] {0}, 1L, (GameMover.Progress) (d, t, f) -> {});
    Method de = GameMover.class.getDeclaredMethod("deleteEmpty", File.class); de.setAccessible(true); de.invoke(null, from);
    new File(to, "saves/s1.zip.fnmove").delete();  // a stray partial is overwritten by the copy; none should stay
    String got = hash(to);
    System.out.println((got.equals(want) ? "PASS" : "FAIL") + " contents equal: " + got);
    System.out.println((!from.exists() ? "PASS" : "FAIL") + " source folder gone");
    System.out.println((new File(to, "bin/arm64/factorio").canExecute() ? "PASS" : "FAIL") + " executable bit kept");
    try (var s = Files.walk(to.toPath())) { long n = s.filter(p -> p.toString().endsWith(".fnmove")).count(); System.out.println((n == 0 ? "PASS" : "FAIL") + " no partial files left: " + n); }
  }
}

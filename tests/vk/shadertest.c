// Compiles every game shader through fnvk's GLSL 330 -> SPIR-V path (pairs by base name, else a stub stage).
// Build: tools/build-vktests.sh; run: out/vk-shadertest <shader dir>
#include "../../fnsdl/vk/vk_shader.h"

#include <dirent.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static char *slurp(const char *dir, const char *name) {
  char path[1024];
  snprintf(path, sizeof path, "%s/%s", dir, name);
  FILE *f = fopen(path, "rb");
  if (!f) return NULL;
  fseek(f, 0, SEEK_END);
  long n = ftell(f);
  fseek(f, 0, SEEK_SET);
  char *b = malloc(n + 1);
  fread(b, 1, n, f);
  b[n] = 0;
  fclose(f);
  return b;
}

static const char stub_vs[] = "#version 330\nvoid main()\n{\n    gl_Position = vec4(0.0);\n}\n";
static const char stub_fs[] = "#version 330\nlayout(location = 0) out vec4 fragColor;\nvoid main()\n{\n    fragColor = vec4(0.0);\n}\n";

int main(int argc, char **argv) {
  const char *dir = argc > 1 ? argv[1] : ".";
  DIR *d = opendir(dir);
  struct dirent *e;
  int ok = 0, bad = 0;
  while ((e = readdir(d))) {
    size_t l = strlen(e->d_name);
    int vert = l > 5 && !strcmp(e->d_name + l - 5, ".vert");
    int frag = l > 5 && !strcmp(e->d_name + l - 5, ".frag");
    if (!vert && !frag) continue;
    char other[512];
    snprintf(other, sizeof other, "%.*s.%s", (int)l - 5, e->d_name, vert ? "frag" : "vert");
    char *a = slurp(dir, e->d_name), *b = slurp(dir, other);
    if (frag && b) { free(a); free(b); continue; }  // pairs are done from the .vert side
    fnvk_spirv s;
    int r = vert ? fnvk_compile_program(a, b ? b : stub_fs, &s) : fnvk_compile_program(stub_vs, a, &s);
    if (r) {
      ok++;
      printf("ok   %-40s %s words %zu+%zu res", e->d_name, b ? other : "(stub)", s.words[0], s.words[1]);
      for (int i = 0; i < s.nres; i++) printf(" %d:%s/%d%s", i, s.res[i].name, s.res[i].kind, s.res[i].view == 1 ? "[]" : s.res[i].view == 2 ? "3d" : "");
      printf("\n");
    } else {
      bad++;
      printf("FAIL %-40s %s\n%s\n", e->d_name, b ? other : "(stub)", s.log);
    }
    fnvk_free_spirv(&s);
    free(a);
    free(b);
  }
  printf("%d ok, %d failed\n", ok, bad);
  return bad != 0;
}

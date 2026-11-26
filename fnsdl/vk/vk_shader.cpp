// GLSL 330 (the game's shaders, SPIRV-Cross output) -> Vulkan GLSL 450 -> SPIR-V with glslang.
// The rewrite is line based: uniform blocks and samplers get set 0 and a binding (one per name, shared by both
// stages), varyings get locations matched by name (as GL links them), and the vertex shader maps GL depth
// (-w..w) to Vulkan's 0..w. Images keep GL's row order; only the window may be flipped/turned (window_prelude).
#include "vk_shader.h"

#include <glslang/Public/ShaderLang.h>
#include <SPIRV/GlslangToSpv.h>

#include <cstdio>
#include <cstdlib>
#include <cctype>
#include <cstring>
#include <map>
#include <sstream>
#include <string>
#include <vector>

static const TBuiltInResource limits = {
    32, 6, 32, 32, 64, 4096, 64, 32, 80, 32, 4096, 32, 128, 8, 16, 16, 15, -8, 7, 8,
    65535, 65535, 65535, 1024, 1024, 64, 1024, 16, 8, 8, 1, 60, 64, 64, 128, 128, 8, 8, 8, 0,
    0, 0, 0, 0, 8, 8, 16, 256, 1024, 1024, 64, 128, 128, 16, 1024, 4096, 128, 128, 16, 1024,
    120, 32, 64, 16, 0, 0, 0, 0, 8, 8, 1, 0, 0, 0, 0, 1, 1, 16384, 4, 64, 8, 8, 4,
    256, 512, 32, 1, 1, 32, 1, 1, 4,
    256, 256, 128, 128, 128, 128, 128, 128, 4, 1,
    {true, true, true, true, true, true, true, true, true}};

namespace {

struct Program {
  std::map<std::string, int> binding;   // resource name -> binding
  std::map<std::string, int> location;  // varying name -> location
  int next_location = 0;
  fnvk_spirv *out;
};

bool starts(const std::string &s, const char *p) { return s.compare(0, strlen(p), p) == 0; }

std::string trim(const std::string &s) {
  size_t a = s.find_first_not_of(" \t\r");
  if (a == std::string::npos) return "";
  return s.substr(a, s.find_last_not_of(" \t\r") - a + 1);
}

int locations_of(const std::string &type, const std::string &name) {
  int n = 1;
  if (type == "mat4" || type == "mat4x4") n = 4;
  else if (type == "mat3" || type == "mat3x3") n = 3;
  else if (type == "mat2" || type == "mat2x2") n = 2;
  size_t b = name.find('[');
  if (b != std::string::npos) n *= atoi(name.c_str() + b + 1);
  return n;
}

int add_resource(Program &p, const std::string &name, int kind, int view, int stage) {
  auto it = p.binding.find(name);
  if (it != p.binding.end()) {
    p.out->res[it->second].stages |= stage;
    return it->second;
  }
  int b = p.out->nres++;
  fnvk_resource &r = p.out->res[b];
  snprintf(r.name, sizeof r.name, "%s", name.c_str());
  r.kind = kind;
  r.view = view;
  r.stages = stage;
  p.binding[name] = b;
  return b;
}

// Splits "flat out vec4 vColor;" into qualifiers, type, name (without ';').
bool parse_decl(const std::string &line, const char *io, std::string &quals, std::string &type, std::string &name) {
  std::istringstream ss(line);
  std::vector<std::string> w;
  std::string t;
  while (ss >> t) w.push_back(t);
  size_t i = 0;
  quals.clear();
  while (i < w.size() && w[i] != io) {
    if (w[i] != "flat" && w[i] != "noperspective" && w[i] != "smooth" && w[i] != "centroid") return false;
    quals += w[i] + " ";
    i++;
  }
  if (i + 3 != w.size()) return false;
  type = w[i + 1];
  name = w[i + 2];
  if (name.empty() || name.back() != ';') return false;
  name.pop_back();
  return true;
}

// Drawing into the swapchain image (rows top-down, maybe pre-rotated for the display) is set per pipeline by
// specialization constants: fnvk_mode 0 = GL's order (offscreen images), 1 = rows flipped (the viewport mirrors
// y), 2/3/4 = flipped and rotated 90/180/270 degrees clockwise (the vertex shader turns the position, the viewport
// is the turned rectangle). fnvk_W/fnvk_H: the window size as the game sees it. The fragment shader gets GL's
// window coordinates and derivatives back from Vulkan's (gl_FragCoord, dFdx, dFdy replaced).
std::string window_prelude(int vertex) {
  std::string s =
      "layout(constant_id = 100) const int fnvk_mode = 0;\n"
      "layout(constant_id = 101) const float fnvk_W = 0.0;\n"
      "layout(constant_id = 102) const float fnvk_H = 0.0;\n";
  if (vertex) return s;
  s += "vec4 fnvk_fc()\n{\n    vec4 c = gl_FragCoord;\n"
       "    if (fnvk_mode == 1) c.y = fnvk_H - c.y;\n"
       "    else if (fnvk_mode == 2) c.xy = c.yx;\n"
       "    else if (fnvk_mode == 3) c.x = fnvk_W - c.x;\n"
       "    else if (fnvk_mode == 4) c.xy = vec2(fnvk_W - c.y, fnvk_H - c.x);\n"
       "    return c;\n}\n";
  for (const char *t : {"float", "vec2", "vec3", "vec4"}) {
    std::string T = t;
    s += T + " fnvk_dFdx(" + T + " v)\n{\n    return fnvk_mode == 2 ? dFdy(v) : fnvk_mode == 3 ? -dFdx(v) : "
         "fnvk_mode == 4 ? -dFdy(v) : dFdx(v);\n}\n";
    s += T + " fnvk_dFdy(" + T + " v)\n{\n    return fnvk_mode == 1 ? -dFdy(v) : fnvk_mode == 2 ? dFdx(v) : "
         "fnvk_mode == 4 ? -dFdx(v) : dFdy(v);\n}\n";
  }
  return s;
}

// Every whole-word `from` in `line` -> `to`.
void replace_word(std::string &line, const char *from, const char *to) {
  size_t n = strlen(from), i = 0;
  auto word = [](char c) { return isalnum((unsigned char)c) || c == '_'; };
  while ((i = line.find(from, i)) != std::string::npos) {
    if ((i && word(line[i - 1])) || (i + n < line.size() && word(line[i + n]))) {
      i += n;
      continue;
    }
    line.replace(i, n, to);
    i += strlen(to);
  }
}

std::string rewrite(Program &p, const char *src, int vertex) {
  std::istringstream in(src);
  std::string line, out;
  int stage = vertex ? 1 : 2;
  bool version_done = false;
  while (std::getline(in, line)) {
    std::string t = trim(line);
    if (!version_done && starts(t, "#version")) {
      out += "#version 450\n#define gl_VertexID gl_VertexIndex\n#define gl_InstanceID gl_InstanceIndex\n";
      out += window_prelude(vertex);
      version_done = true;
      continue;
    }
    if (starts(t, "layout(std140) uniform ")) {
      std::string name = trim(t.substr(strlen("layout(std140) uniform ")));
      int b = add_resource(p, name, FNVK_RES_UBO, 0, stage);
      out += "layout(std140, set = 0, binding = " + std::to_string(b) + ") uniform " + name + "\n";
      continue;
    }
    if (starts(t, "uniform ")) {
      std::istringstream ss(t);
      std::string u, type, name;
      ss >> u >> type >> name;
      if (!name.empty() && name.back() == ';') name.pop_back();
      int kind = FNVK_RES_SAMPLER, view = FNVK_VIEW_2D;
      if (type.find("Buffer") != std::string::npos) kind = FNVK_RES_TEXEL_BUFFER;
      else if (type.find("2DArray") != std::string::npos) view = FNVK_VIEW_2D_ARRAY;
      else if (type.find("3D") != std::string::npos) view = FNVK_VIEW_3D;
      int b = add_resource(p, name, kind, view, stage);
      out += "layout(set = 0, binding = " + std::to_string(b) + ") " + t + "\n";
      continue;
    }
    std::string quals, type, name;
    if (!starts(t, "layout") && parse_decl(t, vertex ? "out" : "in", quals, type, name)) {
      std::string base = name.substr(0, name.find('['));
      auto it = p.location.find(base);
      int loc;
      if (it != p.location.end()) loc = it->second;
      else {
        loc = p.next_location;
        p.location[base] = loc;
        p.next_location += locations_of(type, name);
      }
      out += "layout(location = " + std::to_string(loc) + ") " + t + "\n";
      continue;
    }
    if (vertex && t == "void main()") {
      out += "void fnvk_main()\n";
      continue;
    }
    if (!vertex && version_done) {  // window coordinates and derivatives as GL has them (window_prelude)
      replace_word(line, "gl_FragCoord", "fnvk_fc()");
      replace_word(line, "dFdx", "fnvk_dFdx");
      replace_word(line, "dFdy", "fnvk_dFdy");
    }
    out += line + "\n";
  }
  if (vertex)
    out += "void main()\n{\n    fnvk_main();\n"
           "    if (fnvk_mode == 2) gl_Position.xy = gl_Position.yx;\n"
           "    else if (fnvk_mode == 3) gl_Position.x = -gl_Position.x;\n"
           "    else if (fnvk_mode == 4) gl_Position.xy = -gl_Position.yx;\n"
           "    gl_Position.z = (gl_Position.z + gl_Position.w) * 0.5;\n}\n";
  return out;
}

bool compile(glslang::TProgram &prog, glslang::TShader &sh, const std::string &src, EShLanguage lang,
             std::string &log) {
  const char *s = src.c_str();
  sh.setStrings(&s, 1);
  sh.setEnvInput(glslang::EShSourceGlsl, lang, glslang::EShClientVulkan, 100);
  sh.setEnvClient(glslang::EShClientVulkan, glslang::EShTargetVulkan_1_1);
  sh.setEnvTarget(glslang::EShTargetSpv, glslang::EShTargetSpv_1_3);
  if (!sh.parse(&limits, 450, false, (EShMessages)(EShMsgSpvRules | EShMsgVulkanRules))) {
    log += sh.getInfoLog();
    return false;
  }
  prog.addShader(&sh);
  return true;
}

}  // namespace

// Both stages -> SPIR-V into out (code and log; the caller has set the resources).
static int to_spirv(const std::string &vs, const std::string &fs, fnvk_spirv *out) {
  static bool init;
  if (!init) init = glslang::InitializeProcess();
  std::string log;
  glslang::TProgram prog;
  glslang::TShader vsh(EShLangVertex), fsh(EShLangFragment);
  bool ok = compile(prog, vsh, vs, EShLangVertex, log) && compile(prog, fsh, fs, EShLangFragment, log);
  if (ok && !prog.link((EShMessages)(EShMsgSpvRules | EShMsgVulkanRules))) {
    log += prog.getInfoLog();
    ok = false;
  }
  if (ok) {
    std::vector<unsigned> code[2];
    glslang::SpvOptions opt;
    opt.disableOptimizer = true;
    glslang::GlslangToSpv(*prog.getIntermediate(EShLangVertex), code[0], &opt);
    glslang::GlslangToSpv(*prog.getIntermediate(EShLangFragment), code[1], &opt);
    for (int i = 0; i < 2; i++) {
      out->words[i] = code[i].size();
      out->code[i] = (uint32_t *)malloc(code[i].size() * 4);
      memcpy(out->code[i], code[i].data(), code[i].size() * 4);
    }
  }
  snprintf(out->log, sizeof out->log, "%s", log.c_str());
  return ok;
}

extern "C" int fnvk_compile_program(const char *vs, const char *fs, fnvk_spirv *out) {
  memset(out, 0, sizeof *out);
  Program p;
  p.out = out;
  std::string vsrc = rewrite(p, vs, 1), fsrc = rewrite(p, fs, 0);
  if (getenv("FNVK_DUMP_SHADERS")) fprintf(stderr, "[fnvk] vertex:\n%s\n[fnvk] fragment:\n%s\n", vsrc.c_str(), fsrc.c_str());
  return to_spirv(vsrc, fsrc, out);
}

// Our own Vulkan GLSL (no rewrite; the upscale pass in vk_window.c): out->nres stays 0.
extern "C" int fnvk_compile_raw(const char *vs, const char *fs, fnvk_spirv *out) {
  memset(out, 0, sizeof *out);
  return to_spirv(vs, fs, out);
}

// Changes with every build of this file: a SPIR-V cache of an older rewrite is not used (nvk_draw.c).
extern "C" const char *fnvk_rewrite_id(void) { return __DATE__ " " __TIME__; }

extern "C" void fnvk_free_spirv(fnvk_spirv *s) {
  free(s->code[0]);
  free(s->code[1]);
  s->code[0] = s->code[1] = NULL;
}

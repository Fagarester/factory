// The game's input method switched while it runs (gear menu: gamepad <-> touch / cursor = keyboard and mouse),
// the way the game itself does it after "input method not found" (InInputMethodNotFoundNotification::process,
// 2.1.24): the setting's value, its "changed" flag, then its signal — the game turns its GUI to the new method.
// global -> +496 the settings; the input method item at +192: value at +152 (the byte getInputMethod reads at
// +344), "changed" flag at +136 from its vtable +40, the Signal<> at +0x48. These places are checked in the game's
// own code before use; another game version without them just keeps its method. Done on the game's main thread:
// libfnsdl calls fn_game_poll from SDL_PollEvent.
#include "fn.h"

#include <stdint.h>

static uintptr_t global_var;  // &global (GlobalContext*)
static void (*signal_fire)(void *);
static volatile int wanted = -1;  // 0 keyboard and mouse, 1 game controller

void fn_inputmethod_init(fn_image *img) {
  uintptr_t g = fn_find_symbol(img, "global");
  uintptr_t sig = fn_find_symbol(img, "_ZN6SignalIJEEclEv");
  const uint32_t *f = (const uint32_t *)fn_find_symbol(img, "_ZN33InInputMethodNotFoundNotification7processEP10AppManager");
  // ldr x20,[x8,#496]; ldr x9,[x20,#192]!; strb w8,[x20,#152]; ldr x8,[x9,#40]; add x0,x20,#0x48; strb w8,[x20,#136]
  if (!g || !sig || !f || f[12] != 0xf940f914u || f[17] != 0xf84c0e89u || f[18] != 0x39026288u || f[20] != 0xf9401528u ||
      f[23] != 0x91012280u || f[24] != 0x39022288u) {
    fn_log("input method: the game's switch not found, the app does not switch it");
    return;
  }
  global_var = g;
  signal_fire = (void (*)(void *))sig;
  fn_log("input method: switching ready");
}

static void apply(int method) {
  uintptr_t ctx = *(volatile uintptr_t *)global_var;
  if (!ctx) return;
  uint8_t *settings = *(uint8_t **)(ctx + 496);
  if (!settings) return;
  uint8_t *item = settings + 192;
  if (item[152] == (uint8_t)method) return;
  item[152] = (uint8_t)method;
  int (*changed)(void *) = (int (*)(void *))(*(void ***)item)[40 / 8];
  item[136] = (uint8_t)(changed(item) & 1);
  signal_fire(item + 0x48);
  fn_log("input method: %s", method ? "game controller" : "keyboard and mouse");
}

// From libfnsdl, on the game's main thread (its event polling).
__attribute__((visibility("default"))) void fn_game_poll(void) {
  int m = wanted;
  if (m < 0 || !global_var) return;
  wanted = -1;
  apply(m);
}

#ifdef __ANDROID__
#include <jni.h>
__attribute__((visibility("default"))) void Java_com_factorynative_GameActivity_nativeSetInputMethod(JNIEnv *env, jclass cls, jint m) {
  (void)env;
  (void)cls;
  wanted = m ? 1 : 0;
}
#endif

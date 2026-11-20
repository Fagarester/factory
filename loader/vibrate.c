// The game's vibration with keyboard and mouse. Factorio rumbles only while the player uses a gamepad: the
// vibrating spots check the input method (InputHandlerAgui::getInputMethod: global+496 → +344 == 1) or that a
// gamepad is the active one (SDLWindow::getActiveGameController, window vtable +304). These checks are taken out
// in the vibration paths alone, so the game asks SDLWindow::vibrateActiveGameController with the keys too; that
// ends in SDL_RumbleGamepad(no gamepad), which libfnsdl gives to the app's unseen gamepad, played on the phone's
// vibrator (gear menu switch). The input method itself stays as it is: it decides much more (129 functions).
// Found in 2.1.24; each spot is checked by its instructions, another game version just keeps its checks.
#include "fn.h"

#include <string.h>

typedef struct { uint32_t mask, value; } insn;

#define NOP 0xd503201fu

// ldr xA, [xB, #496]; ldrb wC, [xD, #344]; cmp wC, #1; b.ne — "the input method is the gamepad", else skip
static const insn gate_input_method[] = {
    {0xfffffc00u, 0xf940f800u}, {0xfffffc00u, 0x39456000u}, {0xfffffc1fu, 0x7100041fu}, {0xff00001fu, 0x54000001u}};
// ldr x8, [x8, #304]; blr x8; cbz x0 — "no active gamepad", else skip
static const insn gate_no_gamepad[] = {{0xfffffc00u, 0xf9409800u}, {0xfffffc1fu, 0xd63f0000u}, {0xff00001fu, 0xb4000000u}};

// Turns the branch closing each [pat] in [symbol] into a nop; [want] matches or nothing is changed.
static int open_gates(fn_image *img, const char *symbol, const insn *pat, int n, int want) {
  const Elf64_Sym *s = NULL;
  for (size_t i = 0; i < img->nsyms && !s; i++)
    if (img->symtab[i].st_value && !strcmp(img->strtab + img->symtab[i].st_name, symbol)) s = &img->symtab[i];
  if (!s || !s->st_size) {
    fn_log("vibration: %s not found", symbol);
    return 0;
  }
  uint32_t *w = (uint32_t *)((uintptr_t)img->base + s->st_value), *hits[4];
  size_t count = s->st_size / 4;
  int found = 0;
  for (size_t i = 0; i + n <= count; i++) {
    int k = 0;
    while (k < n && (w[i + k] & pat[k].mask) == pat[k].value) k++;
    if (k < n) continue;
    if (found < 4) hits[found] = &w[i + n - 1];
    found++;
  }
  if (found != want) {
    fn_log("vibration: %s has %d checks, %d expected — left as is", symbol, found, want);
    return 0;
  }
  for (int k = 0; k < found; k++) *hits[k] = NOP;
  return found;
}

void fn_vibrate_init(fn_image *img) {
  int n = open_gates(img, "_ZNK5Sound18playAndGetInstanceEff9NamedBoolI7LoopTagEiSt8optionalINS_4TypeEENS_6StreamES0_I10VibrateTagE",
                     gate_input_method, 4, 1);
  n += open_gates(img, "_ZN8GameView13prepareSoundsEv", gate_input_method, 4, 1);
  n += open_gates(img, "_ZN26PlaySoundTriggerEffectItem13shouldVibrateERK14ShootingTarget", gate_no_gamepad, 3, 2);
  fn_log("vibration: %d gamepad checks taken out (4 in 2.1.24)", n);
}

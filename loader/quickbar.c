// The player's quick bar, read from the game's memory (for the app's quick bar side panel).
// QuickBar* comes from the QuickBarGui constructor (x1 = QuickBar&, the local player's bar); item IDs map to
// prototypes through PrototypeList<ItemPrototype>::indexToPrototype. Layouts found in 2.1.19
// (QuickBar::at, QuickBarSlot::setID; checked in 2.1.24 on a save): pages = vector<vector<QuickBarSlot>> at
// QuickBar+0x10 (10 x 10), slot = 0x90 bytes, item ID u16 at +0x08 (quality u8 at +0x0a), type u8 at +0x88
// (1 = item, 0 = empty).
// FN_QB_PROBE=1 logs what it finds every 5 s (to work out and check the layout).
#include "fn.h"

#include <pthread.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

static volatile uint64_t qb_ptr;     // QuickBar*
static uintptr_t index_to_proto;     // &std::vector<ItemPrototype*>

// Android heap pointers carry a tag in the top byte (0xb4…): compare and check without it.
#define UNTAG(v) ((uint64_t)(v) & 0x00ffffffffffffffull)

static int readable_string(uintptr_t at, char *out, size_t cap) {
  const char *p = *(const char **)at;
  uint64_t len = *(const uint64_t *)(at + 8);
  if (!p || len == 0 || len >= cap || UNTAG(p) >= 0x0000800000000000ull || UNTAG(p) < 0x10000) return 0;
  for (uint64_t i = 0; i < len; i++) {
    char c = p[i];
    if (c < 0x20 || c > 0x7e) return 0;
  }
  memcpy(out, p, len);
  out[len] = 0;
  return 1;
}

static int plausible_ptr(uint64_t v) {
  v = UNTAG(v);
  return v >= 0x10000 && !(v & 7) && v < 0x0000800000000000ull;
}

static void probe(void) {
  uintptr_t qb = (uintptr_t)qb_ptr;
  fn_log("quickbar probe: QuickBar* %p, indexToPrototype %p", (void *)qb, (void *)index_to_proto);
  if (!qb) return;
  for (int off = 0; off < 0x40; off += 16)
    fn_log("  QuickBar+0x%02x: %016llx %016llx", off, (unsigned long long)*(uint64_t *)(qb + off),
           (unsigned long long)*(uint64_t *)(qb + off + 8));
  for (int off = 0; off < 0x80; off += 8) {
    uint64_t b = *(uint64_t *)(qb + off), e = *(uint64_t *)(qb + off + 8);
    if (!plausible_ptr(b) || !plausible_ptr(e) || UNTAG(e) <= UNTAG(b) || (UNTAG(e) - UNTAG(b)) % 24 ||
        (UNTAG(e) - UNTAG(b)) / 24 > 20) continue;
    int pages = (int)((UNTAG(e) - UNTAG(b)) / 24);
    fn_log("  +0x%x: %d pages?", off, pages);
    for (int pg = 0; pg < pages && pg < 2; pg++) {
      uint64_t sb = *(uint64_t *)(b + pg * 24), se = *(uint64_t *)(b + pg * 24 + 8);
      if (!plausible_ptr(sb) || UNTAG(se) < UNTAG(sb) || (UNTAG(se) - UNTAG(sb)) % 0x90) { fn_log("   page %d: not slots", pg); continue; }
      int n = (int)((UNTAG(se) - UNTAG(sb)) / 0x90);
      for (int i = 0; i < n && i < 10; i++) {
        uintptr_t slot = sb + i * 0x90;
        unsigned id = *(uint16_t *)(slot + 0x08);  // IDWithQualityFilter: item ID u16, quality u8 at +0x0a
        unsigned type = *(uint8_t *)(slot + 0x88);
        if (getenv("FN_QB_DUMP") && pg == 0 && (i == 0 || i == 9)) {  // a full slot and an empty one, as words
          for (int w = 0; w < 0x90; w += 16)
            fn_log("    slot %d +0x%02x: %016llx %016llx", i, w, (unsigned long long)*(uint64_t *)(slot + w),
                   (unsigned long long)*(uint64_t *)(slot + w + 8));
        }
        char name[96] = "?";
        if (id != 0xffff && index_to_proto) {
          uint64_t vb = *(uint64_t *)index_to_proto, ve = *(uint64_t *)(index_to_proto + 8);
          if (plausible_ptr(vb) && id < (UNTAG(ve) - UNTAG(vb)) / 8) {
            uintptr_t proto = *(uintptr_t *)(vb + id * 8);
            int found = -1;
            for (int so = 8; so < 0x80 && found < 0; so += 8)
              if (plausible_ptr(proto) && readable_string(proto + so, name, sizeof name)) found = so;
            fn_log("   page %d slot %d: type %u id %u proto %p name '%s' (string at +0x%x)", pg, i, type, id,
                   (void *)proto, name, found);
            continue;
          }
        }
        fn_log("   page %d slot %d: type %u id %u", pg, i, type, id);
      }
    }
  }
}

static void *probe_thread(void *arg) {
  (void)arg;
  for (;;) {
    sleep(5);
    probe();
  }
  return NULL;
}

void fn_quickbar_init(fn_image *img) {
  index_to_proto = fn_find_symbol(img, "_ZN13PrototypeListI13ItemPrototypeE16indexToPrototypeE");
  if (fn_hook_store(img, "_ZN11QuickBarGuiC2ER8QuickBar10GuiContext", 1, &qb_ptr) != 0)
    fn_log("quickbar: QuickBarGui constructor not found");
  if (getenv("FN_QB_PROBE")) {
    pthread_t t;
    pthread_create(&t, NULL, probe_thread, NULL);
    pthread_detach(t);
  }
}

// The item names of the two visible quick bar rows (20 slots, NULL = empty) into names[] (each up to 63 chars).
// Row r shows page rows[r] (QuickBar+0x28: vector<u8>, one page per row; checked only as far as page 0 / 1 in
// row 1 / 2 on a save). Returns 0 when the bar is not known (no game yet).
int fn_quickbar_names(char names[20][64]) {
  uintptr_t qb = (uintptr_t)qb_ptr;
  if (!qb || !index_to_proto) return 0;
  uint64_t pb = *(uint64_t *)(qb + 0x10), pe = *(uint64_t *)(qb + 0x18);
  if (!plausible_ptr(pb) || !plausible_ptr(pe) || UNTAG(pe) <= UNTAG(pb) || (UNTAG(pe) - UNTAG(pb)) % 24) return 0;
  int pages = (int)((UNTAG(pe) - UNTAG(pb)) / 24);
  int rowpage[2] = {0, 1};
  uint64_t rb = *(uint64_t *)(qb + 0x28), re = *(uint64_t *)(qb + 0x30);
  if (plausible_ptr(rb) && UNTAG(re) >= UNTAG(rb) + 2 && UNTAG(re) - UNTAG(rb) <= 16) {
    const uint8_t *rows = (const uint8_t *)(uintptr_t)rb;
    if (rows[0] < pages) rowpage[0] = rows[0];
    if (rows[1] < pages) rowpage[1] = rows[1];
  }
  uint64_t vb = *(uint64_t *)index_to_proto, ve = *(uint64_t *)(index_to_proto + 8);
  size_t nproto = plausible_ptr(vb) && UNTAG(ve) >= UNTAG(vb) ? (UNTAG(ve) - UNTAG(vb)) / 8 : 0;
  for (int r = 0; r < 2; r++) {
    uintptr_t page = pb + (uintptr_t)rowpage[r] * 24;
    uint64_t sb = *(uint64_t *)page, se = *(uint64_t *)(page + 8);
    int n = plausible_ptr(sb) && UNTAG(se) >= UNTAG(sb) ? (int)((UNTAG(se) - UNTAG(sb)) / 0x90) : 0;
    for (int i = 0; i < 10; i++) {
      char *out = names[r * 10 + i];
      out[0] = 0;
      if (i >= n) continue;
      uintptr_t slot = sb + i * 0x90;
      unsigned id = *(uint16_t *)(slot + 0x08);
      if (*(uint8_t *)(slot + 0x88) != 1 || id == 0xffff || id >= nproto) continue;
      uintptr_t proto = *(uintptr_t *)(vb + id * 8);
      if (plausible_ptr(proto)) readable_string(proto + 8, out, 64);  // PrototypeBase::name
    }
  }
  return 1;
}

#ifdef __ANDROID__
#include <jni.h>
// GameActivity.nativeQuickBar(): 20 item names of the visible rows ("" = empty slot), or null without a game.
__attribute__((visibility("default"))) jobjectArray Java_com_factorynative_GameActivity_nativeQuickBar(JNIEnv *env,
                                                                                                    jclass cls) {
  (void)cls;
  char names[20][64];
  if (!fn_quickbar_names(names)) return NULL;
  jclass str = (*env)->FindClass(env, "java/lang/String");
  jobjectArray out = (*env)->NewObjectArray(env, 20, str, NULL);
  for (int i = 0; i < 20; i++) {
    jstring s = (*env)->NewStringUTF(env, names[i]);
    (*env)->SetObjectArrayElement(env, out, i, s);
    (*env)->DeleteLocalRef(env, s);
  }
  return out;
}
#endif

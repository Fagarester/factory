// The game restarting itself: after its own updater has installed a new version it tears its state down and calls
// execv(<game>/bin/arm64/factorio, args) (AppManager::restartApp). Inside an Android app that cannot work: execv
// failed and the game showed "Unexpected error". Here the request is noted and the calling thread waits; the
// activity sees it (GameActivity.nativeRestartWanted) and starts the game again through the launcher.
#include <jni.h>
#include <stdatomic.h>
#include <stdint.h>
#include <string.h>
#include <unistd.h>

#include "fn.h"

static atomic_int wanted;
static volatile uint64_t packages;  // Updater::updatePackage calls: the game installs an update (≈7 s each)
static pid_t game_pid;

void fn_restart_init(void) { game_pid = getpid(); }

int fn_game_execv(const char *path, char *const argv[]) {
  if (getpid() != game_pid) return execv(path, argv);  // a forked child (opening a link...): as before
  fn_log("game asks to restart itself (execv %s): the activity starts it again", path);
  for (int i = 1; argv && argv[i]; i++) fn_log("  restart argument %d: %s", i, argv[i]);  // not passed on yet
  atomic_store(&wanted, 1);
  for (;;) sleep(60);  // the process ends from the activity
}

__attribute__((visibility("default"))) jboolean Java_com_factorynative_GameActivity_nativeRestartWanted(JNIEnv *env, jclass cls) {
  return atomic_load(&wanted) ? JNI_TRUE : JNI_FALSE;
}

// The game's updater installing a package: the activity shows "installing, not frozen" over the stopped picture.
void fn_restart_hooks(fn_image *img) {
  if (fn_hook_counter(img, "_ZN7Updater13updatePackageERKN10Filesystem4PathE", &packages))
    fn_log("update notice: Updater::updatePackage not found in this game version");
}

__attribute__((visibility("default"))) jint Java_com_factorynative_GameActivity_nativeUpdatePackages(JNIEnv *env, jclass cls) {
  return (jint)packages;
}

// The game's imports answered here before the allocator, the shim and bionic: sendto (the HUD's network ping,
// netping.c) and execv (the restart above).
void *fn_game_lookup(const char *name) {
  if (!strcmp(name, "sendto")) return (void *)fn_net_sendto;
  if (!strcmp(name, "execv")) return (void *)fn_game_execv;
  return NULL;
}

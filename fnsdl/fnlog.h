// Logging of libfnsdl. Compiled out: the calls and their arguments are not in the library. A build with
// FN_LOG=1 (tools/build-fnsdl.sh) brings the lines back, to stderr, "[tag] text".
#pragma once

#ifdef FN_LOG
void fn_sdl_log(const char *tag, const char *fmt, ...) __attribute__((format(printf, 2, 3)));
#define FNSDL_LOG(tag, ...) fn_sdl_log(tag, __VA_ARGS__)
#else
static inline __attribute__((format(printf, 2, 3))) void fn_sdl_log_off(const char *tag, const char *fmt, ...) {
  (void)tag;
  (void)fmt;
}
#define FNSDL_LOG(tag, ...) (0 ? fn_sdl_log_off(tag, __VA_ARGS__) : (void)0)
#endif

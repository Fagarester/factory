// Disk cache of converted texture uploads (BC -> ETC2 / RGBA8).
//
// Key: hash of the source BC bytes plus format and size; value: the converted
// bytes. Two files in the cache folder:
//   data.bin  - converted uploads, appended one after another;
//   index.bin - fixed records {key, offset, size}, appended after the data.
// A record whose data does not fit in data.bin (interrupted write) is ignored.
// The whole index is kept in memory; uploads happen on the GL thread.
#include "cache.h"

#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

#define CACHE_VERSION 1  // bump when the converter output changes

typedef struct {
  uint64_t key, offset;
  uint32_t size, version;
} record;

static int data_fd = -1, index_fd = -1;
static uint64_t data_end;
static record *table;  // open addressing, key 0 = empty
static size_t cap, count;
static uint64_t hits, misses, hit_bytes;

static void put_table(const record *r) {
  if ((count + 1) * 2 > cap) {
    size_t ncap = cap ? cap * 2 : 65536;
    record *n = calloc(ncap, sizeof *n);
    if (!n) return;
    for (size_t i = 0; i < cap; i++)
      if (table[i].key) {
        size_t j = table[i].key % ncap;
        while (n[j].key) j = (j + 1) % ncap;
        n[j] = table[i];
      }
    free(table);
    table = n;
    cap = ncap;
  }
  size_t j = r->key % cap;
  while (table[j].key && table[j].key != r->key) j = (j + 1) % cap;
  if (!table[j].key) count++;
  table[j] = *r;
}

static const record *find(uint64_t key) {
  if (!cap) return NULL;
  size_t j = key % cap;
  while (table[j].key) {
    if (table[j].key == key) return &table[j];
    j = (j + 1) % cap;
  }
  return NULL;
}

int tex_cache_open(const char *dir) {
  char path[4096];
  mkdir(dir, 0777);
  snprintf(path, sizeof path, "%s/data.bin", dir);
  data_fd = open(path, O_RDWR | O_CREAT | O_CLOEXEC, 0666);
  snprintf(path, sizeof path, "%s/index.bin", dir);
  index_fd = open(path, O_RDWR | O_CREAT | O_CLOEXEC, 0666);
  if (data_fd < 0 || index_fd < 0) return -errno;
  struct stat st;
  fstat(data_fd, &st);
  data_end = (uint64_t)st.st_size;
  fstat(index_fd, &st);
  size_t n = (size_t)st.st_size / sizeof(record);
  record *all = malloc(n * sizeof(record) + 1);
  ssize_t got = n ? pread(index_fd, all, n * sizeof(record), 0) : 0;
  size_t valid = 0;
  for (size_t i = 0; got > 0 && i < (size_t)got / sizeof(record); i++) {
    if (!all[i].key || all[i].version != CACHE_VERSION || all[i].offset + all[i].size > data_end) continue;
    put_table(&all[i]);
    valid++;
  }
  free(all);
  // Drop a torn record at the end of the index.
  if (ftruncate(index_fd, (off_t)(n * sizeof(record))) != 0) {}
  return (int)valid;
}

uint64_t tex_cache_key(const void *src, size_t size, uint32_t a, uint32_t b, uint32_t c) {
  // 64-bit multiply-xorshift over 8-byte words: fast, good enough as a key
  // together with size and formats.
  uint64_t h = 0x9E3779B97F4A7C15ull ^ size ^ ((uint64_t)a << 32) ^ ((uint64_t)b << 16) ^ c;
  const uint8_t *p = src;
  size_t i = 0;
  for (; i + 8 <= size; i += 8) {
    uint64_t w;
    memcpy(&w, p + i, 8);
    h = (h ^ w) * 0xBF58476D1CE4E5B9ull;
    h ^= h >> 31;
  }
  for (; i < size; i++) h = (h ^ p[i]) * 0x94D049BB133111EBull;
  h ^= h >> 29;
  return h ? h : 1;
}

void *tex_cache_get(uint64_t key, size_t size) {
  if (data_fd < 0) return NULL;
  const record *r = find(key);
  if (!r || r->size != size) { misses++; return NULL; }
  void *buf = malloc(size ? size : 1);
  size_t done = 0;
  while (done < size) {
    ssize_t n = pread(data_fd, (char *)buf + done, size - done, (off_t)(r->offset + done));
    if (n <= 0) { free(buf); misses++; return NULL; }
    done += (size_t)n;
  }
  hits++;
  hit_bytes += size;
  return buf;
}

void tex_cache_put(uint64_t key, const void *data, size_t size) {
  if (data_fd < 0 || find(key)) return;
  record r = {key, data_end, (uint32_t)size, CACHE_VERSION};
  size_t done = 0;
  while (done < size) {
    ssize_t n = pwrite(data_fd, (const char *)data + done, size - done, (off_t)(data_end + done));
    if (n <= 0) return;  // disk full etc.: the entry is simply not cached
    done += (size_t)n;
  }
  data_end += size;
  if (write(index_fd, &r, sizeof r) != (ssize_t)sizeof r) return;
  put_table(&r);
}

void tex_cache_stats(uint64_t *h, uint64_t *m, uint64_t *bytes, uint64_t *entries) {
  *h = hits;
  *m = misses;
  *bytes = hit_bytes;
  *entries = count;
}

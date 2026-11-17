// Disk cache of converted texture uploads (cache.c).
#pragma once
#include <stddef.h>
#include <stdint.h>

// Opens/creates the cache in dir; returns the number of cached entries or -errno.
int tex_cache_open(const char *dir);
uint64_t tex_cache_key(const void *src, size_t size, uint32_t a, uint32_t b, uint32_t c);
// malloc'ed converted bytes, or NULL (size must match the stored size)
void *tex_cache_get(uint64_t key, size_t size);
void tex_cache_put(uint64_t key, const void *data, size_t size);
void tex_cache_stats(uint64_t *hits, uint64_t *misses, uint64_t *bytes, uint64_t *entries);

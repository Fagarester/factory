// The HUD's multiplayer ping, the same for every kind of network game (LAN, factorio.com servers, our own tunnel).
// The game's sendto() comes here first: destinations that get game traffic (>= 15 packets a second; a connected
// game sends ~60) are its peers. Once a second each peer is pinged:
//  - with Factorio's own PingMessage (type 0 + a 16-bit id; a server answers PingReplyMessage, type 1 + the id —
//    what the server browser uses; checked against a 2.1.19 server, 2026-11-23). Our tunnel carries it end to end.
//  - a peer that never answers that (we host: clients do not answer PingMessage) gets ICMP echo instead
//    (unprivileged datagram ICMP sockets; for a client behind NAT that is its router). Not for loopback peers
//    (our tunnel's sockets on the host: the page adds the tunnel's own ping).
// GameActivity.nativeGamePing(int[5]): peers, peers with a ping, lowest ms, highest ms, peers on loopback without one.
#include <arpa/inet.h>
#include <jni.h>
#include <netinet/in.h>
#include <poll.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdint.h>
#include <string.h>
#include <sys/socket.h>
#include <time.h>
#include <unistd.h>

#include "fn.h"

#define PEERS 32
enum { TRY_GAME, USE_ICMP, NO_PING };

typedef struct {
  struct sockaddr_storage a;
  socklen_t len;
  uint32_t count;        // packets since the last round
  int active, quiet;     // active: game traffic in the last round; quiet: rounds without traffic
  int mode, misses;      // how it is pinged; rounds without an answer in a row
  double rtt, last_ok;   // smoothed ms (-1 none); time of the last answer
  uint16_t id;           // id of the ping on its way
  double sent;
  int answered;
} peer;

static peer peers[PEERS];
static pthread_mutex_t mu = PTHREAD_MUTEX_INITIALIZER;
static pthread_once_t once = PTHREAD_ONCE_INIT;
static atomic_int enabled = 0;  // the HUD switch turns it on (GameActivity)
static atomic_int out_peers, out_with, out_min = -1, out_max = -1, out_loop;

static double now_s(void) {
  struct timespec t;
  clock_gettime(CLOCK_MONOTONIC, &t);
  return t.tv_sec + t.tv_nsec / 1e9;
}

static int same(const struct sockaddr *x, const struct sockaddr_storage *y) {
  if (x->sa_family != y->ss_family) return 0;
  if (x->sa_family == AF_INET) {
    const struct sockaddr_in *a = (const void *)x, *b = (const void *)y;
    return a->sin_port == b->sin_port && a->sin_addr.s_addr == b->sin_addr.s_addr;
  }
  const struct sockaddr_in6 *a = (const void *)x, *b = (const void *)y;
  return a->sin6_port == b->sin6_port && !memcmp(&a->sin6_addr, &b->sin6_addr, 16);
}

static int loopback(const struct sockaddr_storage *s) {
  if (s->ss_family == AF_INET) return (ntohl(((const struct sockaddr_in *)s)->sin_addr.s_addr) >> 24) == 127;
  const struct in6_addr *a = &((const struct sockaddr_in6 *)s)->sin6_addr;
  return IN6_IS_ADDR_LOOPBACK(a) || (IN6_IS_ADDR_V4MAPPED(a) && a->s6_addr[12] == 127);
}

static void *pinger(void *arg);

static void start(void) {
  pthread_t t;
  if (!pthread_create(&t, NULL, pinger, NULL)) pthread_detach(t);
}

static void note(const struct sockaddr *to, socklen_t len) {
  pthread_once(&once, start);
  pthread_mutex_lock(&mu);
  int free_slot = -1, oldest = 0;
  for (int i = 0; i < PEERS; i++) {
    if (peers[i].len && same(to, &peers[i].a)) {
      peers[i].count++;
      pthread_mutex_unlock(&mu);
      return;
    }
    if (!peers[i].len && free_slot < 0) free_slot = i;
    if (peers[i].quiet > peers[oldest].quiet) oldest = i;
  }
  int i = free_slot >= 0 ? free_slot : oldest;  // full: the longest quiet one goes
  memset(&peers[i], 0, sizeof peers[i]);
  memcpy(&peers[i].a, to, len < sizeof peers[i].a ? len : sizeof peers[i].a);
  peers[i].len = len;
  peers[i].count = 1;
  peers[i].rtt = -1;
  pthread_mutex_unlock(&mu);
}

// The game's sendto (resolved through fn_lab_lookup).
ssize_t fn_net_sendto(int fd, const void *buf, size_t n, int flags, const struct sockaddr *to, socklen_t len) {
  if (to && atomic_load(&enabled) && (to->sa_family == AF_INET || to->sa_family == AF_INET6)) note(to, len);
  return sendto(fd, buf, n, flags, to, len);
}

static int udp4 = -1, udp6 = -1, icmp4 = -1, icmp6 = -1;

static int open_sock(int fam, int proto) {
  int s = socket(fam, SOCK_DGRAM | SOCK_NONBLOCK | SOCK_CLOEXEC, proto);
  return s;
}

static void send_ping(peer *p, uint16_t id, uint16_t seq) {
  p->id = id;
  p->sent = now_s();
  p->answered = 0;
  int v6 = p->a.ss_family == AF_INET6;
  if (p->mode == TRY_GAME) {
    unsigned char m[3] = {0x00, (unsigned char)id, (unsigned char)(id >> 8)};
    int s = v6 ? udp6 : udp4;
    if (s >= 0) sendto(s, m, 3, 0, (struct sockaddr *)&p->a, p->len);
  } else if (p->mode == USE_ICMP) {
    unsigned char m[16] = {v6 ? 128 : 8, 0, 0, 0, 0, 0, (unsigned char)(seq >> 8), (unsigned char)seq, 'f', 'n', 'a', 't', 'i', 'v', 'e', 0};
    // the kernel fills in the identifier and, for ICMP datagram sockets, the checksum
    struct sockaddr_storage d = p->a;
    if (v6) ((struct sockaddr_in6 *)&d)->sin6_port = 0;
    else ((struct sockaddr_in *)&d)->sin_port = 0;
    int s = v6 ? icmp6 : icmp4;
    if (s >= 0) sendto(s, m, sizeof m, 0, (struct sockaddr *)&d, p->len);
  }
}

static int same_host(const struct sockaddr_storage *x, const struct sockaddr_storage *y) {
  if (x->ss_family != y->ss_family) return 0;
  if (x->ss_family == AF_INET)
    return ((const struct sockaddr_in *)x)->sin_addr.s_addr == ((const struct sockaddr_in *)y)->sin_addr.s_addr;
  return !memcmp(&((const struct sockaddr_in6 *)x)->sin6_addr, &((const struct sockaddr_in6 *)y)->sin6_addr, 16);
}

static void answered(peer *p, double t) {
  if (p->answered) return;
  p->answered = 1;
  double ms = (t - p->sent) * 1000;
  p->rtt = p->rtt < 0 ? ms : p->rtt * 0.7 + ms * 0.3;
  p->last_ok = t;
  p->misses = 0;
}

static void *pinger(void *arg) {
  udp4 = open_sock(AF_INET, 0);
  udp6 = open_sock(AF_INET6, 0);
  icmp4 = open_sock(AF_INET, IPPROTO_ICMP);
  icmp6 = open_sock(AF_INET6, IPPROTO_ICMPV6);
  fn_log("net ping: game ping sockets %d/%d, ICMP %d/%d", udp4, udp6, icmp4, icmp6);
  uint16_t id = (uint16_t)(now_s() * 1000), seq = 0;
  for (;;) {
    double round = now_s();
    pthread_mutex_lock(&mu);
    peer *list[PEERS];
    int n = 0;
    for (int i = 0; i < PEERS; i++) {
      peer *p = &peers[i];
      if (!p->len) continue;
      p->active = p->count >= 15;
      p->quiet = p->active ? 0 : p->quiet + 1;
      p->count = 0;
      if (p->quiet > 30) {  // gone for half a minute: forgotten
        p->len = 0;
        continue;
      }
      if (!p->active || !atomic_load(&enabled)) continue;
      // no answer to the game's ping three times: ICMP (not for our tunnel's loopback sockets)
      if (p->mode == TRY_GAME && p->misses >= 3 && p->rtt < 0) p->mode = loopback(&p->a) ? NO_PING : USE_ICMP;
      list[n++] = p;
      send_ping(p, ++id, ++seq);
    }
    pthread_mutex_unlock(&mu);
    // answers for up to 900 ms
    while (n && now_s() - round < 0.9) {
      struct pollfd f[4] = {{udp4, POLLIN, 0}, {udp6, POLLIN, 0}, {icmp4, POLLIN, 0}, {icmp6, POLLIN, 0}};
      int wait = (int)((0.9 - (now_s() - round)) * 1000);
      if (poll(f, 4, wait > 0 ? wait : 0) <= 0) break;
      double t = now_s();
      for (int k = 0; k < 4; k++) {
        if (f[k].fd < 0 || !(f[k].revents & POLLIN)) continue;
        unsigned char b[1500];
        struct sockaddr_storage from;
        for (;;) {
          socklen_t fl = sizeof from;
          ssize_t r = recvfrom(f[k].fd, b, sizeof b, 0, (struct sockaddr *)&from, &fl);
          if (r < 0) break;
          pthread_mutex_lock(&mu);
          for (int i = 0; i < n; i++) {
            peer *p = list[i];
            if (k < 2) {  // PingReplyMessage: type 1 (low 5 bits), the id
              if (r >= 3 && (b[0] & 0x1f) == 1 && b[1] == (p->id & 0xff) && b[2] == (p->id >> 8) && same((struct sockaddr *)&from, &p->a))
                answered(p, t);
            } else if (p->mode == USE_ICMP && r >= 8 && (b[0] == 0 || b[0] == 129) && same_host(&from, &p->a)) {
              answered(p, t);
            }
          }
          pthread_mutex_unlock(&mu);
        }
      }
    }
    // the results
    pthread_mutex_lock(&mu);
    int peers_n = 0, with = 0, lo = -1, hi = -1, loop = 0;
    double t = now_s();
    for (int i = 0; i < n; i++) {
      peer *p = list[i];
      if (!p->answered) p->misses++;
      peers_n++;
      if (p->rtt >= 0 && t - p->last_ok < 3) {
        int ms = (int)(p->rtt + 0.5);
        with++;
        if (lo < 0 || ms < lo) lo = ms;
        if (ms > hi) hi = ms;
      } else if (loopback(&p->a)) loop++;
    }
    pthread_mutex_unlock(&mu);
    atomic_store(&out_peers, peers_n);
    atomic_store(&out_with, with);
    atomic_store(&out_min, lo);
    atomic_store(&out_max, hi);
    atomic_store(&out_loop, loop);
    static int rounds;
    if (peers_n && ++rounds % 10 == 0)  // the log: every 10 s while in a network game
      fn_log("net ping: %d peers, %d answer, %d..%d ms, %d on loopback without", peers_n, with, lo, hi, loop);
    double left = 1.0 - (now_s() - round);
    if (left > 0) usleep((useconds_t)(left * 1e6));
  }
  return NULL;
}

__attribute__((visibility("default"))) void Java_com_factorynative_GameActivity_nativeGamePing(JNIEnv *env, jclass cls,
                                                                                               jintArray out) {
  jint v[5] = {atomic_load(&out_peers), atomic_load(&out_with), atomic_load(&out_min), atomic_load(&out_max),
               atomic_load(&out_loop)};
  if ((*env)->GetArrayLength(env, out) >= 5) (*env)->SetIntArrayRegion(env, out, 0, 5, v);
}

// HUD ping switch (gear menu, "Производительность"): off = no pings, no bookkeeping.
__attribute__((visibility("default"))) void Java_com_factorynative_GameActivity_nativeSetGamePing(JNIEnv *env, jclass cls,
                                                                                                  jboolean on) {
  atomic_store(&enabled, on ? 1 : 0);
  if (!on) {
    atomic_store(&out_peers, 0);
    atomic_store(&out_with, 0);
  }
}

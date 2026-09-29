/*
 * AirPlay TV - AirPlay screen mirroring receiver for Android TV
 * Copyright (C) 2026 besliky
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

#include "netutil.h"

#include <arpa/inet.h>
#include <errno.h>
#include <fcntl.h>
#include <ifaddrs.h>
#include <net/if.h>
#include <poll.h>
#include <stdio.h>
#include <unistd.h>

#include "log.h"

int wakeup_init(wakeup_t *w) {
    int fds[2];
#if defined(__linux__)
    if (pipe2(fds, O_NONBLOCK | O_CLOEXEC) != 0) {
        return -1;
    }
#else
    if (pipe(fds) != 0) {
        return -1;
    }
    fcntl(fds[0], F_SETFL, O_NONBLOCK);
    fcntl(fds[1], F_SETFL, O_NONBLOCK);
#endif
    w->rd = fds[0];
    w->wr = fds[1];
    return 0;
}

void wakeup_signal(wakeup_t *w) {
    if (w->wr >= 0) {
        char c = 1;
        ssize_t r;
        do {
            r = write(w->wr, &c, 1);
        } while (r < 0 && errno == EINTR);
    }
}

void wakeup_drain(wakeup_t *w) {
    char buf[64];
    while (w->rd >= 0 && read(w->rd, buf, sizeof(buf)) > 0) {
    }
}

void wakeup_close(wakeup_t *w) {
    net_close(&w->rd);
    net_close(&w->wr);
}

void net_close(int *fd) {
    if (fd && *fd >= 0) {
        close(*fd);
        *fd = -1;
    }
}

int net_set_nonblocking(int fd, bool nonblocking) {
    int flags = fcntl(fd, F_GETFL, 0);
    if (flags < 0) {
        return -1;
    }
    flags = nonblocking ? (flags | O_NONBLOCK) : (flags & ~O_NONBLOCK);
    return fcntl(fd, F_SETFL, flags);
}

static int bind_any(int fd, int family, uint16_t *port) {
    struct sockaddr_storage ss;
    socklen_t len;
    memset(&ss, 0, sizeof(ss));
    if (family == AF_INET6) {
        struct sockaddr_in6 *sin6 = (struct sockaddr_in6 *) &ss;
        int v6only = 1;
        setsockopt(fd, IPPROTO_IPV6, IPV6_V6ONLY, &v6only, sizeof(v6only));
        sin6->sin6_family = AF_INET6;
        sin6->sin6_addr = in6addr_any;
        sin6->sin6_port = htons(*port);
        len = sizeof(*sin6);
    } else {
        struct sockaddr_in *sin = (struct sockaddr_in *) &ss;
        sin->sin_family = AF_INET;
        sin->sin_addr.s_addr = htonl(INADDR_ANY);
        sin->sin_port = htons(*port);
        len = sizeof(*sin);
    }
    if (bind(fd, (struct sockaddr *) &ss, len) != 0) {
        return -1;
    }
    len = sizeof(ss);
    if (getsockname(fd, (struct sockaddr *) &ss, &len) != 0) {
        return -1;
    }
    *port = ntohs(family == AF_INET6 ? ((struct sockaddr_in6 *) &ss)->sin6_port
                                     : ((struct sockaddr_in *) &ss)->sin_port);
    return 0;
}

int net_listen_tcp(int family, uint16_t *port, int backlog) {
    int fd = socket(family, SOCK_STREAM | SOCK_CLOEXEC, IPPROTO_TCP);
    if (fd < 0) {
        return -1;
    }
    int one = 1;
    setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &one, sizeof(one));
    if (bind_any(fd, family, port) != 0 || listen(fd, backlog) != 0) {
        close(fd);
        return -1;
    }
    return fd;
}

int net_bind_udp(int family, uint16_t *port) {
    int fd = socket(family, SOCK_DGRAM | SOCK_CLOEXEC, IPPROTO_UDP);
    if (fd < 0) {
        return -1;
    }
    int one = 1;
    setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &one, sizeof(one));
    if (bind_any(fd, family, port) != 0) {
        close(fd);
        return -1;
    }
    return fd;
}

int net_send_all(int fd, const void *data, size_t len, int timeout_ms) {
    const uint8_t *p = (const uint8_t *) data;
    while (len > 0) {
        ssize_t n = send(fd, p, len, MSG_NOSIGNAL);
        if (n > 0) {
            p += n;
            len -= (size_t) n;
            continue;
        }
        if (n < 0 && errno == EINTR) {
            continue;
        }
        if (n < 0 && (errno == EAGAIN || errno == EWOULDBLOCK)) {
            struct pollfd pfd = { .fd = fd, .events = POLLOUT };
            int r = poll(&pfd, 1, timeout_ms);
            if (r <= 0) {
                return -1;
            }
            continue;
        }
        return -1;
    }
    return 0;
}

int net_recv_exact(int fd, void *buf, size_t len, wakeup_t *wake) {
    uint8_t *p = (uint8_t *) buf;
    while (len > 0) {
        struct pollfd pfds[2] = {
            { .fd = fd, .events = POLLIN },
            { .fd = wake ? wake->rd : -1, .events = POLLIN },
        };
        int r = poll(pfds, wake ? 2 : 1, -1);
        if (r < 0) {
            if (errno == EINTR) {
                continue;
            }
            return -1;
        }
        if (wake && (pfds[1].revents & POLLIN)) {
            return -1;
        }
        if (pfds[0].revents & (POLLIN | POLLHUP | POLLERR)) {
            ssize_t n = recv(fd, p, len, 0);
            if (n == 0) {
                return 0;
            }
            if (n < 0) {
                if (errno == EINTR || errno == EAGAIN || errno == EWOULDBLOCK) {
                    continue;
                }
                return -1;
            }
            p += n;
            len -= (size_t) n;
        }
    }
    return 1;
}

void net_normalize_addr(struct sockaddr_storage *ss, socklen_t *len) {
    if (ss->ss_family != AF_INET6) {
        return;
    }
    struct sockaddr_in6 *sin6 = (struct sockaddr_in6 *) ss;
    if (!IN6_IS_ADDR_V4MAPPED(&sin6->sin6_addr)) {
        return;
    }
    struct sockaddr_in sin;
    memset(&sin, 0, sizeof(sin));
    sin.sin_family = AF_INET;
    sin.sin_port = sin6->sin6_port;
    memcpy(&sin.sin_addr, &sin6->sin6_addr.s6_addr[12], 4);
    memset(ss, 0, sizeof(*ss));
    memcpy(ss, &sin, sizeof(sin));
    if (len) {
        *len = sizeof(sin);
    }
}

void net_addr_to_string(const struct sockaddr_storage *ss, char *out, size_t cap) {
    if (cap == 0) {
        return;
    }
    out[0] = '\0';
    if (ss->ss_family == AF_INET) {
        inet_ntop(AF_INET, &((const struct sockaddr_in *) ss)->sin_addr, out, (socklen_t) cap);
    } else if (ss->ss_family == AF_INET6) {
        const struct sockaddr_in6 *sin6 = (const struct sockaddr_in6 *) ss;
        char tmp[INET6_ADDRSTRLEN];
        if (inet_ntop(AF_INET6, &sin6->sin6_addr, tmp, sizeof(tmp))) {
            if (sin6->sin6_scope_id) {
                snprintf(out, cap, "%s%%%u", tmp, (unsigned) sin6->sin6_scope_id);
            } else {
                snprintf(out, cap, "%s", tmp);
            }
        }
    }
}

static bool masked_equal(const uint8_t *a, const uint8_t *b, const uint8_t *mask, size_t len) {
    for (size_t i = 0; i < len; i++) {
        if ((a[i] & mask[i]) != (b[i] & mask[i])) {
            return false;
        }
    }
    return true;
}

static bool is_private_v4(const uint8_t *a) {
    return a[0] == 10 || (a[0] == 172 && (a[1] & 0xf0) == 16) || (a[0] == 192 && a[1] == 168) ||
           (a[0] == 169 && a[1] == 254) || a[0] == 127;
}

bool net_is_lan_peer(const struct sockaddr_storage *peer_in) {
    struct sockaddr_storage peer = *peer_in;
    net_normalize_addr(&peer, NULL);

    if (peer.ss_family == AF_INET) {
        const uint8_t *a = (const uint8_t *) &((struct sockaddr_in *) &peer)->sin_addr;
        if (a[0] == 127) {
            return true;
        }
    } else if (peer.ss_family == AF_INET6) {
        const struct in6_addr *a6 = &((struct sockaddr_in6 *) &peer)->sin6_addr;
        if (IN6_IS_ADDR_LINKLOCAL(a6) || IN6_IS_ADDR_LOOPBACK(a6)) {
            return true;
        }
    } else {
        return false;
    }

    struct ifaddrs *ifs = NULL;
    if (getifaddrs(&ifs) != 0 || !ifs) {
        /* Fall back to private address ranges when interfaces cannot be listed. */
        if (peer.ss_family == AF_INET) {
            return is_private_v4((const uint8_t *) &((struct sockaddr_in *) &peer)->sin_addr);
        }
        const uint8_t *b = ((struct sockaddr_in6 *) &peer)->sin6_addr.s6_addr;
        return (b[0] & 0xfe) == 0xfc; /* unique local addresses */
    }

    bool match = false;
    for (struct ifaddrs *it = ifs; it && !match; it = it->ifa_next) {
        if (!it->ifa_addr || !it->ifa_netmask || !(it->ifa_flags & IFF_UP)) {
            continue;
        }
        if (it->ifa_addr->sa_family != peer.ss_family) {
            continue;
        }
        if (peer.ss_family == AF_INET) {
            match = masked_equal((const uint8_t *) &((struct sockaddr_in *) it->ifa_addr)->sin_addr,
                                 (const uint8_t *) &((struct sockaddr_in *) &peer)->sin_addr,
                                 (const uint8_t *) &((struct sockaddr_in *) it->ifa_netmask)->sin_addr, 4);
        } else {
            match = masked_equal(((struct sockaddr_in6 *) it->ifa_addr)->sin6_addr.s6_addr,
                                 ((struct sockaddr_in6 *) &peer)->sin6_addr.s6_addr,
                                 ((struct sockaddr_in6 *) it->ifa_netmask)->sin6_addr.s6_addr, 16);
        }
    }
    freeifaddrs(ifs);
    return match;
}

bool net_same_host(const struct sockaddr_storage *a_in, const struct sockaddr_storage *b_in) {
    struct sockaddr_storage a = *a_in;
    struct sockaddr_storage b = *b_in;
    net_normalize_addr(&a, NULL);
    net_normalize_addr(&b, NULL);
    if (a.ss_family != b.ss_family) {
        return false;
    }
    if (a.ss_family == AF_INET) {
        return memcmp(&((struct sockaddr_in *) &a)->sin_addr, &((struct sockaddr_in *) &b)->sin_addr, 4) == 0;
    }
    if (a.ss_family == AF_INET6) {
        return memcmp(&((struct sockaddr_in6 *) &a)->sin6_addr, &((struct sockaddr_in6 *) &b)->sin6_addr, 16) == 0;
    }
    return false;
}

void net_set_port(struct sockaddr_storage *ss, uint16_t port) {
    if (ss->ss_family == AF_INET) {
        ((struct sockaddr_in *) ss)->sin_port = htons(port);
    } else if (ss->ss_family == AF_INET6) {
        ((struct sockaddr_in6 *) ss)->sin6_port = htons(port);
    }
}

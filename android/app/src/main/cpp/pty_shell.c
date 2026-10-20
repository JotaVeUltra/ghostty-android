/*
 * Tiny PTY relay for Ghostty Android.
 *
 * The JVM gives us pipes, not a terminal. Without a PTY the shell has no
 * line discipline: no echo, no backspace, no Ctrl+C, no job control.
 * This helper allocates a PTY, runs an interactive shell on the slave side,
 * and relays bytes between stdin/stdout (the pipes) and the PTY master.
 *
 * Build:
 *   armv7a-linux-androideabi24-clang -O2 -fPIE -pie \
 *       -o libpty_shell.so pty_shell.c
 */
#include <pty.h>
#include <poll.h>
#include <stdlib.h>
#include <termios.h>
#include <unistd.h>

static int env_int(const char *name, int fallback) {
    const char *value = getenv(name);
    if (value == NULL) return fallback;
    int parsed = atoi(value);
    return parsed > 0 ? parsed : fallback;
}

int main(void) {
    struct winsize ws;
    ws.ws_col = (unsigned short)env_int("COLUMNS", 80);
    ws.ws_row = (unsigned short)env_int("LINES", 24);
    ws.ws_xpixel = 0;
    ws.ws_ypixel = 0;

    int master = -1;
    pid_t pid = forkpty(&master, NULL, NULL, &ws);
    if (pid < 0) return 1;

    if (pid == 0) {
        setenv("TERM", "xterm-256color", 1);
        execl("/system/bin/sh", "sh", "-i", (char *)NULL);
        _exit(127);
    }

    struct pollfd fds[2];
    fds[0].fd = STDIN_FILENO;
    fds[0].events = POLLIN;
    fds[1].fd = master;
    fds[1].events = POLLIN;

    char buf[4096];
    for (;;) {
        int ready = poll(fds, 2, -1);
        if (ready < 0) break;

        if (fds[0].revents & POLLIN) {
            ssize_t n = read(STDIN_FILENO, buf, sizeof(buf));
            if (n <= 0) break;
            ssize_t off = 0;
            while (off < n) {
                ssize_t written = write(master, buf + off, (size_t)(n - off));
                if (written <= 0) goto done;
                off += written;
            }
        }

        if (fds[1].revents & (POLLIN | POLLHUP | POLLERR)) {
            ssize_t n = read(master, buf, sizeof(buf));
            if (n <= 0) break;
            ssize_t off = 0;
            while (off < n) {
                ssize_t written = write(STDOUT_FILENO, buf + off, (size_t)(n - off));
                if (written <= 0) goto done;
                off += written;
            }
        }
    }

done:
    return 0;
}

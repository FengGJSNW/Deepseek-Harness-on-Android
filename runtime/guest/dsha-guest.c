#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <pty.h>
#include <sched.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/mount.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <termios.h>
#include <unistd.h>

/* Guest-only process broker. All paths/syscalls refer to the emulated Linux machine. */
#define JOBS 16
#define LINE 65536
struct job { char id[48]; pid_t pid; int out, err, input, pty, status, exited; };
static struct job jobs[JOBS];
static int control;
static const char alphabet[] = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
static char *decode(const char *s, size_t *length) {
    size_t n = strlen(s), p = 0; unsigned value = 0; int bits = 0;
    if (n > LINE) return NULL;
    char *out = calloc(1, n + 1); if (!out) return NULL;
    for (size_t i = 0; i < n; i++) {
        if (s[i] == '=') break;
        const char *at = strchr(alphabet, s[i]);
        if (!at || !s[i]) { free(out); return NULL; }
        value = (value << 6) | (unsigned)(at - alphabet); bits += 6;
        if (bits >= 8) { bits -= 8; out[p++] = (char)(value >> bits); }
    }
    *length = p; return out;
}
static void bytes(const char *kind, const char *id, const unsigned char *data, size_t n) {
    char out[12000]; size_t p = (size_t)snprintf(out, sizeof(out), "%s %s ", kind, id);
    for (size_t i = 0; i < n; i += 3) {
        unsigned v = (unsigned)data[i] << 16;
        if (i + 1 < n) v |= (unsigned)data[i + 1] << 8;
        if (i + 2 < n) v |= data[i + 2];
        out[p++] = alphabet[v >> 18]; out[p++] = alphabet[(v >> 12) & 63];
        out[p++] = i + 1 < n ? alphabet[(v >> 6) & 63] : '=';
        out[p++] = i + 2 < n ? alphabet[v & 63] : '=';
    }
    out[p++] = '\n';
    size_t sent = 0;
    while (sent < p) { ssize_t k = write(control, out + sent, p - sent); if (k <= 0) { if (errno == EINTR) continue; _exit(2); } sent += k; }
}
static void error(const char *id, const char *message) { bytes("F", id, (const unsigned char *)message, strlen(message)); }
static int valid_id(const char *s) {
    if (strlen(s) != 36) return 0;
    for (; *s; s++) if (!((*s >= '0' && *s <= '9') || (*s >= 'a' && *s <= 'f') || *s == '-')) return 0;
    return 1;
}
static void nonblock(int fd) { if (fd >= 0) fcntl(fd, F_SETFL, fcntl(fd, F_GETFL) | O_NONBLOCK); }
static void child_environment(const char *root, const char *tree, const char *cwd) {
    if (strcmp(tree, "-")) {
        if (unshare(CLONE_NEWNS) || mount(NULL, "/", NULL, MS_REC | MS_PRIVATE, NULL)) { perror("worktree mount namespace"); _exit(126); }
        char target[1024]; snprintf(target, sizeof(target), "%s/workspace", strcmp(root, "-") ? root : "");
        if (mount(tree, target, NULL, MS_BIND | MS_REC, NULL)) { perror("worktree bind"); _exit(126); }
    }
    if (strcmp(root, "-") && (chdir(root) || chroot("."))) { perror("runtime root"); _exit(126); }
    if (chdir(cwd)) { perror("working directory"); _exit(126); }
    setenv("TERM", "xterm-256color", 1); setenv("LANG", "C.UTF-8", 1); setenv("LC_ALL", "C.UTF-8", 1);
    setenv("HOME", "/root", 1); setenv("PATH", "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin", 1);
}
static void start(const char *id, int pty, char *root, char *tree, char *cwd, char *command, int rows, int cols) {
    struct job *j = NULL;
    for (int i = 0; i < JOBS; i++) { if (jobs[i].pid && !strcmp(jobs[i].id, id)) { error(id, "Duplicate request"); return; } if (!jobs[i].pid) j = &jobs[i]; }
    if (!j) { error(id, "Guest process limit reached"); return; }
    int output[2] = {-1,-1}, err[2] = {-1,-1}, input[2] = {-1,-1}, master = -1;
    if (!pty && (pipe2(output, O_CLOEXEC) || pipe2(err, O_CLOEXEC) || pipe2(input, O_CLOEXEC))) { error(id, "Guest pipe failed"); return; }
    struct winsize size = { .ws_row = (unsigned short)rows, .ws_col = (unsigned short)cols };
    pid_t pid = pty ? forkpty(&master, NULL, NULL, &size) : fork();
    if (pid < 0) { error(id, "Guest fork failed"); return; }
    if (!pid) {
        if (!pty) { setsid(); dup2(input[0], 0); dup2(output[1], 1); dup2(err[1], 2); }
        for (int fd = 3; fd < 1024; fd++) close(fd);
        child_environment(root, tree, cwd);
        if (pty) { struct termios t; if (!tcgetattr(0, &t)) { t.c_iflag |= IUTF8; tcsetattr(0, TCSANOW, &t); } }
        execl("/bin/sh", "sh", pty ? "-i" : "-c", pty ? NULL : command, (char *)NULL);
        perror("exec shell"); _exit(127);
    }
    memset(j, 0, sizeof(*j)); strcpy(j->id, id); j->pid = pid; j->pty = pty;
    if (pty) { j->out = j->input = master; j->err = -1; }
    else { close(output[1]); close(err[1]); close(input[0]); j->out = output[0]; j->err = err[0]; j->input = input[1]; }
    nonblock(j->out); nonblock(j->err); nonblock(j->input);
    bytes("A", id, (const unsigned char *)"started", 7);
}
static void line(char *text) {
    char *save, *kind = strtok_r(text, " ", &save), *id = strtok_r(NULL, " ", &save);
    if (!kind || !id || !valid_id(id)) return;
    struct job *j = NULL;
    for (int i = 0; i < JOBS; i++) if (jobs[i].pid && !strcmp(jobs[i].id, id)) j = &jobs[i];
    if (!strcmp(kind, "E") || !strcmp(kind, "P")) {
        char *root64 = strtok_r(NULL," ",&save), *tree64 = strtok_r(NULL," ",&save), *cwd64 = strtok_r(NULL," ",&save), *cmd64 = strtok_r(NULL," ",&save), *r = strtok_r(NULL," ",&save), *c = strtok_r(NULL," ",&save);
        if (!root64 || !tree64 || !cwd64 || !cmd64 || !r || !c) { error(id, "Invalid start frame"); return; }
        size_t n; char *root=decode(root64,&n), *tree=decode(tree64,&n), *cwd=decode(cwd64,&n), *cmd=decode(cmd64,&n);
        if (!root || !tree || !cwd || !cmd) error(id,"Invalid base64");
        else start(id, !strcmp(kind,"P"), root, tree, cwd, cmd, atoi(r), atoi(c));
        free(root); free(tree); free(cwd); free(cmd);
    } else if (j && !strcmp(kind,"I")) {
        char *data = strtok_r(NULL," ",&save); if (!data) return;
        size_t n; char *bytesIn=decode(data,&n); if (bytesIn) { size_t sent=0; while(sent<n) { ssize_t k=write(j->input,bytesIn+sent,n-sent); if(k>0) sent+=k; else if(errno==EINTR) continue; else break; } free(bytesIn); }
    } else if (j && !strcmp(kind,"R")) {
        char *r=strtok_r(NULL," ",&save), *c=strtok_r(NULL," ",&save); if(!r||!c) return;
        struct winsize size={.ws_row=(unsigned short)atoi(r),.ws_col=(unsigned short)atoi(c)};
        ioctl(j->out,TIOCSWINSZ,&size); kill(-j->pid,SIGWINCH);
    } else if (j && !strcmp(kind,"C")) { if(j->pty) write(j->input,"\003",1); else kill(-j->pid,SIGINT); }
    else if (j && !strcmp(kind,"K")) { kill(-j->pid,SIGKILL); }
}
static void drain(struct job *j, int *fd, const char *kind) {
    if (*fd < 0) return;
    unsigned char buffer[4096];
    while (1) {
        ssize_t n = read(*fd, buffer, sizeof(buffer));
        if (n > 0) bytes(kind,j->id,buffer,(size_t)n);
        else { if (!n || (errno != EAGAIN && errno != EWOULDBLOCK && errno != EINTR)) { int old=*fd; close(old); *fd=-1; if(j->input==old) j->input=-1; } break; }
    }
}
int main(int argc, char **argv) {
    if (argc != 2) return 1;
    signal(SIGPIPE, SIG_IGN);
    control=open(argv[1],O_RDWR|O_NOCTTY);
    if(control<0) return 2;
    struct termios t;
    if(!tcgetattr(control,&t)) { cfmakeraw(&t); t.c_cflag |= CLOCAL|CREAD; tcsetattr(control,TCSANOW,&t); }
    nonblock(control);
    // Keep output blocking so control frames cannot be silently truncated.
    fcntl(control,F_SETFL,fcntl(control,F_GETFL)&~O_NONBLOCK);
    write(control,"S DSHA_GUEST_080\n",17);
    char input[LINE]; size_t length=0;
    while(1) {
        struct pollfd descriptors[JOBS*2+1]; int count=1;
        descriptors[0]=(struct pollfd){.fd=control,.events=POLLIN};
        for(int i=0;i<JOBS;i++) if(jobs[i].pid) {
            if(jobs[i].out>=0) descriptors[count++]=(struct pollfd){.fd=jobs[i].out,.events=POLLIN};
            if(jobs[i].err>=0) descriptors[count++]=(struct pollfd){.fd=jobs[i].err,.events=POLLIN};
        }
        poll(descriptors,count,50);
        if(descriptors[0].revents&POLLIN) {
            char b[4096]; ssize_t n=read(control,b,sizeof(b));
            if(n<=0) return 0;
            for(ssize_t k=0;k<n;k++) {
                if(b[k]=='\n') { input[length]=0; line(input); length=0; }
                else if(length<LINE-1) input[length++]=b[k];
                else { length=0; error("00000000-0000-0000-0000-000000000000","Control frame too long"); }
            }
        }
        for(int i=0;i<JOBS;i++) if(jobs[i].pid) {
            struct job *j=&jobs[i]; drain(j,&j->out,j->pty?"T":"O"); drain(j,&j->err,"E");
            if(!j->exited && waitpid(j->pid,&j->status,WNOHANG)==j->pid) j->exited=1;
            if(j->exited && j->out<0 && j->err<0) {
                char code[32]; int result=WIFEXITED(j->status)?WEXITSTATUS(j->status):128+WTERMSIG(j->status);
                snprintf(code,sizeof(code),"%d",result); bytes("D",j->id,(const unsigned char *)code,strlen(code));
                if(j->input>=0) close(j->input); j->pid=0;
            }
        }
    }
}

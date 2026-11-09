#include <dirent.h>
#include <fcntl.h>
#include <grp.h>
#include <jni.h>
#include <linux/audit.h>
#include <linux/capability.h>
#include <linux/filter.h>
#include <linux/mount.h>
#include <linux/seccomp.h>
#include <linux/securebits.h>
#include <sched.h>
#include <signal.h>
#include <sys/mount.h>
#include <sys/prctl.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/sysmacros.h>
#include <sys/wait.h>
#include <unistd.h>

#include <cerrno>
#include <cstddef>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

static void fail(const char *stage) {
    dprintf(2, "[guard] %s: %s; no unrestricted fallback\n", stage, strerror(errno));
    _exit(126);
}

static bool safe_tree(const char *path, int &budget) {
    if (--budget < 0) return false;
    struct stat st {};
    if (lstat(path, &st) || S_ISLNK(st.st_mode) || (!S_ISDIR(st.st_mode) && st.st_nlink != 1))
        return false;
    if (!S_ISDIR(st.st_mode)) return S_ISREG(st.st_mode);
    DIR *dir = opendir(path);
    if (!dir) return false;
    bool ok = true;
    while (auto *ent = readdir(dir)) {
        if (!strcmp(ent->d_name, ".") || !strcmp(ent->d_name, "..")) continue;
        char child[4096];
        if (snprintf(child, sizeof(child), "%s/%s", path, ent->d_name) >=
                static_cast<int>(sizeof(child)) ||
            !safe_tree(child, budget)) {
            ok = false;
            break;
        }
    }
    closedir(dir);
    return ok;
}

static void guard_filesystem(const char *cwd) {
    if (getuid() != 0) {
        errno = EPERM;
        fail("requires UID 0");
    }
    char resolved[4096];
    constexpr char prefix[] = "/data/local/tmp/dev.deepseek.mobile/root/session_";
    if (!realpath(cwd, resolved) || strcmp(resolved, cwd) ||
        strncmp(cwd, prefix, sizeof(prefix) - 1)) {
        errno = EINVAL;
        fail("workspace path");
    }
    int budget = 4096;
    if (!safe_tree(cwd, budget)) {
        errno = EPERM;
        fail("workspace contains links, special files or too many entries");
    }
    if (unshare(CLONE_NEWNS | CLONE_NEWPID | CLONE_NEWIPC | CLONE_NEWNET | CLONE_NEWUTS))
        fail("unshare mount, PID, IPC and network namespaces");
    if (mount(nullptr, "/", nullptr, MS_REC | MS_PRIVATE, nullptr))
        fail("private mount propagation");
    // PID 1 in the new namespace owns the private /proc. The outer supervisor only waits.
    pid_t inner = fork();
    if (inner < 0) fail("PID namespace fork");
    if (inner > 0) {
        int status = 0;
        while (waitpid(inner, &status, 0) < 0 && errno == EINTR) {
        }
        _exit(WIFEXITED(status) ? WEXITSTATUS(status) : 128 + WTERMSIG(status));
    }
    if (mount("proc", "/proc", "proc", MS_RDONLY | MS_NOSUID | MS_NODEV | MS_NOEXEC, nullptr))
        fail("private procfs");
    // Device files and Binder would bypass a read-only VFS. Expose only harmless fresh devices.
    if (mount("tmpfs", "/dev", "tmpfs", MS_NOSUID | MS_NOEXEC, "size=64k,mode=755"))
        fail("private devfs");
    if (mknod("/dev/null", S_IFCHR | 0666, makedev(1, 3)) ||
        mknod("/dev/zero", S_IFCHR | 0666, makedev(1, 5)) ||
        mknod("/dev/urandom", S_IFCHR | 0666, makedev(1, 9)))
        fail("safe devices");
    if (mount(cwd, cwd, nullptr, MS_BIND, nullptr)) fail("bind workspace");
    struct mount_attr ro {};
    ro.attr_set = MOUNT_ATTR_RDONLY | MOUNT_ATTR_NOSUID;
    if (syscall(__NR_mount_setattr, AT_FDCWD, "/", AT_RECURSIVE, &ro, sizeof(ro)))
        fail("recursive read-only mounts (kernel support required)");
    struct mount_attr rw {};
    rw.attr_clr = MOUNT_ATTR_RDONLY;
    rw.attr_set = MOUNT_ATTR_NOSUID | MOUNT_ATTR_NODEV;
    if (syscall(__NR_mount_setattr, AT_FDCWD, cwd, 0, &rw, sizeof(rw))) fail("writable workspace");
    // Lock root semantics before exec. Retain read/search only; no DAC write override or sysadmin.
    if (prctl(PR_SET_SECUREBITS, SECBIT_NOROOT | SECBIT_NOROOT_LOCKED | SECBIT_NO_SETUID_FIXUP |
                                     SECBIT_NO_SETUID_FIXUP_LOCKED))
        fail("securebits");
    for (int cap = 0; cap <= CAP_LAST_CAP; ++cap)
        if (cap != CAP_DAC_READ_SEARCH && prctl(PR_CAPBSET_DROP, cap, 0, 0, 0) && errno != EINVAL)
            fail("capability bounding set");
    __user_cap_header_struct hdr{_LINUX_CAPABILITY_VERSION_3, 0};
    __user_cap_data_struct caps[2]{};
    caps[0].effective = caps[0].permitted = caps[0].inheritable = (1u << CAP_DAC_READ_SEARCH);
    if (syscall(__NR_capset, &hdr, caps)) fail("read-only capability set");
    if (prctl(PR_CAP_AMBIENT, PR_CAP_AMBIENT_RAISE, CAP_DAC_READ_SEARCH, 0, 0) ||
        prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0))
        fail("no new privileges");
}

static void guard_syscalls() {
    std::vector<sock_filter> filter;
    filter.push_back(BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(seccomp_data, arch)));
#if defined(__aarch64__)
    constexpr unsigned arch = AUDIT_ARCH_AARCH64;
#elif defined(__x86_64__)
    constexpr unsigned arch = AUDIT_ARCH_X86_64;
#elif defined(__arm__)
    constexpr unsigned arch = AUDIT_ARCH_ARM;
#else
#error Unsupported syscall architecture
#endif
    filter.push_back(BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, arch, 1, 0));
    filter.push_back(BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_KILL_PROCESS));
    filter.push_back(BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(seccomp_data, nr)));
#if defined(__x86_64__)
    // x32 shares AUDIT_ARCH_X86_64 but changes syscall numbers. Never bypass the deny list.
    filter.push_back(BPF_JUMP(BPF_JMP | BPF_JSET | BPF_K, 0x40000000, 0, 1));
    filter.push_back(BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_KILL_PROCESS));
#endif
#define BLOCK(n)                                                           \
    filter.push_back(BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_##n, 0, 1)); \
    filter.push_back(BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ERRNO | EPERM))
    BLOCK(mount);
    BLOCK(umount2);
    BLOCK(mount_setattr);
    BLOCK(open_tree);
    BLOCK(move_mount);
    BLOCK(fsopen);
    BLOCK(fsconfig);
    BLOCK(fsmount);
    BLOCK(fspick);
    BLOCK(pivot_root);
    BLOCK(chroot);
    BLOCK(setns);
    BLOCK(unshare);
    BLOCK(ptrace);
    BLOCK(process_vm_writev);
    BLOCK(bpf);
    BLOCK(reboot);
    BLOCK(swapon);
    BLOCK(swapoff);
    BLOCK(init_module);
    BLOCK(finit_module);
    BLOCK(delete_module);
    BLOCK(open_by_handle_at);
    BLOCK(name_to_handle_at);
    BLOCK(linkat);
    BLOCK(symlinkat);
    BLOCK(mknodat);
    BLOCK(socket);
    BLOCK(connect);
    BLOCK(ioctl);
    BLOCK(prctl);
    BLOCK(capset);
    BLOCK(setuid);
    BLOCK(setgid);
    BLOCK(setresuid);
    BLOCK(setresgid);
    BLOCK(setgroups);
    BLOCK(setsid);
    BLOCK(setpgid);
    BLOCK(io_uring_setup);
    BLOCK(io_uring_enter);
    BLOCK(io_uring_register);
    BLOCK(keyctl);
    BLOCK(add_key);
    BLOCK(request_key);
    BLOCK(perf_event_open);
    BLOCK(userfaultfd);
    BLOCK(pidfd_getfd);
    BLOCK(fanotify_init);
#ifdef __NR_link
    BLOCK(link);
#endif
#ifdef __NR_symlink
    BLOCK(symlink);
#endif
#ifdef __NR_mknod
    BLOCK(mknod);
#endif
#ifdef __NR_clone3
    filter.push_back(BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_clone3, 0, 1));
    filter.push_back(BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ERRNO | ENOSYS));
#endif
    filter.push_back(BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_clone, 0, 4));
    filter.push_back(BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(seccomp_data, args[0])));
    filter.push_back(BPF_JUMP(BPF_JMP | BPF_JSET | BPF_K,
                              CLONE_NEWNS | CLONE_NEWUSER | CLONE_NEWPID | CLONE_NEWNET |
                                  CLONE_NEWIPC | CLONE_NEWUTS | CLONE_NEWCGROUP,
                              0, 1));
    filter.push_back(BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ERRNO | EPERM));
    filter.push_back(BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW));
    filter.push_back(BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW));
#undef BLOCK
    sock_fprog prog{static_cast<unsigned short>(filter.size()), filter.data()};
    if (prctl(PR_SET_SECCOMP, SECCOMP_MODE_FILTER, &prog)) fail("seccomp");
}

static std::string utf8(JNIEnv *env, jstring value) {
    const jchar *chars = env->GetStringChars(value, nullptr);
    jsize size = env->GetStringLength(value);
    std::string result;
    for (jsize i = 0; i < size; ++i) {
        unsigned point = chars[i];
        if (point >= 0xd800 && point <= 0xdbff && i + 1 < size && chars[i + 1] >= 0xdc00 &&
            chars[i + 1] <= 0xdfff)
            point = 0x10000 + ((point - 0xd800) << 10) + (chars[++i] - 0xdc00);
        else if (point >= 0xd800 && point <= 0xdfff)
            point = 0xfffd;
        if (point < 0x80)
            result.push_back(static_cast<char>(point));
        else {
            if (point < 0x800)
                result.push_back(static_cast<char>(0xc0 | (point >> 6)));
            else {
                if (point < 0x10000)
                    result.push_back(static_cast<char>(0xe0 | (point >> 12)));
                else {
                    result.push_back(static_cast<char>(0xf0 | (point >> 18)));
                    result.push_back(static_cast<char>(0x80 | ((point >> 12) & 0x3f)));
                }
                result.push_back(static_cast<char>(0x80 | ((point >> 6) & 0x3f)));
            }
            result.push_back(static_cast<char>(0x80 | (point & 0x3f)));
        }
    }
    env->ReleaseStringChars(value, chars);
    return result;
}

extern "C" JNIEXPORT jintArray JNICALL Java_dev_deepseek_mobile_terminal_NativeProcess_spawn(
    JNIEnv *env, jobject, jstring cmd, jstring dir, jboolean guarded, jstring work) {
    std::string command = utf8(env, cmd);
    std::string cwd = utf8(env, dir);
    std::string workspace = utf8(env, work);
    int out[2], err[2];
    if (pipe2(out, O_CLOEXEC)) return nullptr;
    if (pipe2(err, O_CLOEXEC)) {
        close(out[0]);
        close(out[1]);
        return nullptr;
    }
    pid_t pid = fork();
    if (pid == 0) {
        dup2(out[1], 1);
        dup2(err[1], 2);
        if (setsid() < 0) fail("setsid");
        int input = open("/dev/null", O_RDONLY);
        if (input < 0) fail("stdin");
        dup2(input, 0);
        // Binder, sockets and inherited writable descriptors must not enter the command.
        long maxfd = sysconf(_SC_OPEN_MAX);
        if (maxfd < 0) maxfd = 65536;
        for (int fd = 3; fd < maxfd; ++fd) close(fd);
        if (guarded) guard_filesystem(workspace.c_str());
        if (chdir(cwd.c_str())) fail("cwd");
        if (guarded) guard_syscalls();
        char path[] = "PATH=/system/bin:/system/xbin";
        char lang[] = "LANG=C.UTF-8";
        std::string home = "HOME=" + workspace;
        std::string temp = "TMPDIR=" + workspace;
        char *variables[] = {path, lang, home.data(), temp.data(), nullptr};
        char *argv[] = {const_cast<char *>("sh"), const_cast<char *>("-c"), command.data(),
                        nullptr};
        execve("/system/bin/sh", argv, variables);
        fail("execve");
    }
    close(out[1]);
    close(err[1]);
    if (pid < 0) {
        close(out[0]);
        close(err[0]);
        return nullptr;
    }
    jint values[] = {pid, out[0], err[0]};
    jintArray array = env->NewIntArray(3);
    env->SetIntArrayRegion(array, 0, 3, values);
    return array;
}
// Runs only in a new single-threaded child. Never alter the Shizuku server or its JVM threads.
static void become_shell() {
    if (getuid() != 0) { errno = EPERM; fail("Shell downgrade requires Root broker"); }
    int context = open("/proc/self/attr/current", O_WRONLY | O_CLOEXEC);
    if (context < 0) fail("open own SELinux context");
    // AOSP adbd supplementary groups; no Root group or inherited manager groups.
    const gid_t groups[] = {1011, 1007, 1004, 3003, 3002, 3001, 1028,
                            1015, 3006, 3009, 3011, 1078, 1079, 3012};
    if (setgroups(sizeof(groups) / sizeof(groups[0]), groups)) fail("Shell groups");
    for (int cap = 0; cap <= CAP_LAST_CAP; ++cap)
        if (prctl(PR_CAPBSET_DROP, cap, 0, 0, 0) && errno != EINVAL)
            fail("Shell capability bounding set");
    if (setresgid(2000, 2000, 2000) || setresuid(2000, 2000, 2000))
        fail("Shell real/effective/saved IDs");
    const char label[] = "u:r:shell:s0";
    if (write(context, label, sizeof(label)) != static_cast<ssize_t>(sizeof(label)))
        fail("Shell SELinux transition");
    close(context);
    __user_cap_header_struct hdr{_LINUX_CAPABILITY_VERSION_3, 0};
    __user_cap_data_struct caps[2]{};
    if (syscall(__NR_capset, &hdr, caps) || prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0))
        fail("Shell capabilities/no-new-privileges");
    uid_t ruid, euid, suid;
    gid_t rgid, egid, sgid;
    if (getresuid(&ruid, &euid, &suid) || getresgid(&rgid, &egid, &sgid) ||
        ruid != 2000 || euid != 2000 || suid != 2000 ||
        rgid != 2000 || egid != 2000 || sgid != 2000) {
        errno = EPERM; fail("Shell identity verification");
    }
}

extern "C" JNIEXPORT jintArray JNICALL
Java_dev_deepseek_mobile_terminal_NativeProcess_spawnShellWorker(
    JNIEnv *env, jobject, jstring apkValue, jstring libraryValue) {
    std::string apk = utf8(env, apkValue), library = utf8(env, libraryValue);
    // All values originate in the installed package Context, never in Agent parameters.
    if (apk.empty() || apk[0] != '/' || library.empty() || library[0] != '/') return nullptr;
    std::string classpath = "CLASSPATH=" + apk;
    std::vector<std::string> runtime_env;
    for (const char *key : {"ANDROID_ROOT", "ANDROID_DATA", "ANDROID_ART_ROOT",
            "ANDROID_RUNTIME_ROOT", "ANDROID_I18N_ROOT", "ANDROID_TZDATA_ROOT",
            "BOOTCLASSPATH", "DEX2OATBOOTCLASSPATH"}) {
        const char *value = getenv(key);
        if (value) runtime_env.emplace_back(std::string(key) + "=" + value);
    }
    std::vector<char *> runtime_vars;
    for (auto &value : runtime_env) runtime_vars.push_back(value.data());
    int input[2], output[2], error[2];
    if (pipe2(input, O_CLOEXEC)) return nullptr;
    if (pipe2(output, O_CLOEXEC)) {
        close(input[0]); close(input[1]); return nullptr;
    }
    if (pipe2(error, O_CLOEXEC)) {
        close(input[0]); close(input[1]); close(output[0]); close(output[1]); return nullptr;
    }
    pid_t parent = getpid();
    pid_t pid = fork();
    if (pid == 0) {
        if (dup2(input[0], 0) < 0 || dup2(output[1], 1) < 0 || dup2(error[1], 2) < 0)
            fail("Shell worker pipes");
        // A service may have closed stdin. dup2(fd, fd) does not clear O_CLOEXEC.
        for (int fd = 0; fd <= 2; ++fd)
            if (fcntl(fd, F_SETFD, 0) < 0) fail("Shell worker pipe inheritance");
        if (setsid() < 0) fail("Shell worker setsid");
        long maxfd = sysconf(_SC_OPEN_MAX);
        if (maxfd < 0) maxfd = 65536;
        for (int fd = 3; fd < maxfd; ++fd) close(fd);
        become_shell();
        // Credential changes clear PDEATHSIG, so set it after the downgrade and check the race.
        if (prctl(PR_SET_PDEATHSIG, SIGKILL) || getppid() != parent)
            fail("Shell worker parent lifetime");
        if (chdir("/")) fail("Shell worker cwd");
        char path[] = "PATH=/system/bin:/system/xbin", lang[] = "LANG=C.UTF-8";
        char home[] = "HOME=/data/local/tmp", temp[] = "TMPDIR=/data/local/tmp";
        runtime_vars.insert(runtime_vars.end(), {path, lang, home, temp, classpath.data(), nullptr});
        char *argv[] = {const_cast<char *>("app_process"), const_cast<char *>("/system/bin"),
            const_cast<char *>("--nice-name=dev.deepseek.mobile:terminal_shell_worker"),
            const_cast<char *>("dev.deepseek.mobile.terminal.ShellWorkerMain"),
            library.data(), nullptr};
        execve("/system/bin/app_process", argv, runtime_vars.data());
        fail("Shell worker app_process");
    }
    close(input[0]); close(output[1]); close(error[1]);
    if (pid < 0) { close(input[1]); close(output[0]); close(error[0]); return nullptr; }
    jint values[] = {pid, input[1], output[0], error[0]};
    jintArray array = env->NewIntArray(4);
    env->SetIntArrayRegion(array, 0, 4, values);
    return array;
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_deepseek_mobile_terminal_NativeProcess_waitFor(JNIEnv *, jobject, jint pid) {
    int status = 0;
    while (waitpid(pid, &status, 0) < 0) {
        if (errno != EINTR) return 125;
    }
    return WIFEXITED(status) ? WEXITSTATUS(status) : 128 + WTERMSIG(status);
}
extern "C" JNIEXPORT void JNICALL
Java_dev_deepseek_mobile_terminal_NativeProcess_terminate(JNIEnv *, jobject, jint pid) {
    if (pid > 1) {
        kill(-pid, SIGKILL);
        kill(pid, SIGKILL);
    }
}

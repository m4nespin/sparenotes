#include <errno.h>
#include <sched.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ptrace.h>
#include <sys/syscall.h>
#include <sys/uio.h>
#include <sys/wait.h>
#include <unistd.h>
#include <linux/elf.h>

int main(int argc, char **argv) {
    if (argc < 2) return 64;
    pid_t child = fork();
    if (child == 0) {
        if (ptrace(PTRACE_TRACEME, 0, 0, 0) == -1) {
            fprintf(stderr, "compatwrap: TRACEME: %s\n", strerror(errno));
            return 65;
        }
        raise(SIGSTOP);
        execv(argv[1], &argv[1]);
        fprintf(stderr, "compatwrap: exec: %s\n", strerror(errno));
        return 66;
    }
    if (child < 0) return 67;
    int status;
    if (waitpid(child, &status, 0) == -1) return 68;
    ptrace(PTRACE_SETOPTIONS, child, 0, PTRACE_O_TRACESYSGOOD | PTRACE_O_TRACEFORK);
    ptrace(PTRACE_SYSCALL, child, 0, 0);
    for (;;) {
        pid_t stopped = waitpid(-1, &status, __WALL);
        if (stopped == -1) return 69;
        if (WIFEXITED(status)) {
            if (stopped == child) return WEXITSTATUS(status);
            continue;
        }
        if (WIFSIGNALED(status)) {
            fprintf(stderr, "compatwrap: pid %d signal %d\n", stopped, WTERMSIG(status));
            if (stopped == child) return 128 + WTERMSIG(status);
            continue;
        }
        if (!WIFSTOPPED(status)) continue;
        int sig = WSTOPSIG(status);
        if (sig == (SIGTRAP | 0x80)) {
            struct user_pt_regs regs;
            struct iovec io = { &regs, sizeof(regs) };
            if (ptrace(PTRACE_GETREGSET, stopped, (void *)NT_PRSTATUS, &io) == 0) {
                unsigned long long vfork_flags = CLONE_VM | CLONE_VFORK | SIGCHLD;
                if (regs.regs[8] == SYS_clone && regs.regs[0] == vfork_flags) {
                    // Android 11 seccomp kills musl's vfork-style posix_spawn.
                    // Plain fork preserves posix_spawn semantics without shared memory.
                    regs.regs[0] = SIGCHLD;
                    if (ptrace(PTRACE_SETREGSET, stopped, (void *)NT_PRSTATUS, &io) == -1) {
                        fprintf(stderr, "compatwrap: cannot rewrite clone: %s\n", strerror(errno));
                        ptrace(PTRACE_KILL, stopped, 0, 0);
                        return 159;
                    }
                }
            }
            ptrace(PTRACE_SYSCALL, stopped, 0, 0);
            continue;
        }
        if (sig == SIGSYS) {
            struct user_pt_regs regs;
            struct iovec io = { &regs, sizeof(regs) };
            if (ptrace(PTRACE_GETREGSET, stopped, (void *)NT_PRSTATUS, &io) == 0) {
                fprintf(stderr, "compatwrap: blocked syscall=%llu; returning ENOSYS\n",
                        (unsigned long long)regs.regs[8]);
                regs.regs[0] = (unsigned long long)-ENOSYS;
                if (ptrace(PTRACE_SETREGSET, stopped, (void *)NT_PRSTATUS, &io) == -1) {
                    fprintf(stderr, "compatwrap: cannot set result: %s\n", strerror(errno));
                    ptrace(PTRACE_KILL, stopped, 0, 0);
                    return 159;
                }
            } else {
                fprintf(stderr, "compatwrap: registers unavailable: %s\n", strerror(errno));
                ptrace(PTRACE_KILL, stopped, 0, 0);
                return 159;
            }
            ptrace(PTRACE_SYSCALL, stopped, 0, 0);
            continue;
        }
        ptrace(PTRACE_SYSCALL, stopped, 0, sig == SIGTRAP || sig == SIGSTOP ? 0 : sig);
    }
}

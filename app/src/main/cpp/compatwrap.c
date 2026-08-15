#include <errno.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ptrace.h>
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
    ptrace(PTRACE_CONT, child, 0, 0);
    for (;;) {
        if (waitpid(child, &status, 0) == -1) return 69;
        if (WIFEXITED(status)) return WEXITSTATUS(status);
        if (WIFSIGNALED(status)) {
            fprintf(stderr, "compatwrap: signal %d\n", WTERMSIG(status));
            return 128 + WTERMSIG(status);
        }
        if (!WIFSTOPPED(status)) continue;
        int sig = WSTOPSIG(status);
        if (sig == SIGSYS) {
            struct user_pt_regs regs;
            struct iovec io = { &regs, sizeof(regs) };
            if (ptrace(PTRACE_GETREGSET, child, (void *)NT_PRSTATUS, &io) == 0) {
                fprintf(stderr, "compatwrap: blocked syscall=%llu; returning ENOSYS\n",
                        (unsigned long long)regs.regs[8]);
                regs.regs[0] = (unsigned long long)-ENOSYS;
                if (ptrace(PTRACE_SETREGSET, child, (void *)NT_PRSTATUS, &io) == -1) {
                    fprintf(stderr, "compatwrap: cannot set result: %s\n", strerror(errno));
                    ptrace(PTRACE_KILL, child, 0, 0);
                    return 159;
                }
            } else {
                fprintf(stderr, "compatwrap: registers unavailable: %s\n", strerror(errno));
                ptrace(PTRACE_KILL, child, 0, 0);
                return 159;
            }
            ptrace(PTRACE_CONT, child, 0, 0);
            continue;
        }
        ptrace(PTRACE_CONT, child, 0, sig == SIGTRAP || sig == SIGSTOP ? 0 : sig);
    }
}


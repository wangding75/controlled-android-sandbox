package com.warden.controlledsandbox.runtime.hostile;

import java.util.List;

/**
 * First-version ISOLATED_HOSTILE seccomp policy. Syscall numbers are applied in
 * native code from NDK headers; this Java list is the product deny set.
 */
public final class HostileSeccompPolicy {
    public static final List<String> DENY_NETWORK = List.of(
            "socket", "connect", "bind", "sendto", "sendmsg", "socketcall");
    public static final List<String> DENY_ESCAPE = List.of(
            "ptrace", "execve", "execveat");
    public static final String ACTION = "SECCOMP_RET_ERRNO";
    public static final String UNKNOWN_SYSCALL = "ALLOW";
    public static final String SCOPE = "ISOLATED_HOSTILE_ONLY";

    private HostileSeccompPolicy() { }

    public static List<String> denyNames() {
        return List.of(
                "socket", "connect", "bind", "sendto", "sendmsg", "socketcall",
                "ptrace", "execve", "execveat");
    }
}

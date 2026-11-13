package dev.craftcompanion;

/**
 * Lowers the CPU priority of the Claude Code process on Windows, so a reply never slows the game down.
 * Uses JNA, which Minecraft already ships. Does nothing (and never fails) if it isn't available.
 */
final class ProcessPriority {
    private ProcessPriority() {}

    private static final int PROCESS_SET_INFORMATION = 0x0200;
    private static final int BELOW_NORMAL_PRIORITY_CLASS = 0x4000;

    static void lower(Process p) {
        if (!System.getProperty("os.name", "").toLowerCase().contains("win")) return;
        try {
            Win.lower((int) p.pid());
        } catch (Throwable ignored) {
            // JNA missing or call failed: run at normal priority.
        }
    }

    /** Kept in its own class so the JNA types are only loaded when this actually runs. */
    private static final class Win {
        static void lower(int pid) {
            com.sun.jna.platform.win32.Kernel32 k32 = com.sun.jna.platform.win32.Kernel32.INSTANCE;
            com.sun.jna.platform.win32.WinNT.HANDLE h = k32.OpenProcess(PROCESS_SET_INFORMATION, false, pid);
            if (h == null) return;
            try {
                Kernel32Ex.INSTANCE.SetPriorityClass(h.getPointer(), BELOW_NORMAL_PRIORITY_CLASS);
            } finally {
                k32.CloseHandle(h);
            }
        }
    }
}

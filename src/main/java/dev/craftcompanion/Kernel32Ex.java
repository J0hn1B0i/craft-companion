package dev.craftcompanion;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;

/** The one Windows call JNA's own Kernel32 interface doesn't have. Only used through ProcessPriority. */
public interface Kernel32Ex extends Library {
    Kernel32Ex INSTANCE = Native.load("kernel32", Kernel32Ex.class);

    boolean SetPriorityClass(Pointer process, int priorityClass);
}

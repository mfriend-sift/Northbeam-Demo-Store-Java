package com.northbeam.store.lib;

import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * The one lock around all of the store's state (data, id counters, sessions, the mail outbox). Every route runs its
 * whole decision inside Gate.run, so a check and the change that follows it are one step. The lock is reentrant,
 * so helpers may take it again. Nothing slow runs inside it except a password hash: the payment gateway
 * call, the mock reset and the request body read never do.
 */
public final class Gate {
    private Gate() {}

    private static volatile Lock lock = new ReentrantLock();

    /** Test seam for the lock probe: swaps the lock (a lock that does nothing shows whether the probe sees a race). */
    public static void useLock(Lock replacement) { lock = replacement; }

    public static <T> T run(Supplier<T> work) {
        Lock l = lock;
        l.lock();
        try { return work.get(); } finally { l.unlock(); }
    }

    public static void run(Runnable work) {
        Lock l = lock;
        l.lock();
        try { work.run(); } finally { l.unlock(); }
    }
}

package patternrouter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Work handed from the network thread to the server thread, bounded per player so a flooding client can neither
 * grow the queue nor push other players' work back.
 */
public final class ServerQueue {

    /** Whole queue; beyond it new work is dropped. */
    private static final int MAX_QUEUED = 1024;
    /** Uploads or extractions of one player waiting at once. */
    private static final int MAX_ACTIONS_PER_PLAYER = 4;
    /**
     * Least time between two list lookups of one player. A lookup scans the whole network; a client sending requests
     * every tick would otherwise get one every tick. A request sent sooner waits and still gets answered.
     */
    private static final long MIN_LOOKUP_INTERVAL_NS = 250_000_000L;

    private static final Queue<Runnable> TASKS = new ConcurrentLinkedQueue<>();
    private static final AtomicInteger SIZE = new AtomicInteger();

    private static final Object LOCK = new Object();
    /** Per player: the list request waiting to run; a newer one replaces its task in place. */
    private static final Map<UUID, LatestRequest> REQUESTS = new HashMap<>();
    /** Per player and kind: actions waiting to run. */
    private static final Map<String, Integer> ACTIONS = new HashMap<>();
    /** Per player: when the last list lookup started (System.nanoTime). */
    private static final Map<UUID, Long> LAST_LOOKUP = new HashMap<>();
    /** Lookups that came too soon after the previous one; queued again at the next tick. */
    private static final List<Runnable> DEFERRED = new ArrayList<>();

    private static boolean warnedFull;

    private ServerQueue() {}

    private static final class LatestRequest implements Runnable {

        final UUID player;
        Runnable task;

        LatestRequest(UUID player, Runnable task) {
            this.player = player;
            this.task = task;
        }

        @Override
        public void run() {
            Runnable r;
            synchronized (LOCK) {
                long now = System.nanoTime();
                Long last = LAST_LOOKUP.get(player);
                if (last != null && now - last < MIN_LOOKUP_INTERVAL_NS) {
                    // Stays the player's waiting request: a newer one still replaces its task meanwhile.
                    DEFERRED.add(this);
                    return;
                }
                REQUESTS.remove(player, this);
                LAST_LOOKUP.put(player, now);
                r = task;
            }
            r.run();
        }
    }

    /**
     * Only the newest list request of a player is worked out. A newer one takes the place of the waiting one; after
     * an upload or extraction (see {@link #submitAction}) the next request queues behind it instead, so it never runs
     * before an action sent earlier.
     */
    public static void submitLatestRequest(UUID player, Runnable task) {
        synchronized (LOCK) {
            LatestRequest waiting = REQUESTS.get(player);
            if (waiting != null) {
                waiting.task = task;
                return;
            }
            LatestRequest slot = new LatestRequest(player, task);
            if (!offer(slot)) return;
            REQUESTS.put(player, slot);
        }
    }

    /** Upload or extraction: kept in order, at most a few per player and kind waiting. */
    public static void submitAction(UUID player, String kind, Runnable task) {
        String key = player + "|" + kind;
        synchronized (LOCK) {
            int waiting = ACTIONS.getOrDefault(key, 0);
            if (waiting >= MAX_ACTIONS_PER_PLAYER) return;
            if (!offer(() -> {
                synchronized (LOCK) {
                    int left = ACTIONS.getOrDefault(key, 1) - 1;
                    if (left <= 0) ACTIONS.remove(key);
                    else ACTIONS.put(key, left);
                }
                task.run();
            })) return;
            ACTIONS.put(key, waiting + 1);
            REQUESTS.remove(player);
        }
    }

    /** The player left: nothing of theirs needs remembering. */
    public static void forgetPlayer(UUID player) {
        synchronized (LOCK) {
            LAST_LOOKUP.remove(player);
        }
    }

    private static boolean offer(Runnable r) {
        if (SIZE.incrementAndGet() > MAX_QUEUED) {
            SIZE.decrementAndGet();
            if (!warnedFull) {
                warnedFull = true;
                PatternRouter.LOG.warn("Pattern Router server queue is full; dropping requests");
            }
            return false;
        }
        warnedFull = false;
        TASKS.add(r);
        return true;
    }

    /** Start of a server tick: lookups that had to wait go back into the queue (at most one per player). */
    static void requeueDeferred() {
        synchronized (LOCK) {
            if (DEFERRED.isEmpty()) return;
            for (Runnable r : DEFERRED) {
                if (!offer(r) && r instanceof LatestRequest l) REQUESTS.remove(l.player, l);
            }
            DEFERRED.clear();
        }
    }

    /** Next task for the server thread, or null. */
    static Runnable poll() {
        Runnable r = TASKS.poll();
        if (r != null) SIZE.decrementAndGet();
        return r;
    }
}

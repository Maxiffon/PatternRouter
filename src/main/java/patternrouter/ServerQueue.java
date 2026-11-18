package patternrouter;

import java.util.HashMap;
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

    private static final Queue<Runnable> TASKS = new ConcurrentLinkedQueue<>();
    private static final AtomicInteger SIZE = new AtomicInteger();

    private static final Object LOCK = new Object();
    /** Per player: the list request waiting to run; a newer one replaces its task in place. */
    private static final Map<UUID, LatestRequest> REQUESTS = new HashMap<>();
    /** Per player and kind: actions waiting to run. */
    private static final Map<String, Integer> ACTIONS = new HashMap<>();

    private static boolean warnedFull;

    private ServerQueue() {}

    private static final class LatestRequest {

        Runnable task;

        LatestRequest(Runnable task) {
            this.task = task;
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
            LatestRequest slot = new LatestRequest(task);
            if (!offer(() -> {
                Runnable r;
                synchronized (LOCK) {
                    REQUESTS.remove(player, slot);
                    r = slot.task;
                }
                r.run();
            })) return;
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

    /** Next task for the server thread, or null. */
    static Runnable poll() {
        Runnable r = TASKS.poll();
        if (r != null) SIZE.decrementAndGet();
        return r;
    }
}

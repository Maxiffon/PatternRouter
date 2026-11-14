package patternrouter;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

public class CommonProxy {

    /** Tasks from the network thread that must run on the server thread. */
    private static final Queue<Runnable> SERVER_TASKS = new ConcurrentLinkedQueue<>();

    public static void runOnServer(Runnable r) {
        SERVER_TASKS.add(r);
    }

    public void init() {
        FMLCommonHandler.instance()
            .bus()
            .register(this);
    }

    /** Client only: candidate list received. */
    public void onCandidates(Net.CandidateList msg) {}

    /** Client only: upload result received. */
    public void onResult(Net.Result msg) {}

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.START) return;
        patternrouter.server.Bench.tick();
        Runnable r;
        int budget = 64;
        while (budget-- > 0 && (r = SERVER_TASKS.poll()) != null) {
            // If a task runs long, log where the server thread is, so a freeze can be attributed for sure.
            Thread server = Thread.currentThread();
            ScheduledFuture<?> watch = WATCHDOG.schedule(() -> {
                StringBuilder b = new StringBuilder("Pattern Router task is still running after ").append(SLOW_TASK_MS)
                    .append(" ms; server thread at:");
                for (StackTraceElement e : server.getStackTrace()) b.append("\n\tat ")
                    .append(e);
                PatternRouter.LOG.warn(b.toString());
            }, SLOW_TASK_MS, TimeUnit.MILLISECONDS);
            long started = System.nanoTime();
            try {
                r.run();
            } catch (Throwable t) {
                PatternRouter.LOG.error("Server task failed", t);
            } finally {
                watch.cancel(false);
                long ms = (System.nanoTime() - started) / 1_000_000;
                if (ms >= 200) PatternRouter.LOG.warn("Pattern Router server task took {} ms", ms);
            }
        }
    }

    private static final long SLOW_TASK_MS = 2000;

    private static final ScheduledExecutorService WATCHDOG = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "PatternRouter-watchdog");
        t.setDaemon(true);
        return t;
    });
}

package patternrouter;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import net.minecraft.entity.player.EntityPlayerMP;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import patternrouter.server.Bench;

public class CommonProxy {

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
        try {
            Bench.tick();
        } catch (Throwable t) {
            PatternRouter.LOG.error("Pattern Router bench tick failed", t);
        }
        Runnable r;
        int budget = 64;
        // At most ~25 ms of lookups per tick (half a tick); the rest waits for the next tick. At least one task runs.
        long deadline = System.nanoTime() + TICK_BUDGET_NS;
        boolean first = true;
        while (budget-- > 0 && (first || System.nanoTime() < deadline) && (r = ServerQueue.poll()) != null) {
            first = false;
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

    @SubscribeEvent
    public void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.player instanceof EntityPlayerMP) Bench.onLogout((EntityPlayerMP) event.player);
    }

    private static final long SLOW_TASK_MS = 2000;
    private static final long TICK_BUDGET_NS = 25_000_000L;

    private static final ScheduledExecutorService WATCHDOG = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "PatternRouter-watchdog");
        t.setDaemon(true);
        return t;
    });
}

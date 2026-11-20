package patternrouter;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.world.WorldEvent;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import patternrouter.net.ActionResultMessage;
import patternrouter.net.CandidateListMessage;
import patternrouter.server.BenchCommand;
import patternrouter.server.CandidateSearch;

public class CommonProxy {

    /** At most this many queued tasks per tick. */
    private static final int MAX_TASKS_PER_TICK = 64;
    /** At most ~25 ms of lookups per tick (half a tick); the rest waits for the next tick. At least one task runs. */
    private static final long TICK_BUDGET_NS = 25_000_000L;
    /** A task this slow is reported in the console. */
    private static final long SLOW_TASK_WARN_MS = 200;
    /** A task still running after this long gets the server thread's stack logged, so a freeze can be attributed. */
    private static final long STUCK_TASK_MS = 2000;

    private static final ScheduledExecutorService WATCHDOG = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "PatternRouter-watchdog");
        t.setDaemon(true);
        return t;
    });

    public void init() {
        FMLCommonHandler.instance()
            .bus()
            .register(this);
        MinecraftForge.EVENT_BUS.register(new WorldEvents());
    }

    /** Client only: candidate list received. */
    public void onCandidates(CandidateListMessage msg) {}

    /** Client only: upload result received. */
    public void onResult(ActionResultMessage msg) {}

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.START) return;
        try {
            BenchCommand.tick();
        } catch (Throwable t) {
            PatternRouter.LOG.error("Pattern Router bench tick failed", t);
        }
        runQueuedTasks();
    }

    private static void runQueuedTasks() {
        ServerQueue.requeueDeferred();
        Runnable task;
        int budget = MAX_TASKS_PER_TICK;
        long deadline = System.nanoTime() + TICK_BUDGET_NS;
        boolean first = true;
        while (budget-- > 0 && (first || System.nanoTime() < deadline) && (task = ServerQueue.poll()) != null) {
            first = false;
            runWatched(task);
        }
    }

    private static void runWatched(Runnable task) {
        Thread server = Thread.currentThread();
        ScheduledFuture<?> watch = WATCHDOG.schedule(() -> logStack(server), STUCK_TASK_MS, TimeUnit.MILLISECONDS);
        long started = System.nanoTime();
        try {
            task.run();
        } catch (Throwable t) {
            PatternRouter.LOG.error("Server task failed", t);
        } finally {
            watch.cancel(false);
            long ms = (System.nanoTime() - started) / 1_000_000;
            if (ms >= SLOW_TASK_WARN_MS) PatternRouter.LOG.warn("Pattern Router server task took {} ms", ms);
        }
    }

    private static void logStack(Thread server) {
        StringBuilder b = new StringBuilder("Pattern Router task is still running after ").append(STUCK_TASK_MS)
            .append(" ms; server thread at:");
        for (StackTraceElement e : server.getStackTrace()) b.append("\n\tat ")
            .append(e);
        PatternRouter.LOG.warn(b.toString());
    }

    @SubscribeEvent
    public void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.player instanceof EntityPlayerMP player) BenchCommand.onLogout(player);
        CandidateSearch.forgetPlayer(event.player.getUniqueID());
        ServerQueue.forgetPlayer(event.player.getUniqueID());
    }

    /** Forge-bus events (the proxy itself listens on the FML bus). */
    public static final class WorldEvents {

        @SubscribeEvent
        public void onWorldUnload(WorldEvent.Unload event) {
            if (!event.world.isRemote) CandidateSearch.forgetWorld(event.world);
        }
    }
}

package gtnhpatternupload;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

public class CommonProxy {

    /** Задачи из сетевого потока, которые надо выполнить в основном потоке сервера. */
    private static final Queue<Runnable> SERVER_TASKS = new ConcurrentLinkedQueue<>();

    public static void runOnServer(Runnable r) {
        SERVER_TASKS.add(r);
    }

    public void init() {
        FMLCommonHandler.instance().bus().register(this);
    }

    /** Только на клиенте: пришёл список кандидатов. */
    public void onCandidates(Net.CandidateList msg) {}

    /** Только на клиенте: пришёл результат загрузки. */
    public void onResult(Net.Result msg) {}

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.START) return;
        Runnable r;
        int budget = 64;
        while (budget-- > 0 && (r = SERVER_TASKS.poll()) != null) {
            try {
                r.run();
            } catch (Throwable t) {
                PatternUpload.LOG.error("Server task failed", t);
            }
        }
    }
}

package patternrouter.server;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ChatComponentText;

import appeng.api.networking.IGrid;
import patternrouter.PatternRouter;

/**
 * {@code /patternrouter bench [runs]}: replays the player's last panel lookup (same pattern, network, NEI category)
 * and reports timings. One run per server tick, so the server never stalls; a few warm-up runs are not counted. A
 * lookup without a category (the GT lookup over every machine) is the heaviest one.
 */
public final class BenchCommand extends CommandBase {

    private static final int WARMUP_RUNS = 3;
    private static final int DEFAULT_RUNS = 50;
    private static final int MIN_RUNS = 5;
    private static final int MAX_RUNS = 500;
    private static final int OP_PERMISSION_LEVEL = 2;

    /** The last real panel request of each player. */
    private static final Map<UUID, Replay> LAST_REQUESTS = new HashMap<>();

    private static Job job;

    /** A panel request as it can be run again with the terminal closed. */
    static final class Replay {

        final ItemStack pattern;
        final WeakReference<IGrid> grid;
        final String category;
        final List<ItemStack> nonConsumables;
        final String machineName;

        Replay(ItemStack pattern, IGrid grid, String category, List<ItemStack> nonConsumables, String machineName) {
            this.pattern = pattern;
            this.grid = new WeakReference<>(grid);
            this.category = category;
            this.nonConsumables = new ArrayList<>(nonConsumables);
            this.machineName = machineName;
        }
    }

    private static final class Job {

        final EntityPlayerMP player;
        final String category;
        final int runs;
        int warmupLeft = WARMUP_RUNS;
        long firstMicros = -1;
        final List<Long> micros = new ArrayList<>();

        Job(EntityPlayerMP player, String category, int runs) {
            this.player = player;
            this.category = category;
            this.runs = runs;
        }
    }

    static void record(EntityPlayerMP player, ItemStack pattern, IGrid grid, String category,
        List<ItemStack> nonConsumables, String machineName) {
        LAST_REQUESTS
            .put(player.getUniqueID(), new Replay(pattern.copy(), grid, category, nonConsumables, machineName));
    }

    /** The player's last request while its network still exists, or null. */
    static Replay replayOf(EntityPlayerMP player) {
        Replay r = LAST_REQUESTS.get(player.getUniqueID());
        return r == null || r.grid.get() == null ? null : r;
    }

    static void forget(UUID player) {
        LAST_REQUESTS.remove(player);
    }

    @Override
    public String getCommandName() {
        return "patternrouter";
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/patternrouter bench [runs]";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return OP_PERMISSION_LEVEL;
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        if (args.length == 0 || !"bench".equalsIgnoreCase(args[0]))
            throw new WrongUsageException(getCommandUsage(sender));
        if (!(sender instanceof EntityPlayerMP player)) {
            sender.addChatMessage(new ChatComponentText("Run it as a player with a pattern terminal open."));
            return;
        }
        Replay replay = replayOf(player);
        if (replay == null) {
            sender.addChatMessage(
                new ChatComponentText(
                    "Encode a pattern in a pattern terminal first (the panel must list machines), close it, then run"
                        + " /patternrouter bench. It replays that last lookup."));
            return;
        }
        int runs = args.length > 1 ? parseIntBounded(sender, args[1], MIN_RUNS, MAX_RUNS) : DEFAULT_RUNS;
        job = new Job(player, replay.category, runs);
        sender.addChatMessage(
            new ChatComponentText("Pattern Router bench: " + runs + " runs of your last lookup, one per tick."));
    }

    /** Called once per server tick by CommonProxy. */
    public static void tick() {
        Job j = job;
        if (j == null) return;
        Replay replay = replayOf(j.player);
        if (!isOnline(j.player) || replay == null) {
            PatternRouter.LOG.info("Pattern Router bench stopped: player left or the network is gone");
            job = null;
            return;
        }
        long started = System.nanoTime();
        try {
            CandidateSearch.replay(j.player, replay);
        } catch (Throwable t) {
            job = null;
            PatternRouter.LOG.error("Pattern Router bench stopped: the lookup failed", t);
            j.player
                .addChatMessage(new ChatComponentText("Pattern Router bench stopped: the lookup failed (see log)."));
            return;
        }
        long micros = (System.nanoTime() - started) / 1000;
        if (j.firstMicros < 0) j.firstMicros = micros;
        if (j.warmupLeft > 0) {
            j.warmupLeft--;
            return;
        }
        j.micros.add(micros);
        if (j.micros.size() < j.runs) return;
        job = null;
        report(j);
    }

    private static boolean isOnline(EntityPlayerMP player) {
        MinecraftServer server = MinecraftServer.getServer();
        return server != null && server.getConfigurationManager().playerEntityList.contains(player);
    }

    /** The player left: drop a bench they started. */
    public static void onLogout(EntityPlayerMP player) {
        Job j = job;
        if (j != null && j.player == player) job = null;
    }

    private static void report(Job j) {
        List<Long> t = new ArrayList<>(j.micros);
        Collections.sort(t);
        long sum = 0;
        for (long v : t) sum += v;
        String msg = String.format(
            "Pattern Router bench (%s): %d providers, %d runs after %d warm-up | first %.1f ms | min %.1f | median %.1f"
                + " | p95 %.1f | max %.1f | mean %.1f ms",
            j.category.isEmpty() ? "no NEI category" : j.category,
            CandidateSearch.providersInLastLookup,
            t.size(),
            WARMUP_RUNS,
            j.firstMicros / 1000.0,
            t.get(0) / 1000.0,
            t.get(t.size() / 2) / 1000.0,
            t.get(Math.min(t.size() - 1, (int) Math.ceil(t.size() * 0.95) - 1)) / 1000.0,
            t.get(t.size() - 1) / 1000.0,
            sum / 1000.0 / t.size());
        PatternRouter.LOG.info(msg);
        j.player.addChatMessage(new ChatComponentText(msg));
    }
}

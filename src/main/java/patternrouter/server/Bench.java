package patternrouter.server;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ChatComponentText;

import patternrouter.PatternRouter;

/**
 * {@code /patternrouter bench [runs]}: replays the player's last panel lookup (same pattern, network, NEI category)
 * and reports timings. One run per server tick, so the server never stalls; a few
 * warm-up runs are not counted. Without a category it uses the no-NEI path (the GT lookup over every machine), the
 * heaviest one.
 */
public final class Bench extends CommandBase {

    private static final int WARMUP = 3;
    private static final int DEFAULT_RUNS = 50;
    private static final int MAX_RUNS = 500;

    private static Job job;

    private static final class Job {

        final EntityPlayerMP player;
        final String category;
        final int runs;
        int warmupLeft = WARMUP;
        long first = -1;
        final List<Long> times = new ArrayList<>();

        Job(EntityPlayerMP player, String category, int runs) {
            this.player = player;
            this.category = category;
            this.runs = runs;
        }
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
        return 2;
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        if (args.length == 0 || !"bench".equalsIgnoreCase(args[0]))
            throw new WrongUsageException(getCommandUsage(sender));
        if (!(sender instanceof EntityPlayerMP)) {
            sender.addChatMessage(new ChatComponentText("Run it as a player with a pattern terminal open."));
            return;
        }
        EntityPlayerMP player = (EntityPlayerMP) sender;
        if (!Candidates.hasBenchContext(player)) {
            sender.addChatMessage(
                new ChatComponentText(
                    "Encode a pattern in a pattern terminal first (the panel must list machines), close it, then run"
                        + " /patternrouter bench. It replays that last lookup."));
            return;
        }
        int runs = args.length > 1 ? parseIntBounded(sender, args[1], 5, MAX_RUNS) : DEFAULT_RUNS;
        job = new Job(player, Candidates.benchCategory(player), runs);
        sender.addChatMessage(
            new ChatComponentText("Pattern Router bench: " + runs + " runs of your last lookup, one per tick."));
    }

    /** Called once per server tick by CommonProxy. */
    public static void tick() {
        Job j = job;
        if (j == null) return;
        if (!isOnline(j.player) || !Candidates.hasBenchContext(j.player)) {
            PatternRouter.LOG.info("Pattern Router bench stopped: player left or the network is gone");
            job = null;
            return;
        }
        long t0 = System.nanoTime();
        try {
            Candidates.buildReply(j.player, "", Collections.<ItemStack>emptyList(), "", true);
        } catch (Throwable t) {
            job = null;
            PatternRouter.LOG.error("Pattern Router bench stopped: the lookup failed", t);
            j.player
                .addChatMessage(new ChatComponentText("Pattern Router bench stopped: the lookup failed (see log)."));
            return;
        }
        long micros = (System.nanoTime() - t0) / 1000;
        if (j.first < 0) j.first = micros;
        if (j.warmupLeft > 0) {
            j.warmupLeft--;
            return;
        }
        j.times.add(micros);
        if (j.times.size() < j.runs) return;
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
        List<Long> t = new ArrayList<>(j.times);
        Collections.sort(t);
        long sum = 0;
        for (long v : t) sum += v;
        String msg = String.format(
            "Pattern Router bench (%s): %d providers, %d runs after %d warm-up | first %.1f ms | min %.1f | median %.1f"
                + " | p95 %.1f | max %.1f | mean %.1f ms",
            j.category.isEmpty() ? "no NEI category" : j.category,
            Candidates.lastProviders,
            t.size(),
            WARMUP,
            j.first / 1000.0,
            t.get(0) / 1000.0,
            t.get(t.size() / 2) / 1000.0,
            t.get(Math.min(t.size() - 1, (int) Math.ceil(t.size() * 0.95) - 1)) / 1000.0,
            t.get(t.size() - 1) / 1000.0,
            sum / 1000.0 / t.size());
        PatternRouter.LOG.info(msg);
        j.player.addChatMessage(new ChatComponentText(msg));
    }
}

package patternrouter.server;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;

import org.apache.logging.log4j.Level;

import appeng.api.AEApi;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.util.IInterfaceViewable;
import appeng.container.implementations.ContainerPatternTerm;
import gregtech.api.recipe.RecipeMap;
import patternrouter.PatternRouter;
import patternrouter.common.ListNote;
import patternrouter.net.CandidateListMessage;
import patternrouter.net.PatternRouterNetwork;
import patternrouter.net.ProviderEntry;
import patternrouter.server.PlayerSessions.ConflictRef;
import patternrouter.server.PlayerSessions.ShownList;
import patternrouter.server.ae2.GridAccess;
import patternrouter.server.ae2.Patterns;
import patternrouter.server.ae2.ProviderCompat;
import patternrouter.server.machine.HatchOwners;
import patternrouter.server.machine.MachineLocator;
import patternrouter.server.recipe.PatternContents;
import patternrouter.server.recipe.RecipeMaps;
import patternrouter.server.recipe.SkippedCooling;

/**
 * Finds the interfaces and CRIBs in the pattern terminal's ME network (and networks linked to it) whose machine fits
 * the terminal's pattern, and builds the list sent to the player.
 */
public final class CandidateSearch {

    private static final int MAX_ENTRIES = 40;
    private static final int MAX_CONFLICTS = 8;
    /** Lookups slower than this are reported in the console. */
    private static final long SLOW_LOOKUP_MS = 200;

    /**
     * Remembered -> machine seen -> guessed by name last -> has space -> fits -> exact setup before spare setup -> no
     * duplicate -> name. A guess by name stays below every machine that was actually seen, even a full one.
     */
    private static final Comparator<ProviderScore> LIST_ORDER = Comparator
        .<ProviderScore>comparingInt(s -> s.has(ProviderEntry.REMEMBERED) ? 0 : 1)
        .thenComparingInt(s -> s.has(ProviderEntry.RECIPE_MATCH) ? 0 : 1)
        .thenComparingInt(s -> s.has(ProviderEntry.BY_NAME) ? 1 : 0)
        .thenComparingInt(s -> s.freeSlots > 0 ? 0 : 1)
        .thenComparingInt(s -> s.has(ProviderEntry.SETUP_FITS) ? 0 : s.has(ProviderEntry.SETUP_CONFLICTS) ? 2 : 1)
        .thenComparingInt(s -> s.hasSpareSetup ? 1 : 0)
        .thenComparingInt(s -> s.has(ProviderEntry.HAS_SAME_OUTPUT) ? 1 : 0)
        .thenComparing(s -> s.name);

    /** Providers checked by the last lookup (for the benchmark). */
    static int providersInLastLookup;

    private CandidateSearch() {}

    public static void handleRequest(EntityPlayerMP player, String category, List<ItemStack> nonConsumables,
        String machineName) {
        CandidateListMessage reply = forTerminal(player, category, nonConsumables, machineName);
        if (reply != null) PatternRouterNetwork.channel.sendTo(reply, player);
    }

    /** The player left: drop their list and bench replay. */
    public static void forgetPlayer(UUID player) {
        PlayerSessions.forget(player);
        BenchCommand.forget(player);
    }

    /** A world unloads (also leaving a single player world): drop what is cached about it. */
    public static void forgetWorld(World world) {
        HatchOwners.forget(world);
    }

    /** The lookup for the pattern in the player's open terminal; null if no pattern terminal is open. */
    private static CandidateListMessage forTerminal(EntityPlayerMP player, String category,
        List<ItemStack> nonConsumables, String machineName) {
        int token = PlayerSessions.nextToken();
        if (!(player.openContainer instanceof ContainerPatternTerm term)) return null; // already closed, ignore
        ItemStack pattern = Patterns.encodedPattern(term);
        IGrid grid = GridAccess.gridOf(term);
        if (pattern != null && grid != null) {
            BenchCommand.record(player, pattern, grid, category, nonConsumables, machineName);
        }
        return run(player, token, term, pattern, grid, category, nonConsumables, machineName, false);
    }

    /** /patternrouter bench: the player's last lookup again, without a log line and without replacing their list. */
    static CandidateListMessage replay(EntityPlayerMP player, BenchCommand.Replay replay) {
        int token = PlayerSessions.nextToken();
        IGrid grid = replay.grid.get();
        if (grid == null) return null;
        return run(
            player,
            token,
            null,
            replay.pattern,
            grid,
            replay.category,
            replay.nonConsumables,
            replay.machineName,
            true);
    }

    private static CandidateListMessage run(EntityPlayerMP player, int token, ContainerPatternTerm term,
        ItemStack pattern, IGrid grid, String category, List<ItemStack> nonConsumables, String machineName,
        boolean bench) {
        try {
            CandidateListMessage reply = new CandidateListMessage();
            reply.token = token;
            if (pattern == null) {
                reply.note = ListNote.NO_PATTERN.wire();
            } else if (grid == null) {
                reply.note = ListNote.NO_GRID.wire();
            } else if (!GridAccess.canBuild(grid, player)) {
                reply.note = ListNote.DENIED.wire();
            } else {
                fillList(reply, player, term, pattern, grid, category, nonConsumables, machineName, bench);
            }
            return reply;
        } finally {
            forgetRequestState();
        }
    }

    /** Request caches hold tiles, grids and stacks: drop them so nothing of a world outlives the request. */
    private static void forgetRequestState() {
        RecipeMaps.endLookup();
        MachineLocator.endLookup();
        PatternContents.forget();
    }

    private static void fillList(CandidateListMessage reply, EntityPlayerMP player, ContainerPatternTerm term,
        ItemStack pattern, IGrid grid, String category, List<ItemStack> nonConsumables, String machineName,
        boolean bench) {
        long started = System.nanoTime();
        World world = player.worldObj;
        category = RecipeMaps.checkedCategory(category, nonConsumables, pattern, world);
        PatternContents contents = PatternContents.of(pattern, world);
        PatternContents beforeFreezer = SkippedCooling.beforeFreezer(contents);
        if (beforeFreezer != null && SkippedCooling.isFreezerTabOfShortcut(category, contents, nonConsumables)) {
            category = "";
        }
        List<ProviderScore> scored = scoreProviders(grid, player, world, category, nonConsumables, pattern, contents);
        if (beforeFreezer != null && !anyHas(scored, ProviderEntry.RECIPE_MATCH)) {
            // Nothing makes the output from these inputs: look for the machine making what the freezer cools.
            RecipeMaps.endLookup();
            scored = scoreProviders(grid, player, world, "", nonConsumables, pattern, beforeFreezer);
        }
        if (RecipeMaps.forCategory(category) != null) markNameMatches(scored, machineName);
        String choiceKey = RememberedChoices.keyFor(category, nonConsumables, Patterns.storedOutput(pattern, world));
        markRemembered(scored, player, choiceKey);
        // Look for conflicts across the whole network before filtering: the old recipe may be in another machine
        // type.
        List<ConflictRef> conflicts = collectConflicts(scored, reply);
        providersInLastLookup = scored.size();
        logLookup(player, category, nonConsumables, pattern, scored.size(), conflicts.size(), started, bench);
        reply.note = noteFor(scored, category, pattern, world).wire();
        scored = shownProviders(scored);
        List<WeakReference<IInterfaceViewable>> targets = new ArrayList<>();
        for (ProviderScore s : scored) {
            targets.add(new WeakReference<>(s.view));
            reply.entries.add(s.toEntry());
        }
        if (!bench) {
            PlayerSessions
                .put(player.getUniqueID(), new ShownList(reply.token, term, pattern, targets, conflicts, choiceKey));
        }
    }

    /**
     * Every active provider of the terminal's network and the linked networks where the player may build, checked
     * against {@code contents}: the pattern, or the step before one it skips.
     */
    private static List<ProviderScore> scoreProviders(IGrid grid, EntityPlayerMP player, World world, String category,
        List<ItemStack> nonConsumables, ItemStack pattern, PatternContents contents) {
        RecipeMap<?> wanted = RecipeMaps.forCategory(category);
        ItemStack output = Patterns.storedOutput(pattern, world);
        List<ProviderScore> out = new ArrayList<>();
        Set<IInterfaceViewable> seen = new HashSet<>();
        List<IGrid> linked = GridAccess.linkedGrids(grid);
        MachineLocator.beginLookup(linked);
        List<IGrid> grids = new ArrayList<>();
        for (IGrid g : linked) if (g == grid || GridAccess.canBuild(g, player)) grids.add(g);
        for (IGrid g : grids) for (Class<? extends IInterfaceViewable> cls : AEApi.instance()
            .registries()
            .interfaceTerminal()
            .getSupportedClasses()) {
                for (IGridNode node : g.getMachines(cls)) {
                    if (!(node.getMachine() instanceof IInterfaceViewable listed)) continue;
                    IInterfaceViewable view = ProviderCompat.p2pInputOf(listed);
                    if (!seen.add(view) || !view.shouldDisplay() || !ProviderCompat.isActive(view, node)) continue;
                    IInventory patterns = view.getPatterns();
                    if (patterns == null) continue;
                    try {
                        ProviderScore s = ProviderScorer
                            .score(view, patterns, wanted, nonConsumables, pattern, output, contents);
                        // Fluid inputs need a provider that can push fluids (CRIB buffer, dual interface).
                        if (contents.hasFluidInputs() && !ProviderCompat.acceptsFluids(view)) {
                            s.flags = (s.flags & ~ProviderEntry.SETUP_FITS) | ProviderEntry.SETUP_CONFLICTS
                                | ProviderEntry.NO_FLUIDS;
                        }
                        out.add(s);
                    } catch (Throwable t) {
                        PatternRouter.LOG.debug("Skipping provider {}: {}", view.getName(), t.toString());
                    }
                }
            }
        return out;
    }

    /**
     * Machine not seen (chest, conduit in between) but the provider is named after the NEI tab: weak match. Only when
     * the machine is unknown: "Precise Assembler" must not pull in interfaces of plain assemblers. Only names the
     * player gave: an unnamed AE2 interface shows its neighbour's name ("Molecular Assembler").
     */
    private static void markNameMatches(List<ProviderScore> scored, String machineName) {
        List<String> words = ProviderNames.significantWords(machineName);
        for (ProviderScore s : scored) {
            if (!s.has(ProviderEntry.RECIPE_MATCH) && !s.machineSeen
                && ProviderCompat.isCustomNamed(s.view)
                && ProviderNames.containsAny(s.name, words)) {
                s.flags |= ProviderEntry.RECIPE_MATCH | ProviderEntry.BY_NAME;
            }
        }
    }

    /** Providers this player chose before for this kind of pattern: offered first, even if no machine is seen. */
    private static void markRemembered(List<ProviderScore> scored, EntityPlayerMP player, String choiceKey) {
        RememberedChoices choices = choiceKey.isEmpty() ? null : RememberedChoices.get();
        if (choices == null) return;
        for (ProviderScore s : scored) {
            if (choices.isRemembered(player.getUniqueID(), choiceKey, new int[] { s.dim, s.x, s.y, s.z })) {
                s.flags |= ProviderEntry.REMEMBERED;
            }
        }
    }

    private static List<ConflictRef> collectConflicts(List<ProviderScore> scored, CandidateListMessage reply) {
        List<ConflictRef> conflicts = new ArrayList<>();
        for (ProviderScore s : scored) {
            for (int i = 0; i < s.sameOutputSlots.size() && conflicts.size() < MAX_CONFLICTS; i++) {
                int slot = s.sameOutputSlots.get(i);
                ItemStack old = s.view.getPatterns()
                    .getStackInSlot(slot);
                if (old == null) continue;
                conflicts.add(new ConflictRef(s.view, slot, old));
                ProviderEntry e = s.toEntry();
                e.flags = ProviderEntry.HAS_SAME_OUTPUT;
                reply.conflicts.add(e);
            }
        }
        return conflicts;
    }

    /** Debug goes to logs/fml-server-latest.log only; the console gets a line only for slow lookups. */
    private static void logLookup(EntityPlayerMP player, String category, List<ItemStack> nonConsumables,
        ItemStack pattern, int providers, int conflicts, long startedNanos, boolean bench) {
        long ms = (System.nanoTime() - startedNanos) / 1_000_000;
        Level level = bench ? Level.TRACE : ms >= SLOW_LOOKUP_MS ? Level.WARN : Level.DEBUG;
        PatternRouter.LOG.log(
            level,
            "Pattern upload for {}: category '{}', {} non-consumables, output {}, {} providers checked, {} conflicts, {} ms",
            player.getCommandSenderName(),
            category,
            nonConsumables.size(),
            Patterns.storedOutput(pattern, player.worldObj),
            providers,
            conflicts,
            ms);
    }

    /** NONE when some machine fits (by NEI or by the pattern contents); otherwise why not. */
    private static ListNote noteFor(List<ProviderScore> scored, String category, ItemStack pattern, World world) {
        if (anyHas(scored, ProviderEntry.RECIPE_MATCH)) return ListNote.NONE;
        if (PatternContents.of(pattern, world)
            .isCraftingPattern()) return ListNote.NO_ASSEMBLER;
        if (RecipeMaps.forCategory(category) == null) return ListNote.UNKNOWN_RECIPE;
        for (ProviderScore s : scored) if (s.feedsUnformedMultiblock) return ListNote.UNFORMED;
        return ListNote.NO_MACHINE;
    }

    /**
     * Only matching machines if any, otherwise every provider with free space; remembered ones always. If some
     * machine fits, the ones set up for another recipe (wrong circuit/mold/lens) are hidden; when nothing fits they
     * stay as "doesn't fit" so the player sees where to put the right item.
     */
    private static List<ProviderScore> shownProviders(List<ProviderScore> scored) {
        if (anyHas(scored, ProviderEntry.RECIPE_MATCH)) {
            scored.removeIf(s -> (s.flags & (ProviderEntry.RECIPE_MATCH | ProviderEntry.REMEMBERED)) == 0);
        } else {
            scored.removeIf(s -> s.freeSlots == 0 && !s.has(ProviderEntry.REMEMBERED));
        }
        if (anyHas(scored, ProviderEntry.SETUP_FITS)) {
            scored.removeIf(s -> s.has(ProviderEntry.SETUP_CONFLICTS) && !s.has(ProviderEntry.REMEMBERED));
        }
        scored.sort(LIST_ORDER);
        return scored.size() > MAX_ENTRIES ? new ArrayList<>(scored.subList(0, MAX_ENTRIES)) : scored;
    }

    private static boolean anyHas(List<ProviderScore> scored, int flag) {
        for (ProviderScore s : scored) if (s.has(flag)) return true;
        return false;
    }
}

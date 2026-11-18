package patternrouter.server;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.ChunkCoordinates;
import net.minecraft.util.IChatComponent;
import net.minecraft.world.World;
import net.minecraftforge.common.util.ForgeDirection;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.oredict.OreDictionary;

import appeng.api.AEApi;
import appeng.api.config.Actionable;
import appeng.api.config.SecurityPermissions;
import appeng.api.implementations.ICraftingPatternItem;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridHost;
import appeng.api.networking.IGridNode;
import appeng.api.networking.crafting.ICraftingPatternDetails;
import appeng.api.networking.security.IActionHost;
import appeng.api.networking.security.ISecurityGrid;
import appeng.api.networking.security.PlayerSource;
import appeng.api.networking.storage.IStorageGrid;
import appeng.api.storage.StorageName;
import appeng.api.storage.data.IAEFluidStack;
import appeng.api.storage.data.IAEItemStack;
import appeng.api.storage.data.IAEStack;
import appeng.api.util.DimensionalCoord;
import appeng.api.util.IInterfaceViewable;
import appeng.container.implementations.ContainerPatternTerm;
import appeng.helpers.IInterfaceHost;
import appeng.items.misc.ItemEncodedPattern;
import appeng.parts.p2p.PartP2PInterface;
import appeng.util.item.AEItemStack;
import gregtech.api.enums.ItemList;
import gregtech.api.interfaces.IConfigurationCircuitSupport;
import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.interfaces.tileentity.RecipeMapWorkable;
import gregtech.api.metatileentity.implementations.MTEBasicMachine;
import gregtech.api.metatileentity.implementations.MTEHatch;
import gregtech.api.metatileentity.implementations.MTEHatchInput;
import gregtech.api.metatileentity.implementations.MTEHatchInputBus;
import gregtech.api.metatileentity.implementations.MTEMultiBlockBase;
import gregtech.api.recipe.RecipeCategory;
import gregtech.api.recipe.RecipeMap;
import gregtech.api.util.GTRecipe;
import gregtech.common.tileentities.machines.MTEHatchCraftingInputME;
import patternrouter.Net;
import patternrouter.PatternRouter;

/**
 * Server side: finds interfaces and CRIBs in the pattern terminal's ME network that fit the recipe and puts
 * the pattern into the chosen one.
 */
public final class Candidates {

    public static final int NOTE_NONE = 0;
    public static final int NOTE_NO_PATTERN = 1; // no encoded pattern in the output slot
    public static final int NOTE_NO_GRID = 2; // terminal is not on a network
    public static final int NOTE_DENIED = 3; // no build permission in this network
    public static final int NOTE_NO_MACHINE = 4; // no matching machine, showing all interfaces
    public static final int NOTE_UNKNOWN_RECIPE = 5; // not a GT recipe, or not transferred from NEI
    public static final int NOTE_UNFORMED = 6; // no matching machine, and some hatch has no formed multiblock
    public static final int NOTE_NO_ASSEMBLER = 7; // crafting pattern, no molecular assembler in the network

    private static final int MAX_ENTRIES = 40;

    /** Last list sent to the player: maps a row index to its target. */
    private static final class Session {

        final int token;
        final WeakReference<Container> container;
        final List<WeakReference<IInterfaceViewable>> targets;
        final List<Conflict> conflicts;
        /** What is being placed (see choiceKey), remembered on upload. */
        final String key;
        /** The pattern the list was built for; an upload of anything else is refused. */
        final ItemStack pattern;

        Session(int token, Container container, ItemStack pattern, List<WeakReference<IInterfaceViewable>> targets,
            List<Conflict> conflicts, String key) {
            this.key = key;
            this.pattern = pattern.copy();
            this.token = token;
            this.container = new WeakReference<>(container);
            this.targets = targets;
            this.conflicts = conflicts;
        }
    }

    /** Existing pattern with the same output: where it is and which one (re-checked before extraction). */
    private static final class Conflict {

        final WeakReference<IInterfaceViewable> view;
        final int slot;
        final ItemStack pattern;

        Conflict(IInterfaceViewable view, int slot, ItemStack pattern) {
            this.view = new WeakReference<>(view);
            this.slot = slot;
            this.pattern = pattern.copy();
        }
    }

    private static final int MAX_CONFLICTS = 8;
    /** Requests slower than this are reported in the console. */
    private static final long SLOW_MS = 200;

    private static final Map<UUID, Session> SESSIONS = new HashMap<>();
    private static int nextToken = 1;

    private Candidates() {}

    // ------------------------------------------------------------------ list request

    public static void handleRequest(EntityPlayerMP player, String category, List<ItemStack> nonConsumables,
        String machineName) {
        Net.CandidateList reply = buildReply(player, category, nonConsumables, machineName, false);
        if (reply != null) Net.CH.sendTo(reply, player);
    }

    /** Providers checked by the last buildReply (for the benchmark). */
    static int lastProviders;

    /** The player's last panel request, replayed by /patternrouter bench. */
    private static final class BenchContext {

        final ItemStack pattern;
        final WeakReference<IGrid> grid;
        final String category;
        final List<ItemStack> nonConsumables;
        final String machineName;

        BenchContext(ItemStack pattern, IGrid grid, String category, List<ItemStack> nonConsumables,
            String machineName) {
            this.pattern = pattern;
            this.grid = new WeakReference<>(grid);
            this.category = category;
            this.nonConsumables = new ArrayList<>(nonConsumables);
            this.machineName = machineName;
        }
    }

    private static final Map<UUID, BenchContext> LAST_REQUEST = new HashMap<>();

    static boolean hasBenchContext(EntityPlayerMP player) {
        BenchContext c = LAST_REQUEST.get(player.getUniqueID());
        return c != null && c.grid.get() != null;
    }

    static String benchCategory(EntityPlayerMP player) {
        BenchContext c = LAST_REQUEST.get(player.getUniqueID());
        return c == null ? "" : c.category;
    }

    /**
     * The whole lookup for the pattern in the player's open terminal. Bench runs (/patternrouter bench) skip the log
     * line and don't replace the player's session. Null if no pattern terminal is open.
     */
    static Net.CandidateList buildReply(EntityPlayerMP player, String category, List<ItemStack> nonConsumables,
        String machineName, boolean bench) {
        Net.CandidateList reply = new Net.CandidateList();
        reply.token = nextToken++;
        ContainerPatternTerm term = null;
        ItemStack pattern;
        IGrid grid;
        if (bench) {
            // Replay the player's last real request (the terminal is closed while typing the command).
            BenchContext c = LAST_REQUEST.get(player.getUniqueID());
            if (c == null || c.grid.get() == null) return null;
            pattern = c.pattern;
            grid = c.grid.get();
            category = c.category;
            nonConsumables = c.nonConsumables;
            machineName = c.machineName;
        } else {
            Container open = player.openContainer;
            if (!(open instanceof ContainerPatternTerm)) return null; // terminal already closed, ignore
            term = (ContainerPatternTerm) open;
            pattern = encodedPattern(term);
            grid = gridOf(term);
            if (pattern != null && grid != null) {
                LAST_REQUEST.put(
                    player.getUniqueID(),
                    new BenchContext(pattern.copy(), grid, category, nonConsumables, machineName));
            }
        }
        if (pattern == null) {
            reply.note = NOTE_NO_PATTERN;
        } else if (grid == null) {
            reply.note = NOTE_NO_GRID;
        } else if (!canBuild(grid, player)) {
            reply.note = NOTE_DENIED;
        } else {
            long started = System.nanoTime();
            category = checkedCategory(category, nonConsumables, pattern, player.worldObj);
            List<Scored> scored = score(grid, player, player.worldObj, category, nonConsumables, pattern);
            // Machine not seen (chest, conduit in between) but the interface is named after it: weak match.
            // Only when the machine is unknown: "Precise Assembler" must not pull in interfaces of plain assemblers.
            // Only names the player gave: an unnamed AE2 interface shows its neighbour's name ("Molecular Assembler").
            if (recipeMapOf(category) != null) {
                List<String> words = nameWords(machineName);
                for (Scored s : scored) {
                    if ((s.flags & Net.Entry.RECIPE_MATCH) == 0 && !s.machineSeen
                        && customNamed(s.view)
                        && nameMatches(s.name, words)) {
                        s.flags |= Net.Entry.RECIPE_MATCH | Net.Entry.BY_NAME;
                    }
                }
            }
            // Providers this player chose before for this kind of pattern: offered first, even if no machine is seen.
            String key = choiceKey(category, nonConsumables, storedOutput(pattern, player.worldObj));
            Choices choices = key.isEmpty() ? null : Choices.get();
            if (choices != null) {
                List<int[]> mem = choices.remembered(player.getUniqueID(), key);
                for (Scored s : scored) {
                    int[] p = { s.dim, s.x, s.y, s.z };
                    for (int[] m : mem) if (Choices.same(m, p)) s.flags |= Net.Entry.REMEMBERED;
                }
            }
            // Look for conflicts across the whole network before filtering: the old recipe may be in another machine
            // type.
            List<Conflict> conflicts = new ArrayList<>();
            for (Scored s : scored) {
                for (int i = 0; i < s.sameSlots.size() && conflicts.size() < MAX_CONFLICTS; i++) {
                    int slot = s.sameSlots.get(i);
                    ItemStack old = s.view.getPatterns()
                        .getStackInSlot(slot);
                    if (old == null) continue;
                    conflicts.add(new Conflict(s.view, slot, old));
                    Net.Entry e = s.toEntry();
                    e.flags = Net.Entry.HAS_SAME;
                    reply.conflicts.add(e);
                }
            }
            long ms = (System.nanoTime() - started) / 1_000_000;
            // Debug goes to logs/fml-server-latest.log only; the console gets a line only for slow requests.
            lastProviders = scored.size();
            org.apache.logging.log4j.Level level = bench ? org.apache.logging.log4j.Level.TRACE
                : ms >= SLOW_MS ? org.apache.logging.log4j.Level.WARN : org.apache.logging.log4j.Level.DEBUG;
            PatternRouter.LOG.log(
                level,
                "Pattern upload for {}: category '{}', {} non-consumables, output {}, {} providers checked, {} conflicts, {} ms",
                player.getCommandSenderName(),
                category,
                nonConsumables.size(),
                storedOutput(pattern, player.worldObj),
                scored.size(),
                conflicts.size(),
                ms);
            boolean anyMachine = false;
            for (Scored s : scored) anyMachine |= (s.flags & Net.Entry.RECIPE_MATCH) != 0;
            RecipeMap<?> wanted = recipeMapOf(category);
            // Machine found (via NEI or pattern contents): no note needed.
            boolean anyUnformed = false;
            for (Scored s : scored) anyUnformed |= s.unformed;
            boolean crafting = PatternContents.of(pattern, player.worldObj).craftable;
            if (!anyMachine) reply.note = crafting ? NOTE_NO_ASSEMBLER
                : wanted == null ? NOTE_UNKNOWN_RECIPE : anyUnformed ? NOTE_UNFORMED : NOTE_NO_MACHINE;
            // Show only matching machines if any; otherwise every interface with free space.
            if (anyMachine) scored.removeIf(s -> (s.flags & (Net.Entry.RECIPE_MATCH | Net.Entry.REMEMBERED)) == 0);
            else scored.removeIf(s -> s.free == 0 && (s.flags & Net.Entry.REMEMBERED) == 0);
            // If some machine fits, hide the ones configured for another recipe (wrong circuit/mold/lens).
            // When nothing fits, they stay as "doesn't fit" so the player sees where to put the right item.
            boolean anyFit = false;
            for (Scored s : scored) anyFit |= (s.flags & Net.Entry.MOLD_MATCH) != 0;
            if (anyFit)
                scored.removeIf(s -> (s.flags & Net.Entry.MOLD_MISMATCH) != 0 && (s.flags & Net.Entry.REMEMBERED) == 0);
            scored.sort(ORDER);
            if (scored.size() > MAX_ENTRIES) scored = new ArrayList<>(scored.subList(0, MAX_ENTRIES));

            List<WeakReference<IInterfaceViewable>> refs = new ArrayList<>();
            for (Scored s : scored) {
                refs.add(new WeakReference<>(s.view));
                reply.entries.add(s.toEntry());
            }
            if (!bench)
                SESSIONS.put(player.getUniqueID(), new Session(reply.token, term, pattern, refs, conflicts, key));
        }
        return reply;
    }

    /**
     * The client sends the category of the last NEI transfer, which may belong to another pattern (encoded by hand,
     * or in an earlier session). Keep it only if that recipe map has a recipe from this pattern's inputs (plus the NEI
     * non-consumables) with the same output; otherwise drop it and let the pattern contents decide.
     */
    private static String checkedCategory(String category, List<ItemStack> nonConsumables, ItemStack pattern,
        World world) {
        RecipeMap<?> wanted = recipeMapOf(category);
        if (wanted == null) return category;
        PatternContents contents = PatternContents.of(pattern, world);
        if (contents.isEmpty() || contents.recipeIn(wanted, nonConsumables) != null) return category;
        // Exact lookup can miss recipes whose inputs have alternatives (assembly line: any circuit of a tier).
        // A recipe with the same output in that map is enough to trust the category.
        try {
            for (GTRecipe r : wanted.getAllRecipes()) if (contents.producedBy(r)) return category;
        } catch (Throwable ignored) {}
        PatternRouter.LOG.debug("Ignoring NEI category '{}': the pattern is not a recipe of that machine", category);
        return "";
    }

    /** Words too common in machine names to identify one. */
    private static final Set<String> NAME_STOP = new HashSet<>(
        java.util.Arrays.asList(
            "большой",
            "большая",
            "малый",
            "мега",
            "промышленный",
            "промышленная",
            "продвинутый",
            "машина",
            "large",
            "mega",
            "industrial",
            "advanced",
            "machine",
            "multiblock"));

    /** Significant words of the NEI tab title: letters only, 4+ characters, not common words. */
    private static List<String> nameWords(String title) {
        List<String> out = new ArrayList<>();
        if (title == null) return out;
        for (String w : title.toLowerCase()
            .split("[^\\p{L}]+")) {
            if (w.length() >= 4 && !NAME_STOP.contains(w)) out.add(w);
        }
        return out;
    }

    /**
     * The interface name contains one of the words, compared by the first 5 letters so word endings don't matter
     * ("отвердитель" ~ "Отвердителя", "реактор" ~ "Хим реактор 2").
     */
    private static boolean nameMatches(String name, List<String> words) {
        if (name == null || name.isEmpty() || words.isEmpty()) return false;
        String n = name.toLowerCase();
        for (String w : words) if (n.contains(w.length() > 5 ? w.substring(0, 5) : w)) return true;
        return false;
    }

    /** The player renamed this provider (anvil, name press); otherwise AE2 shows the neighbour block's name. */
    private static boolean customNamed(IInterfaceViewable view) {
        try {
            if (view instanceof appeng.helpers.ICustomNameObject) {
                return ((appeng.helpers.ICustomNameObject) view).hasCustomName();
            }
            TileEntity te = view.getTileEntity();
            if (te instanceof appeng.helpers.ICustomNameObject) {
                return ((appeng.helpers.ICustomNameObject) te).hasCustomName();
            }
        } catch (Throwable ignored) {}
        return false;
    }

    /**
     * Order: remembered -> machine seen -> has space -> mold -> no duplicate -> name. A guess by name stays below every
     * machine that was actually seen, even a full one.
     */
    private static final Comparator<Scored> ORDER = Comparator
        .<Scored>comparingInt(s -> (s.flags & Net.Entry.REMEMBERED) != 0 ? 0 : 1)
        .thenComparingInt(s -> (s.flags & Net.Entry.RECIPE_MATCH) != 0 ? 0 : 1)
        .thenComparingInt(s -> (s.flags & Net.Entry.BY_NAME) != 0 ? 1 : 0)
        .thenComparingInt(s -> s.free > 0 ? 0 : 1)
        .thenComparingInt(
            s -> (s.flags & Net.Entry.MOLD_MATCH) != 0 ? 0 : (s.flags & Net.Entry.MOLD_MISMATCH) != 0 ? 2 : 1)
        // Among fitting machines, one set up exactly for this recipe before one with spare circuits/molds.
        .thenComparingInt(s -> s.loose ? 1 : 0)
        .thenComparingInt(s -> (s.flags & Net.Entry.HAS_SAME) != 0 ? 1 : 0)
        .thenComparing(s -> s.name);

    private static final class Scored {

        IInterfaceViewable view;
        ItemStack icon;
        String name;
        String suffix;
        int free;
        int flags;
        int dim, x, y, z;
        /** Configuration items (deduplicated) for the client-side caption. */
        final List<ItemStack> config = new ArrayList<>();
        /** Feeds a GT hatch that no formed multiblock owns. */
        boolean unformed;
        /** The machine behind this provider is known (its recipe maps were found). */
        boolean machineSeen;
        /** Fits, but also holds circuits/molds this recipe doesn't use (multi-circuit setups). */
        boolean loose;
        /** Slots holding a pattern with the same output. */
        final List<Integer> sameSlots = new ArrayList<>();

        Net.Entry toEntry() {
            Net.Entry e = new Net.Entry();
            e.icon = icon;
            e.name = name;
            e.suffix = suffix;
            e.free = free;
            e.flags = flags;
            e.dim = dim;
            e.x = x;
            e.y = y;
            e.z = z;
            e.config.addAll(config);
            return e;
        }
    }

    private static List<Scored> score(IGrid grid, EntityPlayerMP player, World world, String category,
        List<ItemStack> nonConsumables, ItemStack pattern) {
        RecipeMap<?> wanted = recipeMapOf(category);
        ItemStack output = storedOutput(pattern, world);
        // Pattern inputs/output for the GT recipe lookup (with or without the NEI category).
        PatternContents contents = PatternContents.of(pattern, world);
        List<Scored> out = new ArrayList<>();
        CAN_MAKE.clear();
        SUBNETS.clear();
        Set<IInterfaceViewable> seen = new HashSet<>();
        // The terminal's network plus networks linked by Pattern Repeaters where the player may build.
        List<IGrid> grids = new ArrayList<>();
        for (IGrid g : linkedGrids(grid)) if (g == grid || canBuild(g, player)) grids.add(g);
        for (IGrid g : grids) for (Class<? extends IInterfaceViewable> cls : AEApi.instance()
            .registries()
            .interfaceTerminal()
            .getSupportedClasses()) {
                for (IGridNode node : g.getMachines(cls)) {
                    if (!(node.getMachine() instanceof IInterfaceViewable)) continue;
                    IInterfaceViewable view = p2pInput((IInterfaceViewable) node.getMachine());
                    if (!seen.add(view) || !view.shouldDisplay() || !isActive(view, node)) continue;
                    IInventory inv = view.getPatterns();
                    if (inv == null) continue;
                    try {
                        Scored s = scoreOne(view, inv, wanted, nonConsumables, pattern, output, contents);
                        // Fluid inputs need a provider that can push fluids (CRIB buffer, dual interface).
                        if (!contents.fluids.isEmpty() && !acceptsFluids(view)) {
                            s.flags = (s.flags & ~Net.Entry.MOLD_MATCH) | Net.Entry.MOLD_MISMATCH | Net.Entry.NO_FLUIDS;
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
     * A P2P interface output answers getPatterns()/getName() with its input's slots and name (AE2 does this for the
     * interface terminal), so every output looked like a separate interface placed at the output. Patterns put there
     * land in the input and the input pushes them to the block it faces. List the input instead: once, at its own
     * place, with the machine it feeds. An output without an input stays as it is.
     */
    private static IInterfaceViewable p2pInput(IInterfaceViewable view) {
        try {
            if (view instanceof PartP2PInterface && ((PartP2PInterface) view).isOutput()) {
                PartP2PInterface in = ((PartP2PInterface) view).getInput();
                if (in != null) return in;
            }
        } catch (Throwable ignored) {}
        return view;
    }

    private static boolean isActive(IInterfaceViewable view, IGridNode listed) {
        if (view == listed.getMachine()) return listed.isActive();
        try {
            IGridNode n = ((IGridHost) view).getGridNode(ForgeDirection.UNKNOWN);
            return n != null && n.isActive();
        } catch (Throwable t) {
            return listed.isActive();
        }
    }

    private static Scored scoreOne(IInterfaceViewable view, IInventory inv, RecipeMap<?> wanted,
        List<ItemStack> nonConsumables, ItemStack pattern, ItemStack output, PatternContents contents) {
        Scored s = new Scored();
        s.view = view;
        try {
            appeng.api.util.DimensionalCoord loc = view.getLocation();
            s.dim = loc.getDimension();
            s.x = loc.x;
            s.y = loc.y;
            s.z = loc.z;
        } catch (Throwable ignored) {}
        s.icon = icon(view);
        s.name = rawName(view);
        IChatComponent suffix = nameSuffix(view);
        // Sent as JSON so the client translates it in the player's language.
        // AE2 returns an empty component (not null) for plain interfaces: treat it as "no caption".
        s.suffix = suffix == null || suffix.getUnformattedText()
            .trim()
            .isEmpty() ? "" : IChatComponent.Serializer.func_150696_a(suffix);

        int slots = Math.min(inv.getSizeInventory(), numSlots(view));
        for (int i = 0; i < slots; i++) {
            ItemStack in = inv.getStackInSlot(i);
            if (in == null) {
                if (inv.isItemValidForSlot(i, pattern)) s.free++;
            } else if (output != null && sameItem(
                storedOutput(
                    in,
                    view.getTileEntity() != null ? view.getTileEntity()
                        .getWorldObj() : null),
                output)) {
                    s.flags |= Net.Entry.HAS_SAME;
                    s.sameSlots.add(i);
                }
        }

        if (contents.craftable) {
            // Crafting pattern: AE2 itself knows which providers craft (interface next to a molecular assembler,
            // GT Large Molecular Assembler).
            if (craftingProvider(view)) s.flags |= Net.Entry.RECIPE_MATCH | Net.Entry.MOLD_MATCH;
            return s;
        }
        Collection<RecipeMap<?>> maps = machineRecipeMaps(view);
        if (wanted != null && !maps.contains(wanted)) {
            PatternRouter.LOG.debug(
                "  {} ({}): machine {}, maps {}, wanted {}",
                s.name,
                view.getClass()
                    .getSimpleName(),
                describeMachine(view),
                mapNames(maps),
                wanted.unlocalizedName);
        }
        if (maps.isEmpty()) {
            s.unformed = hasUnownedHatch(view);
            return s;
        }
        s.machineSeen = true;
        boolean known = !contents.isEmpty();
        Held held = machineHeld(view, maps);
        if (PatternRouter.LOG.isDebugEnabled()) {
            List<String> cfg = new ArrayList<>();
            for (ItemStack st : held.config) cfg.add(st.getUnlocalizedName() + "@" + st.getItemDamage());
            PatternRouter.LOG.debug(
                "  {}: machine {}, maps {}, held {} items, config {}",
                s.name,
                describeMachine(view),
                mapNames(maps),
                held.all.size(),
                cfg);
        }
        // AE2 captions only CRIBs; for other providers show what the machine is set up with ([5], mold, lens).
        if (s.suffix.isEmpty()) s.config.addAll(uniqueConfig(held.config));
        if (wanted != null) {
            if (!maps.contains(wanted)) return s;
            s.flags |= Net.Entry.RECIPE_MATCH;
            int fit = known ? fitAny(wanted, contents, held) : FIT_NONE;
            if (fit == FIT_OK || fit == FIT_LOOSE) {
                s.flags |= Net.Entry.MOLD_MATCH;
                s.loose = fit == FIT_LOOSE;
            } else if (fit == FIT_EXTRA) s.flags |= Net.Entry.MOLD_MISMATCH;
            else if (!nonConsumables.isEmpty()) {
                // GT lookup failed (e.g. odd pattern amounts): fall back to the items NEI marked as not consumed.
                boolean all = true;
                for (ItemStack need : nonConsumables) {
                    boolean found = false;
                    for (ItemStack h : held.all) found |= matches(need, h);
                    all &= found;
                }
                s.flags |= all ? Net.Entry.MOLD_MATCH : Net.Entry.MOLD_MISMATCH;
            }
        } else if (known) {
            // No category: the GT lookup alone decides which machine fits.
            for (RecipeMap<?> map : maps) {
                if (!canMake(map, contents)) continue;
                int fit = fitAny(map, contents, held);
                if (fit == FIT_OK || fit == FIT_LOOSE) {
                    s.flags = (s.flags & ~Net.Entry.MOLD_MISMATCH) | Net.Entry.RECIPE_MATCH | Net.Entry.MOLD_MATCH;
                    s.loose = fit == FIT_LOOSE;
                    if (fit == FIT_OK) break;
                    continue;
                }
                if ((s.flags & Net.Entry.MOLD_MATCH) != 0) continue; // a loose fit in another map beats a mismatch
                if (fit == FIT_EXTRA) s.flags |= Net.Entry.RECIPE_MATCH | Net.Entry.MOLD_MISMATCH;
            }
        }
        return s;
    }

    /**
     * Caption like AE2's CRIB one: "[5]" for a programmed circuit, item names for molds/lenses. Sent as JSON so the
     * client translates names in the player's language.
     */
    private static String configCaption(List<ItemStack> config) {
        IChatComponent c = new ChatComponentText("{");
        boolean first = true;
        Set<String> seen = new HashSet<>();
        for (ItemStack st : config) {
            String key = Item.getIdFromItem(st.getItem()) + ":" + st.getItemDamage();
            if (!seen.add(key)) continue;
            if (!first) c.appendText(", ");
            first = false;
            if (isCircuit(st)) c.appendText("[" + st.getItemDamage() + "]");
            else c.appendSibling(new ChatComponentTranslation(st.getUnlocalizedName() + ".name"));
        }
        c.appendText("}");
        return IChatComponent.Serializer.func_150696_a(c);
    }

    private static List<ItemStack> uniqueConfig(List<ItemStack> config) {
        List<ItemStack> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (ItemStack st : config) {
            if (st != null && st.getItem() != null
                && seen.add(Item.getIdFromItem(st.getItem()) + ":" + st.getItemDamage())) {
                ItemStack one = st.copy();
                one.stackSize = 1;
                out.add(one);
            }
        }
        return out;
    }

    private static boolean isCircuit(ItemStack st) {
        try {
            return ItemList.Circuit_Integrated.getItem() == st.getItem();
        } catch (Throwable t) {
            return false;
        }
    }

    /** Per request: does this recipe map have any recipe with the pattern's output (one scan per map). */
    private static final Map<RecipeMap<?>, Boolean> CAN_MAKE = new HashMap<>();

    private static boolean canMake(RecipeMap<?> map, PatternContents contents) {
        return CAN_MAKE.computeIfAbsent(map, m -> {
            try {
                Collection<GTRecipe> all = m.getAllRecipes();
                // Furnace, microwave and other non-GT maps keep no recipe list: they build a recipe on request
                // (vanilla smelting), so only the lookup itself can tell. Without this the Multi Smelter never fits.
                if (all.isEmpty()) return true;
                for (GTRecipe r : all) if (contents.producedBy(r)) return true;
            } catch (Throwable ignored) {}
            return false;
        });
    }

    private static final int FIT_NONE = 0; // GT finds no recipe with this output
    private static final int FIT_OK = 1; // recipe found, everything the machine is configured with is needed
    private static final int FIT_EXTRA = 2; // recipe found, but the machine's contents also start another recipe
    private static final int FIT_LOOSE = 3; // recipe found, spare circuits/molds in the machine change nothing

    /**
     * Runs the same GT recipe lookup the machine would: pattern inputs plus everything the machine holds.
     * <ul>
     * <li>no recipe with the pattern's output: doesn't fit;</li>
     * <li>these contents also start a recipe with another output (a spare circuit or mold selects it): doesn't fit,
     * GT may run that one instead;</li>
     * <li>otherwise it fits; if some circuit/mold/lens can be removed and the recipe is still found, the machine is
     * set up for more recipes than this one (several circuits in one bus) - still fine, listed after exact ones.</li>
     * </ul>
     */
    private static int fit(RecipeMap<?> map, PatternContents contents, Held held) {
        if (contents.recipeIn(map, held.all) == null) return FIT_NONE;
        if (contents.startsOtherRecipe(map, held.all)) return FIT_EXTRA;
        for (ItemStack x : held.config) {
            List<ItemStack> without = new ArrayList<>();
            for (ItemStack h : held.all) if (!sameItem(h, x)) without.add(h);
            if (contents.recipeIn(map, without) != null) return FIT_LOOSE;
        }
        return FIT_OK;
    }

    /** Pattern inputs and main output in a form GT recipe lookup understands. */
    private static final class PatternContents {

        final List<ItemStack> items = new ArrayList<>();
        final List<FluidStack> fluids = new ArrayList<>();
        ItemStack outItem;
        FluidStack outFluid;
        /** Crafting (workbench) pattern, run by molecular assemblers. */
        boolean craftable;

        /** The terminal's pattern is decoded several times per request; decoding a crafting pattern is slow. */
        private static ItemStack lastPattern;
        private static PatternContents lastContents;

        static PatternContents of(ItemStack pattern, World world) {
            if (pattern != null && pattern == lastPattern && lastContents != null) return lastContents;
            PatternContents pc = new PatternContents();
            lastPattern = pattern;
            lastContents = pc;
            if (pattern == null || !(pattern.getItem() instanceof ICraftingPatternItem) || world == null) return pc;
            try {
                ICraftingPatternDetails d = ((ICraftingPatternItem) pattern.getItem())
                    .getPatternForItem(pattern, world);
                if (d == null) return pc;
                pc.craftable = d.isCraftable();
                for (IAEStack<?> st : d.getCondensedAEInputs()) {
                    if (st instanceof IAEItemStack) pc.items.add(((IAEItemStack) st).getItemStack());
                    else if (st instanceof IAEFluidStack) pc.fluids.add(((IAEFluidStack) st).getFluidStack());
                }
                IAEStack<?>[] outs = d.getCondensedAEOutputs();
                if (outs != null && outs.length > 0) {
                    if (outs[0] instanceof IAEItemStack) pc.outItem = ((IAEItemStack) outs[0]).getItemStack();
                    else if (outs[0] instanceof IAEFluidStack) pc.outFluid = ((IAEFluidStack) outs[0]).getFluidStack();
                }
            } catch (Throwable t) {
                PatternRouter.LOG.debug("Cannot decode pattern: {}", t.toString());
            }
            return pc;
        }

        boolean isEmpty() {
            return (items.isEmpty() && fluids.isEmpty()) || (outItem == null && outFluid == null);
        }

        /** A recipe in the map made from these inputs (plus machine contents) with the same output, or null. */
        GTRecipe recipeIn(RecipeMap<?> map, List<ItemStack> held) {
            List<ItemStack> in = new ArrayList<>(items);
            in.addAll(held);
            try {
                return map.findRecipeQuery()
                    .items(in.toArray(new ItemStack[0]))
                    .fluids(fluids.toArray(new FluidStack[0]))
                    .dontCheckStackSizes(true)
                    .findAll()
                    .filter(this::producedInProportion)
                    .findFirst()
                    .orElse(null);
            } catch (Throwable t) {
                return null;
            }
        }

        /** Some recipe with another output also matches these inputs plus machine contents. */
        boolean startsOtherRecipe(RecipeMap<?> map, List<ItemStack> held) {
            List<ItemStack> in = new ArrayList<>(items);
            in.addAll(held);
            try {
                return map.findRecipeQuery()
                    .items(in.toArray(new ItemStack[0]))
                    .fluids(fluids.toArray(new FluidStack[0]))
                    .dontCheckStackSizes(true)
                    .findAll()
                    .anyMatch(r -> !producedBy(r));
            } catch (Throwable t) {
                return false;
            }
        }

        boolean producedBy(GTRecipe r) {
            if (outItem != null && r.mOutputs != null) {
                for (ItemStack o : r.mOutputs) if (o != null && o.isItemEqual(outItem)) return true;
            }
            if (outFluid != null && r.mFluidOutputs != null) {
                for (FluidStack o : r.mFluidOutputs) if (o != null && o.isFluidEqual(outFluid)) return true;
            }
            return false;
        }

        /**
         * Same output and the same proportions: the pattern must be k times the recipe (k = pattern output / recipe
         * output), so "1 ingot -> 2 rods" fits the extruder (1 -> 2) but not the lathe (1 -> 1 rod + dust). Inputs
         * the recipe lists differently (ore dictionary variants) are not compared.
         */
        boolean producedInProportion(GTRecipe r) {
            if (!producedBy(r)) return false;
            if (!usesAllInputs(r)) return false;
            long k = multiplier(r);
            if (k <= 0) return k == 0; // 0: nothing to compare against, accept
            if (r.mInputs != null) {
                for (ItemStack need : r.mInputs) {
                    if (need == null || need.stackSize <= 0) continue; // non-consumed (circuit, mold, lens)
                    for (ItemStack have : items) {
                        if (have != null && have.isItemEqual(need)) {
                            if (have.stackSize != k * need.stackSize) return false;
                            break;
                        }
                    }
                }
            }
            if (r.mFluidInputs != null) {
                for (FluidStack need : r.mFluidInputs) {
                    if (need == null || need.amount <= 0) continue;
                    for (FluidStack have : fluids) {
                        if (have != null && have.isFluidEqual(need)) {
                            if (have.amount != k * need.amount) return false;
                            break;
                        }
                    }
                }
            }
            return true;
        }

        /**
         * Every pattern input is something the recipe takes. EBF "dust + circuit 1" and "dust + circuit 11 + 100 L
         * oganesson" make the same ingot: a machine set to [1] would run the first one and the oganesson from the
         * pattern would pile up in its hatch.
         */
        boolean usesAllInputs(GTRecipe r) {
            for (FluidStack have : fluids) {
                if (have == null) continue;
                boolean used = false;
                if (r.mFluidInputs != null) {
                    for (FluidStack need : r.mFluidInputs) used |= need != null && need.isFluidEqual(have);
                }
                if (!used) return false;
            }
            for (ItemStack have : items) {
                if (have == null) continue;
                boolean used = false;
                if (r.mInputs != null) {
                    for (ItemStack need : r.mInputs) {
                        if (need != null && matches(need, have)) {
                            used = true;
                            break;
                        }
                    }
                }
                if (!used) return false;
            }
            return true;
        }

        /** Pattern output / recipe output for the main output; 0 if unknown, -1 if not a whole multiple. */
        private long multiplier(GTRecipe r) {
            long pattern = 0, recipe = 0;
            if (outItem != null && r.mOutputs != null) {
                pattern = outItem.stackSize;
                for (ItemStack o : r.mOutputs) if (o != null && o.isItemEqual(outItem)) {
                    recipe = o.stackSize;
                    break;
                }
            } else if (outFluid != null && r.mFluidOutputs != null) {
                pattern = outFluid.amount;
                for (FluidStack o : r.mFluidOutputs) if (o != null && o.isFluidEqual(outFluid)) {
                    recipe = o.amount;
                    break;
                }
            }
            if (pattern <= 0 || recipe <= 0) return 0;
            return pattern % recipe == 0 ? pattern / recipe : -1;
        }
    }

    // ------------------------------------------------------------------ extracting the old pattern

    public static void handleExtract(EntityPlayerMP player, int token, int index) {
        Session session = SESSIONS.get(player.getUniqueID());
        Container open = player.openContainer;
        if (session == null || session.token != token
            || session.container.get() != open
            || !(open instanceof ContainerPatternTerm)
            || index < 0
            || index >= session.conflicts.size()) {
            Net.CH.sendTo(new Net.Result(Net.Result.GONE, ""), player);
            return;
        }
        Conflict c = session.conflicts.get(index);
        IInterfaceViewable view = c.view.get();
        IGrid grid = gridOf((ContainerPatternTerm) open);
        if (view == null || grid == null || !sameGrid(view, grid)) {
            Net.CH.sendTo(new Net.Result(Net.Result.GONE, ""), player);
            return;
        }
        if (!canBuild(grid, player) || !canBuildAt(view, player)) {
            Net.CH.sendTo(new Net.Result(Net.Result.DENIED, rawName(view)), player);
            return;
        }
        IInventory inv = view.getPatterns();
        ItemStack now = c.slot < inv.getSizeInventory() ? inv.getStackInSlot(c.slot) : null;
        // The slot must still hold exactly the pattern we listed; otherwise someone changed it.
        if (now == null || !ItemStack.areItemStacksEqual(now, c.pattern)) {
            Net.CH.sendTo(new Net.Result(Net.Result.GONE, rawName(view)), player);
            return;
        }
        ItemStack taken = now.copy();
        inv.setInventorySlotContents(c.slot, null);
        inv.markDirty();
        if (!player.inventory.addItemStackToInventory(taken)) {
            inv.setInventorySlotContents(c.slot, now); // inventory full: put it back
            inv.markDirty();
            Net.CH.sendTo(new Net.Result(Net.Result.INV_FULL, rawName(view)), player);
            return;
        }
        player.inventoryContainer.detectAndSendChanges();
        open.detectAndSendChanges();
        Net.CH.sendTo(new Net.Result(Net.Result.EXTRACTED, rawName(view)), player);
    }

    // ------------------------------------------------------------------ upload

    public static void handleUpload(EntityPlayerMP player, int token, int index) {
        Session session = SESSIONS.get(player.getUniqueID());
        Container open = player.openContainer;
        if (session == null || session.token != token
            || session.container.get() != open
            || !(open instanceof ContainerPatternTerm)
            || index < 0
            || index >= session.targets.size()) {
            Net.CH.sendTo(new Net.Result(Net.Result.GONE, ""), player);
            return;
        }
        ContainerPatternTerm term = (ContainerPatternTerm) open;
        IInterfaceViewable view = session.targets.get(index)
            .get();
        IGrid grid = gridOf(term);
        if (view == null || grid == null || !sameGrid(view, grid)) {
            Net.CH.sendTo(new Net.Result(Net.Result.GONE, ""), player);
            return;
        }
        if (!canBuild(grid, player) || !canBuildAt(view, player)) {
            Net.CH.sendTo(new Net.Result(Net.Result.DENIED, rawName(view)), player);
            return;
        }
        IInventory patternInv = term.getPatternTerminal()
            .getInventoryByName(StorageName.CRAFTING_PATTERN.getName());
        ItemStack pattern = patternInv == null ? null : patternInv.getStackInSlot(1);
        if (pattern == null || !(pattern.getItem() instanceof ItemEncodedPattern)) {
            Net.CH.sendTo(new Net.Result(Net.Result.NO_PATTERN, rawName(view)), player);
            return;
        }
        // The list was made for another pattern (re-encoded since, or changed by another player at this terminal).
        if (!sameItem(pattern, session.pattern)) {
            Net.CH.sendTo(new Net.Result(Net.Result.GONE, ""), player);
            return;
        }
        int slot = freeSlot(view, pattern);
        boolean cardAdded = false;
        if (slot < 0) {
            // Full: put a Pattern Capacity Card in (player inventory first, then the network).
            int card = addCapacityCard(view, grid, term, player);
            if (card != CARD_ADDED) {
                Net.CH.sendTo(new Net.Result(card, rawName(view)), player);
                return;
            }
            cardAdded = true;
            slot = freeSlot(view, pattern);
            if (slot < 0) {
                Net.CH.sendTo(new Net.Result(Net.Result.FULL, rawName(view)), player);
                return;
            }
        }
        IInventory target = view.getPatterns();
        ItemStack one = pattern.copy();
        one.stackSize = 1;
        target.setInventorySlotContents(slot, one);
        target.markDirty();
        pattern.stackSize--;
        patternInv.setInventorySlotContents(1, pattern.stackSize > 0 ? pattern : null);
        term.detectAndSendChanges();
        rememberChoice(player, session.key, view);
        Net.CH.sendTo(new Net.Result(cardAdded ? Net.Result.OK_CARD : Net.Result.OK, rawName(view)), player);
        PatternRouter.LOG.debug("{} uploaded a pattern to {}", player.getCommandSenderName(), rawName(view));
    }

    private static int freeSlot(IInterfaceViewable view, ItemStack pattern) {
        IInventory target = view.getPatterns();
        if (target == null) return -1;
        int slots = Math.min(target.getSizeInventory(), numSlots(view));
        for (int i = 0; i < slots; i++) {
            if (target.getStackInSlot(i) == null && target.isItemValidForSlot(i, pattern)) return i;
        }
        return -1;
    }

    // ------------------------------------------------------------------ pattern capacity card

    private static final int CARD_ADDED = -1;

    /** Installs one Pattern Capacity Card into an AE2 interface; returns CARD_ADDED or a Net.Result code. */
    private static int addCapacityCard(IInterfaceViewable view, IGrid grid, ContainerPatternTerm term,
        EntityPlayerMP player) {
        if (!(view instanceof IInterfaceHost)) return Net.Result.FULL; // CRIB, LMA: no upgrade slots
        try {
            IInventory upgrades = ((IInterfaceHost) view).getInterfaceDuality()
                .getInventoryByName("upgrades");
            ItemStack card = AEApi.instance()
                .definitions()
                .materials()
                .cardPatternCapacity()
                .maybeStack(1)
                .orNull();
            if (upgrades == null || card == null) return Net.Result.FULL;
            int slot = -1;
            for (int i = 0; i < upgrades.getSizeInventory() && slot < 0; i++) {
                // AE2's upgrade inventory refuses cards beyond the per-type maximum.
                if (upgrades.getStackInSlot(i) == null && upgrades.isItemValidForSlot(i, card)) slot = i;
            }
            if (slot < 0) return Net.Result.MAX_CARDS;
            if (!takeFromPlayer(player, card) && !takeFromNetwork(grid, term, player, card)) {
                return Net.Result.NO_CARD;
            }
            upgrades.setInventorySlotContents(slot, card.copy());
            upgrades.markDirty();
            return CARD_ADDED;
        } catch (Throwable t) {
            PatternRouter.LOG.debug("Cannot add a capacity card to {}: {}", rawName(view), t.toString());
            return Net.Result.FULL;
        }
    }

    private static boolean takeFromPlayer(EntityPlayerMP player, ItemStack card) {
        ItemStack[] inv = player.inventory.mainInventory;
        for (int i = 0; i < inv.length; i++) {
            if (inv[i] != null && inv[i].isItemEqual(card) && ItemStack.areItemStackTagsEqual(inv[i], card)) {
                player.inventory.decrStackSize(i, 1);
                player.inventory.markDirty();
                player.inventoryContainer.detectAndSendChanges();
                return true;
            }
        }
        return false;
    }

    private static boolean takeFromNetwork(IGrid grid, ContainerPatternTerm term, EntityPlayerMP player,
        ItemStack card) {
        ISecurityGrid security = grid.getCache(ISecurityGrid.class);
        if (security != null && !security.hasPermission(player, SecurityPermissions.EXTRACT)) return false;
        IStorageGrid storage = grid.getCache(IStorageGrid.class);
        if (storage == null) return false;
        Object target = term.getTarget();
        IActionHost host = target instanceof IActionHost ? (IActionHost) target : null;
        IAEItemStack got = storage.getItemInventory()
            .extractItems(AEItemStack.create(card), Actionable.MODULATE, new PlayerSource(player, host));
        return got != null && got.getStackSize() >= 1;
    }

    // ------------------------------------------------------------------ remembered choices

    /** What the player is placing: recipe category plus its circuit/mold/lens, or the output if no category. */
    private static String choiceKey(String category, List<ItemStack> nonConsumables, ItemStack output) {
        if (category != null && !category.isEmpty()) {
            List<String> nc = new ArrayList<>();
            for (ItemStack s : nonConsumables) {
                if (s != null && s.getItem() != null) nc.add(Item.getIdFromItem(s.getItem()) + ":" + s.getItemDamage());
            }
            Collections.sort(nc);
            return "cat:" + category + "|" + String.join(",", nc);
        }
        return output == null || output.getItem() == null ? ""
            : "out:" + Item.itemRegistry.getNameForObject(output.getItem()) + ":" + output.getItemDamage();
    }

    private static int[] position(IInterfaceViewable view) {
        try {
            DimensionalCoord c = view.getLocation();
            return new int[] { c.getDimension(), c.x, c.y, c.z };
        } catch (Throwable t) {
            return null;
        }
    }

    private static void rememberChoice(EntityPlayerMP player, String key, IInterfaceViewable view) {
        int[] pos = position(view);
        Choices c = key == null || key.isEmpty() || pos == null ? null : Choices.get();
        if (c != null) c.remember(player.getUniqueID(), key, pos);
    }

    // ------------------------------------------------------------------ helpers

    private static ItemStack encodedPattern(ContainerPatternTerm term) {
        IInventory inv = term.getPatternTerminal()
            .getInventoryByName(StorageName.CRAFTING_PATTERN.getName());
        if (inv == null || inv.getSizeInventory() < 2) return null;
        ItemStack s = inv.getStackInSlot(1);
        return s != null && s.getItem() instanceof ItemEncodedPattern ? s : null;
    }

    /**
     * Main pattern output, decoded in the server world: ItemEncodedPattern.getOutput() uses the AE2 client proxy
     * world and returns null on a server.
     */
    private static ItemStack patternOutput(ItemStack pattern, World world) {
        if (pattern == null || !(pattern.getItem() instanceof ICraftingPatternItem) || world == null) return null;
        try {
            ICraftingPatternDetails d = ((ICraftingPatternItem) pattern.getItem()).getPatternForItem(pattern, world);
            if (d == null) return null;
            IAEStack<?>[] outs = d.getCondensedAEOutputs();
            if (outs == null || outs.length == 0 || outs[0] == null) return null;
            return outs[0].getItemStackForNEI();
        } catch (Throwable t) {
            return null;
        }
    }

    /** Decoded outputs of patterns already seen; ItemStack has identity equality, entries go with the stacks. */
    private static final Map<ItemStack, ItemStack> DECODED = new WeakHashMap<>();

    /**
     * Output of a pattern sitting in some interface, for conflict search over the whole network. Reads the first
     * "out" entry of the pattern NBT directly: decoding a crafting pattern makes AE2 search all workbench recipes,
     * which over thousands of patterns froze big servers for seconds. Full decoding (cached) only as a fallback.
     */
    private static ItemStack storedOutput(ItemStack pattern, World world) {
        if (pattern == null) return null;
        NBTTagCompound tag = pattern.getTagCompound();
        if (tag != null) {
            NBTTagList outs = tag.getTagList("out", 10);
            for (int i = 0; i < outs.tagCount(); i++) {
                NBTTagCompound t = outs.getCompoundTagAt(i);
                if (t == null || t.hasNoTags()) continue;
                try {
                    ItemStack s = ItemStack.loadItemStackFromNBT(t);
                    if (s != null && s.getItem() != null) return s;
                } catch (Throwable ignored) {}
                break; // first output is not a plain item (fluid, new format): decode instead
            }
        }
        if (DECODED.containsKey(pattern)) return DECODED.get(pattern);
        ItemStack out = patternOutput(pattern, world);
        DECODED.put(pattern, out);
        return out;
    }

    private static IGrid gridOf(ContainerPatternTerm term) {
        Object target = term.getTarget();
        IGridNode node = null;
        if (target instanceof IActionHost) node = ((IActionHost) target).getActionableNode();
        if (node == null && target instanceof IGridHost)
            node = ((IGridHost) target).getGridNode(ForgeDirection.UNKNOWN);
        return node == null ? null : node.getGrid();
    }

    /**
     * Can this provider push fluids? GT CRIBs say so directly (the Crafting Input Bus can't); AE2 providers list
     * their stack types (a plain interface: items only). Unknown builds: assume yes, never hide wrongly.
     */
    private static boolean acceptsFluids(IInterfaceViewable view) {
        TileEntity te = view.getTileEntity();
        if (te instanceof IGregTechTileEntity) {
            IMetaTileEntity mte = ((IGregTechTileEntity) te).getMetaTileEntity();
            if (mte instanceof MTEHatchCraftingInputME) return ((MTEHatchCraftingInputME) mte).supportsFluids();
        }
        try {
            for (appeng.api.storage.data.IAEStackType<?> t : view.getSupportedStackTypes()) {
                if (t != null && t.getId() != null
                    && t.getId()
                        .toLowerCase()
                        .contains("fluid"))
                    return true;
            }
            // Fluid-capable providers from add-ons that don't report types (dual interfaces).
            return view.getClass()
                .getName()
                .matches("(?i).*(fluid|dual).*");
        } catch (Throwable t) {
            return true;
        }
    }

    /** Repeaters.reachable(), or just the grid on AE2 builds without Pattern Repeater (class missing). */
    private static List<IGrid> linkedGrids(IGrid grid) {
        try {
            return Repeaters.reachable(grid);
        } catch (Throwable t) {
            return Collections.singletonList(grid);
        }
    }

    /** The provider is in the terminal's network or one linked to it by Pattern Repeaters. */
    private static boolean sameGrid(IInterfaceViewable view, IGrid grid) {
        IGridNode node = view.getGridNode(ForgeDirection.UNKNOWN);
        if (node == null || node.getGrid() == null) return false;
        return node.getGrid() == grid || linkedGrids(grid).contains(node.getGrid());
    }

    /** Build permission in the provider's own network (it may be behind a Pattern Repeater). */
    private static boolean canBuildAt(IInterfaceViewable view, EntityPlayerMP player) {
        IGridNode node = view.getGridNode(ForgeDirection.UNKNOWN);
        return node != null && node.getGrid() != null && canBuild(node.getGrid(), player);
    }

    private static boolean canBuild(IGrid grid, EntityPlayerMP player) {
        ISecurityGrid security = grid.getCache(ISecurityGrid.class);
        return security == null || security.hasPermission(player, SecurityPermissions.BUILD);
    }

    /** GregTech recipe map for an NEI category id. */
    public static RecipeMap<?> recipeMapOf(String category) {
        if (category == null || category.isEmpty()) return null;
        for (RecipeCategory c : RecipeCategory.ALL_RECIPE_CATEGORIES.values()) {
            if (category.equals(c.unlocalizedName)) return c.recipeMap;
        }
        return null;
    }

    /** Recipe maps of the machine this interface/CRIB feeds. */
    private static Collection<RecipeMap<?>> machineRecipeMaps(IInterfaceViewable view) {
        Set<RecipeMap<?>> maps = new HashSet<>();
        TileEntity self = view.getTileEntity();
        if (self == null) return maps;
        // CRIB and other GT hatches: the machine is the multiblock controller.
        maps.addAll(recipeMapsOf(self, 0));
        // AE2 ME interface: the machine is the neighbour it outputs into.
        if (view instanceof IInterfaceHost) {
            for (TileEntity n : targets(view)) maps.addAll(recipeMapsOf(n, 0));
        }
        return maps;
    }

    // ------------------------------------------------------------------ AE2 compatibility
    // Default methods of IInterfaceViewable added in newer AE2 GTNH builds; older ones (GTNH 2.9.0-RC-1) lack some.

    private static String rawName(IInterfaceViewable view) {
        String n;
        try {
            n = view.getRawName();
        } catch (Throwable t) {
            n = view.getName();
        }
        return n == null ? "" : n;
    }

    private static IChatComponent nameSuffix(IInterfaceViewable view) {
        try {
            return view.getNameSuffix();
        } catch (Throwable t) {
            return null;
        }
    }

    private static ItemStack icon(IInterfaceViewable view) {
        try {
            ItemStack d = view.getDisplayRep();
            return d != null ? d : view.getSelfRep();
        } catch (Throwable t) {
            return null;
        }
    }

    private static int numSlots(IInterfaceViewable view) {
        try {
            return view.numSlots();
        } catch (Throwable t) {
            return view.rows() * view.rowSize();
        }
    }

    private static boolean craftingProvider(IInterfaceViewable view) {
        try {
            return view.isCraftingPatternProvider();
        } catch (Throwable t) {
            return false;
        }
    }

    /** A CRIB, or a bus next to an ME interface, that GT has not attached to a formed multiblock. */
    private static boolean hasUnownedHatch(IInterfaceViewable view) {
        TileEntity self = view.getTileEntity();
        List<TileEntity> tes = new ArrayList<>();
        tes.add(self);
        if (view instanceof IInterfaceHost) tes.addAll(targets(view));
        for (TileEntity te : tes) {
            if (!(te instanceof IGregTechTileEntity)) continue;
            IMetaTileEntity mte = ((IGregTechTileEntity) te).getMetaTileEntity();
            if (mte instanceof MTEHatchInputBus && Owners.ownerOf(mte, te.getWorldObj()) == null) {
                return true;
            }
        }
        return false;
    }

    /** Diagnostics: what the interface/hatch points at. */
    private static String describeMachine(IInterfaceViewable view) {
        StringBuilder b = new StringBuilder();
        TileEntity self = view.getTileEntity();
        describe(b, self, 0);
        if (view instanceof IInterfaceHost) {
            for (TileEntity n : targets(view)) {
                b.append(" | side ");
                describe(b, n, 0);
            }
        }
        return b.toString();
    }

    private static void describe(StringBuilder b, TileEntity te, int depth) {
        if (!(te instanceof IGregTechTileEntity)) {
            b.append(
                te == null ? "null"
                    : te.getClass()
                        .getSimpleName());
            return;
        }
        IMetaTileEntity mte = ((IGregTechTileEntity) te).getMetaTileEntity();
        b.append(
            mte == null ? "noMTE"
                : mte.getClass()
                    .getSimpleName());
        if (mte instanceof RecipeMapWorkable) {
            b.append(" maps=")
                .append(mapNames(((RecipeMapWorkable) mte).getAvailableRecipeMaps()));
        }
        if (mte instanceof MTEMultiBlockBase) {
            b.append(" separation=")
                .append(((MTEMultiBlockBase) mte).isInputSeparationEnabled());
        }
        if (mte instanceof MTEHatch && depth == 0) {
            ChunkCoordinates owner = Owners.ownerOf(mte, te.getWorldObj());
            b.append(" owner=")
                .append(owner == null ? "null" : owner.posX + "," + owner.posY + "," + owner.posZ);
            World w = te.getWorldObj();
            if (owner != null && w != null && w.blockExists(owner.posX, owner.posY, owner.posZ)) {
                b.append(" -> ");
                describe(b, w.getTileEntity(owner.posX, owner.posY, owner.posZ), 1);
            }
        }
    }

    private static String mapNames(Collection<RecipeMap<?>> maps) {
        List<String> names = new ArrayList<>();
        if (maps != null) for (RecipeMap<?> m : maps) names.add(m == null ? "null" : m.unlocalizedName);
        return names.toString();
    }

    private static Collection<RecipeMap<?>> recipeMapsOf(TileEntity te, int depth) {
        if (!(te instanceof IGregTechTileEntity)) return Collections.emptyList();
        IMetaTileEntity mte = ((IGregTechTileEntity) te).getMetaTileEntity();
        if (mte instanceof RecipeMapWorkable) {
            Collection<RecipeMap<?>> c = ((RecipeMapWorkable) mte).getAvailableRecipeMaps();
            if (!c.isEmpty()) return c;
        }
        if (mte instanceof MTEHatch && depth == 0) {
            ChunkCoordinates owner = Owners.ownerOf(mte, te.getWorldObj());
            World w = te.getWorldObj();
            if (owner != null && w != null && w.blockExists(owner.posX, owner.posY, owner.posZ)) {
                return recipeMapsOf(w.getTileEntity(owner.posX, owner.posY, owner.posZ), 1);
            }
        }
        return Collections.emptyList();
    }

    /** What the machine holds besides the pattern inputs. */
    private static final class Held {

        /** Everything that takes part in the machine's recipe lookup. */
        final List<ItemStack> all = new ArrayList<>();
        /** Configuration items among them: circuits, molds, lenses. */
        final List<ItemStack> config = new ArrayList<>();
        /**
         * Multiblock with input separation fed only through fluid hatches: items may land in any of its buses, so
         * each bus is a separate possibility (GT runs each bus with all fluids). Null otherwise.
         */
        List<Held> perBus;
    }

    private static Held machineHeld(IInterfaceViewable view, Collection<RecipeMap<?>> maps) {
        Held held = new Held();
        TileEntity self = view.getTileEntity();
        if (self instanceof IGregTechTileEntity) {
            IMetaTileEntity mte = ((IGregTechTileEntity) self).getMetaTileEntity();
            if (mte instanceof MTEHatchCraftingInputME) {
                // CRIB circuit + manual slots: shared by every pattern, all of it is configuration.
                for (ItemStack s : ((MTEHatchCraftingInputME) mte).getSharedItems()) {
                    if (s == null) continue;
                    held.all.add(s);
                    held.config.add(s);
                }
            }
        }
        if (view instanceof IInterfaceHost) {
            Set<Object> addedBuses = Collections.newSetFromMap(new java.util.IdentityHashMap<>());
            boolean touchesItemInput = false;
            List<MTEMultiBlockBase> separated = new ArrayList<>();
            for (TileEntity n : targets(view)) {
                if (!(n instanceof IGregTechTileEntity)) continue;
                IMetaTileEntity mte = ((IGregTechTileEntity) n).getMetaTileEntity();
                if (mte instanceof MTEBasicMachine || mte instanceof MTEHatchInputBus) {
                    touchesItemInput = true;
                    if (addedBuses.add(mte)) addInventory(held, mte, maps);
                } else if (mte instanceof MTEHatchInput && addedBuses.add(mte)) {
                    // Fluid hatches may hold configuration too: GT++ Solidifier Hatch has a mold slot and a circuit.
                    addInventory(held, mte, maps);
                }
                // Multiblock without input separation: GT combines all its input buses (circuit, mold, lens may be
                // in another one than the hatch the interface touches, e.g. an interface on a fluid input hatch).
                if (mte instanceof MTEHatch && !(mte instanceof MTEHatchCraftingInputME)) {
                    ChunkCoordinates owner = Owners.ownerOf(mte, n.getWorldObj());
                    World w = n.getWorldObj();
                    if (owner == null || w == null || !w.blockExists(owner.posX, owner.posY, owner.posZ)) continue;
                    TileEntity c = w.getTileEntity(owner.posX, owner.posY, owner.posZ);
                    if (!(c instanceof IGregTechTileEntity)) continue;
                    IMetaTileEntity cm = ((IGregTechTileEntity) c).getMetaTileEntity();
                    if (!(cm instanceof MTEMultiBlockBase)) continue;
                    MTEMultiBlockBase multi = (MTEMultiBlockBase) cm;
                    // GT also takes a programmed circuit from the controller's own slot (getStoredInputs).
                    ItemStack ctrlCircuit = multi.getStackInSlot(1);
                    if (ctrlCircuit != null && isCircuit(ctrlCircuit) && addedBuses.add(multi)) {
                        held.all.add(ctrlCircuit);
                        held.config.add(ctrlCircuit);
                    }
                    if (multi.mInputBusses == null) continue;
                    if (multi.isInputSeparationEnabled()) {
                        if (!separated.contains(multi)) separated.add(multi);
                        continue;
                    }
                    for (MTEHatchInputBus bus : multi.mInputBusses) {
                        // Same rule as MTEMultiBlockBase.getStoredInputs(): a CRIB is listed among the buses but its
                        // contents (patterns, manual slots) are never combined with them.
                        if (bus != null && bus != mte
                            && !(bus instanceof MTEHatchCraftingInputME)
                            && bus.getBaseMetaTileEntity() != null
                            && addedBuses.add(bus)) {
                            addInventory(held, bus, maps);
                        }
                    }
                }
            }
            // Only fluid hatches touched, input separation on: the items reach some bus by other means (chest,
            // conduit). Try each bus; the caption and NEI fallback see all of them.
            if (!touchesItemInput && !separated.isEmpty()) {
                List<Held> variants = new ArrayList<>();
                List<ItemStack> baseAll = new ArrayList<>(held.all), baseConfig = new ArrayList<>(held.config);
                for (MTEMultiBlockBase multi : separated) {
                    for (MTEHatchInputBus bus : multi.mInputBusses) {
                        if (bus == null || bus instanceof MTEHatchCraftingInputME
                            || bus.getBaseMetaTileEntity() == null
                            || !addedBuses.add(bus)) continue;
                        Held v = new Held();
                        v.all.addAll(baseAll);
                        v.config.addAll(baseConfig);
                        addInventory(v, bus, maps);
                        variants.add(v);
                        addInventory(held, bus, maps);
                    }
                }
                if (!variants.isEmpty()) held.perBus = variants;
            }
        }
        return held;
    }

    /** fit() for the machine; with per-bus possibilities the best one counts (OK, LOOSE, EXTRA, NONE). */
    private static int fitAny(RecipeMap<?> map, PatternContents contents, Held held) {
        if (held.perBus == null) return fit(map, contents, held);
        int best = FIT_NONE;
        for (Held v : held.perBus) {
            int f = fit(map, contents, v);
            if (f == FIT_OK) return FIT_OK;
            if (f == FIT_LOOSE) best = FIT_LOOSE;
            else if (f == FIT_EXTRA && best == FIT_NONE) best = FIT_EXTRA;
        }
        return best;
    }

    /** Adds a GT machine's or bus's slots; the circuit slot and recipe non-consumables count as configuration. */
    private static void addInventory(Held held, IMetaTileEntity mte, Collection<RecipeMap<?>> maps) {
        int circuitSlot = -1;
        if (mte instanceof IConfigurationCircuitSupport && ((IConfigurationCircuitSupport) mte).allowSelectCircuit()) {
            circuitSlot = ((IConfigurationCircuitSupport) mte).getCircuitSlot();
        }
        // ME stocking buses show items of the network in their slots: only their configuration counts.
        boolean stocking = mte instanceof MTEHatchInputBus && !(mte instanceof MTEHatchCraftingInputME)
            && mte.getClass()
                .getSimpleName()
                .endsWith("ME");
        for (int i = 0; i < mte.getSizeInventory(); i++) {
            ItemStack s = mte.getStackInSlot(i);
            if (s == null) continue;
            boolean config = i == circuitSlot || isNonConsumable(s, maps);
            if (!stocking || config) held.all.add(s);
            if (config) held.config.add(s);
        }
    }

    /** Per recipe map: items that some recipe uses without consuming them (stack size 0). */
    private static final Map<RecipeMap<?>, Set<String>> NON_CONSUMABLES = new HashMap<>();

    private static boolean isNonConsumable(ItemStack s, Collection<RecipeMap<?>> maps) {
        String exact = Item.getIdFromItem(s.getItem()) + ":" + s.getItemDamage();
        String any = Item.getIdFromItem(s.getItem()) + ":" + OreDictionary.WILDCARD_VALUE;
        for (RecipeMap<?> map : maps) {
            Set<String> kinds = NON_CONSUMABLES.computeIfAbsent(map, m -> {
                Set<String> k = new HashSet<>();
                for (GTRecipe r : m.getAllRecipes()) {
                    if (r.mInputs == null) continue;
                    for (ItemStack in : r.mInputs) {
                        if (in != null && in.getItem() != null && in.stackSize == 0) {
                            k.add(Item.getIdFromItem(in.getItem()) + ":" + in.getItemDamage());
                        }
                    }
                }
                return k;
            });
            if (kinds.contains(exact) || kinds.contains(any)) return true;
        }
        return false;
    }

    /**
     * Where an ME interface delivers: the blocks it faces and, when one of them belongs to another ME network (a
     * subnet), the GT machine inputs that subnet feeds.
     */
    private static List<TileEntity> targets(IInterfaceViewable view) {
        TileEntity self = view.getTileEntity();
        if (!(view instanceof IInterfaceHost) || self == null) return new ArrayList<>();
        EnumSet<ForgeDirection> dirs = ((IInterfaceHost) view).getTargets();
        List<TileEntity> out = new ArrayList<>();
        if (dirs == null) return out;
        IGrid own = ownGrid(view);
        World w = self.getWorldObj();
        for (ForgeDirection d : dirs) {
            int x = self.xCoord + d.offsetX, y = self.yCoord + d.offsetY, z = self.zCoord + d.offsetZ;
            if (w == null || !w.blockExists(x, y, z)) continue;
            TileEntity te = w.getTileEntity(x, y, z);
            if (te == null) continue;
            out.add(te);
            if (te instanceof IGregTechTileEntity || !(te instanceof IGridHost)) continue;
            IGrid sub = null;
            try {
                IGridNode n = ((IGridHost) te).getGridNode(d.getOpposite());
                if (n != null) sub = n.getGrid();
            } catch (Throwable ignored) {}
            // Unknown own network: don't guess, it could be the main network with every machine of the base.
            if (own != null && sub != null && sub != own) {
                for (TileEntity t : subnetInputs(sub)) if (!out.contains(t)) out.add(t);
            }
        }
        return out;
    }

    private static IGrid ownGrid(IInterfaceViewable view) {
        try {
            if (view instanceof IGridHost) {
                IGridNode n = ((IGridHost) view).getGridNode(ForgeDirection.UNKNOWN);
                if (n != null) return n.getGrid();
            }
            TileEntity te = view.getTileEntity();
            if (te instanceof IGridHost) {
                IGridNode n = ((IGridHost) te).getGridNode(ForgeDirection.UNKNOWN);
                if (n != null) return n.getGrid();
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /** Per request: GT machine inputs a subnet reaches (one grid scan per subnet). */
    private static final Map<IGrid, List<TileEntity>> SUBNETS = new java.util.IdentityHashMap<>();
    private static final int SUBNET_NODE_BUDGET = 4096;

    /**
     * GT inputs fed by a subnet: its own ME input buses/hatches and CRIBs, buses/machines faced by its export buses,
     * storage buses and parts, and those next to its interface blocks.
     */
    private static List<TileEntity> subnetInputs(IGrid sub) {
        List<TileEntity> cached = SUBNETS.get(sub);
        if (cached != null) return cached;
        List<TileEntity> out = new ArrayList<>();
        SUBNETS.put(sub, out);
        int budget = SUBNET_NODE_BUDGET;
        try {
            for (IGridNode n : sub.getNodes()) {
                if (budget-- <= 0) break;
                IGridHost m = n.getMachine();
                if (m instanceof IGregTechTileEntity) {
                    addGtInput(out, (TileEntity) m);
                } else if (m instanceof appeng.parts.AEBasePart) {
                    appeng.parts.AEBasePart p = (appeng.parts.AEBasePart) m;
                    TileEntity host = p.getTile();
                    ForgeDirection s = p.getSide();
                    if (host != null && s != null && s != ForgeDirection.UNKNOWN) {
                        addGtInput(out, tileAt(host, s));
                    }
                } else if (m instanceof IInterfaceHost && m instanceof TileEntity) {
                    for (TileEntity t : neighbours((TileEntity) m, ((IInterfaceHost) m).getTargets()))
                        addGtInput(out, t);
                }
            }
        } catch (Throwable t) {
            PatternRouter.LOG.debug("Subnet scan failed: {}", t.toString());
        }
        return out;
    }

    /** Only machine inputs: an output hatch on a subnet says nothing about what the subnet feeds. */
    private static void addGtInput(List<TileEntity> out, TileEntity te) {
        if (!(te instanceof IGregTechTileEntity) || out.contains(te)) return;
        IMetaTileEntity mte = ((IGregTechTileEntity) te).getMetaTileEntity();
        if (mte instanceof MTEHatchInputBus || mte instanceof MTEHatchInput || mte instanceof MTEBasicMachine)
            out.add(te);
    }

    private static TileEntity tileAt(TileEntity from, ForgeDirection d) {
        World w = from.getWorldObj();
        int x = from.xCoord + d.offsetX, y = from.yCoord + d.offsetY, z = from.zCoord + d.offsetZ;
        return w != null && w.blockExists(x, y, z) ? w.getTileEntity(x, y, z) : null;
    }

    private static List<TileEntity> neighbours(TileEntity self, EnumSet<ForgeDirection> dirs) {
        List<TileEntity> out = new ArrayList<>();
        if (self == null || dirs == null) return out;
        World w = self.getWorldObj();
        for (ForgeDirection d : dirs) {
            int x = self.xCoord + d.offsetX, y = self.yCoord + d.offsetY, z = self.zCoord + d.offsetZ;
            if (w != null && w.blockExists(x, y, z)) {
                TileEntity te = w.getTileEntity(x, y, z);
                if (te != null) out.add(te);
            }
        }
        return out;
    }

    /** Same item, wildcard damage, or a shared ore dictionary name (e.g. any lens of the same colour). */
    private static boolean matches(ItemStack need, ItemStack have) {
        if (need == null || have == null || need.getItem() == null || have.getItem() == null) return false;
        if (need.getItem() == have.getItem()) {
            int dn = need.getItemDamage();
            if (dn == OreDictionary.WILDCARD_VALUE || dn == have.getItemDamage()) return true;
        }
        for (int a : OreDictionary.getOreIDs(need)) {
            for (int b : OreDictionary.getOreIDs(have)) if (a == b) return true;
        }
        return false;
    }

    private static boolean sameItem(ItemStack a, ItemStack b) {
        return a != null && b != null && a.isItemEqual(b) && ItemStack.areItemStackTagsEqual(a, b);
    }
}

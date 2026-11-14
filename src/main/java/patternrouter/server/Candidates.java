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

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ChunkCoordinates;
import net.minecraft.util.IChatComponent;
import net.minecraft.world.World;
import net.minecraftforge.common.util.ForgeDirection;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.oredict.OreDictionary;

import appeng.api.AEApi;
import appeng.api.config.SecurityPermissions;
import appeng.api.implementations.ICraftingPatternItem;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridHost;
import appeng.api.networking.IGridNode;
import appeng.api.networking.crafting.ICraftingPatternDetails;
import appeng.api.networking.security.IActionHost;
import appeng.api.networking.security.ISecurityGrid;
import appeng.api.storage.StorageName;
import appeng.api.storage.data.IAEFluidStack;
import appeng.api.storage.data.IAEItemStack;
import appeng.api.storage.data.IAEStack;
import appeng.api.util.IInterfaceViewable;
import appeng.container.implementations.ContainerPatternTerm;
import appeng.helpers.IInterfaceHost;
import appeng.items.misc.ItemEncodedPattern;
import gregtech.api.interfaces.IConfigurationCircuitSupport;
import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.interfaces.tileentity.RecipeMapWorkable;
import gregtech.api.metatileentity.implementations.MTEBasicMachine;
import gregtech.api.metatileentity.implementations.MTEHatch;
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

        Session(int token, Container container, List<WeakReference<IInterfaceViewable>> targets,
            List<Conflict> conflicts) {
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

    private static final Map<UUID, Session> SESSIONS = new HashMap<>();
    private static int nextToken = 1;

    private Candidates() {}

    // ------------------------------------------------------------------ list request

    public static void handleRequest(EntityPlayerMP player, String category, List<ItemStack> nonConsumables) {
        Net.CandidateList reply = new Net.CandidateList();
        reply.token = nextToken++;
        Container open = player.openContainer;
        if (!(open instanceof ContainerPatternTerm)) return; // terminal already closed, ignore
        ContainerPatternTerm term = (ContainerPatternTerm) open;

        ItemStack pattern = encodedPattern(term);
        IGrid grid = gridOf(term);
        if (pattern == null) {
            reply.note = NOTE_NO_PATTERN;
        } else if (grid == null) {
            reply.note = NOTE_NO_GRID;
        } else if (!canBuild(grid, player)) {
            reply.note = NOTE_DENIED;
        } else {
            category = checkedCategory(category, nonConsumables, pattern, player.worldObj);
            List<Scored> scored = score(grid, player.worldObj, category, nonConsumables, pattern);
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
            PatternRouter.LOG.info(
                "Pattern upload for {}: category '{}', {} non-consumables, output {}, {} providers checked, {} conflicts",
                player.getCommandSenderName(),
                category,
                nonConsumables.size(),
                patternOutput(pattern, player.worldObj),
                scored.size(),
                conflicts.size());
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
            if (anyMachine) scored.removeIf(s -> (s.flags & Net.Entry.RECIPE_MATCH) == 0);
            else scored.removeIf(s -> s.free == 0);
            // If some machine fits, hide the ones configured for another recipe (wrong circuit/mold/lens).
            // When nothing fits, they stay as "doesn't fit" so the player sees where to put the right item.
            boolean anyFit = false;
            for (Scored s : scored) anyFit |= (s.flags & Net.Entry.MOLD_MATCH) != 0;
            if (anyFit) scored.removeIf(s -> (s.flags & Net.Entry.MOLD_MISMATCH) != 0);
            scored.sort(ORDER);
            if (scored.size() > MAX_ENTRIES) scored = new ArrayList<>(scored.subList(0, MAX_ENTRIES));

            List<WeakReference<IInterfaceViewable>> refs = new ArrayList<>();
            for (Scored s : scored) {
                refs.add(new WeakReference<>(s.view));
                reply.entries.add(s.toEntry());
            }
            SESSIONS.put(player.getUniqueID(), new Session(reply.token, term, refs, conflicts));
        }
        Net.CH.sendTo(reply, player);
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
        PatternRouter.LOG.info("Ignoring NEI category '{}': the pattern is not a recipe of that machine", category);
        return "";
    }

    /** Order: has space -> machine -> mold -> no duplicate -> name. */
    private static final Comparator<Scored> ORDER = Comparator.<Scored>comparingInt(s -> s.free > 0 ? 0 : 1)
        .thenComparingInt(s -> (s.flags & Net.Entry.RECIPE_MATCH) != 0 ? 0 : 1)
        .thenComparingInt(
            s -> (s.flags & Net.Entry.MOLD_MATCH) != 0 ? 0 : (s.flags & Net.Entry.MOLD_MISMATCH) != 0 ? 2 : 1)
        .thenComparingInt(s -> (s.flags & Net.Entry.HAS_SAME) != 0 ? 1 : 0)
        .thenComparing(s -> s.name);

    private static final class Scored {

        IInterfaceViewable view;
        ItemStack icon;
        String name;
        String suffix;
        int free;
        int flags;
        /** Feeds a GT hatch that no formed multiblock owns. */
        boolean unformed;
        /** Slots holding a pattern with the same output. */
        final List<Integer> sameSlots = new ArrayList<>();

        Net.Entry toEntry() {
            Net.Entry e = new Net.Entry();
            e.icon = icon;
            e.name = name;
            e.suffix = suffix;
            e.free = free;
            e.flags = flags;
            return e;
        }
    }

    private static List<Scored> score(IGrid grid, World world, String category, List<ItemStack> nonConsumables,
        ItemStack pattern) {
        RecipeMap<?> wanted = recipeMapOf(category);
        ItemStack output = patternOutput(pattern, world);
        // Pattern inputs/output for the GT recipe lookup (with or without the NEI category).
        PatternContents contents = PatternContents.of(pattern, world);
        List<Scored> out = new ArrayList<>();
        Set<IInterfaceViewable> seen = new HashSet<>();
        for (Class<? extends IInterfaceViewable> cls : AEApi.instance()
            .registries()
            .interfaceTerminal()
            .getSupportedClasses()) {
            for (IGridNode node : grid.getMachines(cls)) {
                if (!(node.getMachine() instanceof IInterfaceViewable)) continue;
                IInterfaceViewable view = (IInterfaceViewable) node.getMachine();
                if (!seen.add(view) || !view.shouldDisplay() || !node.isActive()) continue;
                IInventory inv = view.getPatterns();
                if (inv == null) continue;
                try {
                    out.add(scoreOne(view, inv, wanted, nonConsumables, pattern, output, contents));
                } catch (Throwable t) {
                    PatternRouter.LOG.warn("Skipping provider {}: {}", view.getName(), t.toString());
                }
            }
        }
        return out;
    }

    private static Scored scoreOne(IInterfaceViewable view, IInventory inv, RecipeMap<?> wanted,
        List<ItemStack> nonConsumables, ItemStack pattern, ItemStack output, PatternContents contents) {
        Scored s = new Scored();
        s.view = view;
        s.icon = icon(view);
        s.name = rawName(view);
        IChatComponent suffix = nameSuffix(view);
        // Sent as JSON so the client translates it in the player's language.
        s.suffix = suffix == null ? "" : IChatComponent.Serializer.func_150696_a(suffix);

        int slots = Math.min(inv.getSizeInventory(), numSlots(view));
        for (int i = 0; i < slots; i++) {
            ItemStack in = inv.getStackInSlot(i);
            if (in == null) {
                if (inv.isItemValidForSlot(i, pattern)) s.free++;
            } else if (output != null && sameItem(
                patternOutput(
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
        boolean known = !contents.isEmpty();
        if (wanted != null) {
            if (!maps.contains(wanted)) return s;
            s.flags |= Net.Entry.RECIPE_MATCH;
            Held held = machineHeld(view, maps);
            int fit = known ? fit(wanted, contents, held) : FIT_NONE;
            if (fit == FIT_OK) s.flags |= Net.Entry.MOLD_MATCH;
            else if (fit == FIT_EXTRA) s.flags |= Net.Entry.MOLD_MISMATCH;
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
            Held held = machineHeld(view, maps);
            for (RecipeMap<?> map : maps) {
                int fit = fit(map, contents, held);
                if (fit == FIT_OK) {
                    s.flags = (s.flags & ~Net.Entry.MOLD_MISMATCH) | Net.Entry.RECIPE_MATCH | Net.Entry.MOLD_MATCH;
                    break;
                }
                if (fit == FIT_EXTRA) s.flags |= Net.Entry.RECIPE_MATCH | Net.Entry.MOLD_MISMATCH;
            }
        }
        return s;
    }

    private static final int FIT_NONE = 0; // GT finds no recipe with this output
    private static final int FIT_OK = 1; // recipe found, everything the machine is configured with is needed
    private static final int FIT_EXTRA = 2; // recipe found, but the machine holds a circuit/mold/lens it doesn't need

    /**
     * Runs the same GT recipe lookup the machine would: pattern inputs plus everything the machine holds. Then each
     * configuration item (circuit, mold, lens) is removed in turn; if the recipe is still found, that item is not
     * part of it and would at best be ignored, at worst select another recipe.
     */
    private static int fit(RecipeMap<?> map, PatternContents contents, Held held) {
        if (contents.recipeIn(map, held.all) == null) return FIT_NONE;
        for (ItemStack x : held.config) {
            List<ItemStack> without = new ArrayList<>();
            for (ItemStack h : held.all) if (!sameItem(h, x)) without.add(h);
            if (contents.recipeIn(map, without) != null) return FIT_EXTRA;
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

        static PatternContents of(ItemStack pattern, World world) {
            PatternContents pc = new PatternContents();
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
                    .filter(this::producedBy)
                    .findFirst()
                    .orElse(null);
            } catch (Throwable t) {
                return null;
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
        if (!canBuild(grid, player)) {
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
        if (!canBuild(grid, player)) {
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
        IInventory target = view.getPatterns();
        int slots = Math.min(target.getSizeInventory(), numSlots(view));
        for (int i = 0; i < slots; i++) {
            if (target.getStackInSlot(i) == null && target.isItemValidForSlot(i, pattern)) {
                ItemStack one = pattern.copy();
                one.stackSize = 1;
                target.setInventorySlotContents(i, one);
                target.markDirty();
                pattern.stackSize--;
                patternInv.setInventorySlotContents(1, pattern.stackSize > 0 ? pattern : null);
                term.detectAndSendChanges();
                Net.CH.sendTo(new Net.Result(Net.Result.OK, rawName(view)), player);
                PatternRouter.LOG.debug("{} uploaded a pattern to {}", player.getCommandSenderName(), rawName(view));
                return;
            }
        }
        Net.CH.sendTo(new Net.Result(Net.Result.FULL, rawName(view)), player);
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

    private static IGrid gridOf(ContainerPatternTerm term) {
        Object target = term.getTarget();
        IGridNode node = null;
        if (target instanceof IActionHost) node = ((IActionHost) target).getActionableNode();
        if (node == null && target instanceof IGridHost)
            node = ((IGridHost) target).getGridNode(ForgeDirection.UNKNOWN);
        return node == null ? null : node.getGrid();
    }

    private static boolean sameGrid(IInterfaceViewable view, IGrid grid) {
        IGridNode node = view.getGridNode(ForgeDirection.UNKNOWN);
        return node != null && node.getGrid() == grid;
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
            IInterfaceHost host = (IInterfaceHost) view;
            for (TileEntity n : neighbours(self, host.getTargets())) maps.addAll(recipeMapsOf(n, 0));
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
        if (view instanceof IInterfaceHost) tes.addAll(neighbours(self, ((IInterfaceHost) view).getTargets()));
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
            for (TileEntity n : neighbours(self, ((IInterfaceHost) view).getTargets())) {
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
            for (TileEntity n : neighbours(self, ((IInterfaceHost) view).getTargets())) {
                if (!(n instanceof IGregTechTileEntity)) continue;
                IMetaTileEntity mte = ((IGregTechTileEntity) n).getMetaTileEntity();
                if (mte instanceof MTEBasicMachine || mte instanceof MTEHatchInputBus) {
                    addInventory(held, mte, maps);
                }
                // Multiblock without input separation: GT combines all its input buses (molds, lenses may be in
                // another one).
                if (mte instanceof MTEHatchInputBus) {
                    ChunkCoordinates owner = Owners.ownerOf(mte, n.getWorldObj());
                    World w = n.getWorldObj();
                    if (owner == null || w == null || !w.blockExists(owner.posX, owner.posY, owner.posZ)) continue;
                    TileEntity c = w.getTileEntity(owner.posX, owner.posY, owner.posZ);
                    if (!(c instanceof IGregTechTileEntity)) continue;
                    IMetaTileEntity cm = ((IGregTechTileEntity) c).getMetaTileEntity();
                    if (!(cm instanceof MTEMultiBlockBase)) continue;
                    MTEMultiBlockBase multi = (MTEMultiBlockBase) cm;
                    if (multi.isInputSeparationEnabled() || multi.mInputBusses == null) continue;
                    for (MTEHatchInputBus bus : multi.mInputBusses) {
                        // Same rule as MTEMultiBlockBase.getStoredInputs(): a CRIB is listed among the buses but its
                        // contents (patterns, manual slots) are never combined with them.
                        if (bus != null && bus != mte
                            && !(bus instanceof MTEHatchCraftingInputME)
                            && bus.getBaseMetaTileEntity() != null) {
                            addInventory(held, bus, maps);
                        }
                    }
                }
            }
        }
        return held;
    }

    /** Adds a GT machine's or bus's slots; the circuit slot and recipe non-consumables count as configuration. */
    private static void addInventory(Held held, IMetaTileEntity mte, Collection<RecipeMap<?>> maps) {
        int circuitSlot = -1;
        if (mte instanceof IConfigurationCircuitSupport && ((IConfigurationCircuitSupport) mte).allowSelectCircuit()) {
            circuitSlot = ((IConfigurationCircuitSupport) mte).getCircuitSlot();
        }
        for (int i = 0; i < mte.getSizeInventory(); i++) {
            ItemStack s = mte.getStackInSlot(i);
            if (s == null) continue;
            held.all.add(s);
            if (i == circuitSlot || isNonConsumable(s, maps)) held.config.add(s);
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

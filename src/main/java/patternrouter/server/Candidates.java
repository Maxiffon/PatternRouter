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
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ChunkCoordinates;
import net.minecraft.util.IChatComponent;
import net.minecraft.world.World;
import net.minecraftforge.common.util.ForgeDirection;
import net.minecraftforge.fluids.FluidStack;

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
import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.interfaces.tileentity.RecipeMapWorkable;
import gregtech.api.metatileentity.implementations.MTEBasicMachine;
import gregtech.api.metatileentity.implementations.MTEHatch;
import gregtech.api.metatileentity.implementations.MTEHatchInputBus;
import gregtech.api.recipe.RecipeCategory;
import gregtech.api.recipe.RecipeMap;
import gregtech.api.util.GTRecipe;
import gregtech.common.tileentities.machines.MTEHatchCraftingInputME;
import patternrouter.Net;
import patternrouter.PatternRouter;
import patternrouter.mixins.late.gt.MTEHatchAccessor;

/**
 * Серверная часть: ищет в ME-сети открытого терминала шаблонов интерфейсы и CRIB, подходящие под рецепт, и кладёт
 * шаблон в выбранный.
 */
public final class Candidates {

    public static final int NOTE_NONE = 0;
    public static final int NOTE_NO_PATTERN = 1; // в выходном слоте нет закодированного шаблона
    public static final int NOTE_NO_GRID = 2; // терминал не в сети
    public static final int NOTE_DENIED = 3; // нет права строить в этой сети
    public static final int NOTE_NO_MACHINE = 4; // подходящих машин нет, показываем все интерфейсы
    public static final int NOTE_UNKNOWN_RECIPE = 5; // рецепт не из GregTech или не через NEI

    private static final int MAX_ENTRIES = 40;

    /** Последний выданный игроку список: по номеру строки находим, куда класть. */
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

    /** Уже существующий шаблон на тот же результат: где лежит и какой именно (для проверки перед извлечением). */
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

    // ------------------------------------------------------------------ запрос списка

    public static void handleRequest(EntityPlayerMP player, String category, List<ItemStack> nonConsumables) {
        Net.CandidateList reply = new Net.CandidateList();
        reply.token = nextToken++;
        Container open = player.openContainer;
        if (!(open instanceof ContainerPatternTerm)) return; // терминал уже закрыт — молча
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
            List<Scored> scored = score(grid, player.worldObj, category, nonConsumables, pattern);
            // Конфликты ищем по всей сети, до фильтрации: старый рецепт мог быть в машине другого типа.
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
            // Машину нашли (по NEI или по содержимому шаблона) — пояснения не нужны.
            if (!anyMachine) reply.note = wanted == null ? NOTE_UNKNOWN_RECIPE : NOTE_NO_MACHINE;
            // Есть подходящие машины — показываем только их; иначе все интерфейсы со свободным местом.
            if (anyMachine) scored.removeIf(s -> (s.flags & Net.Entry.RECIPE_MATCH) == 0);
            else scored.removeIf(s -> s.free == 0);
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

    /** Подходящие сверху: есть место → машина → форма → нет такого же шаблона → имя. */
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
        /** Слоты с шаблоном на тот же результат. */
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
        // Рецепт не пришёл из NEI — проверим машины по их картам рецептов по содержимому шаблона.
        PatternContents contents = wanted == null ? PatternContents.of(pattern, world) : null;
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
        s.icon = view.getDisplayRep() != null ? view.getDisplayRep() : view.getSelfRep();
        s.name = view.getRawName() == null ? "" : view.getRawName();
        IChatComponent suffix = view.getNameSuffix();
        // Передаём как JSON: клиент переведёт подпись на языке игрока (на сервере язык свой).
        s.suffix = suffix == null ? "" : IChatComponent.Serializer.func_150696_a(suffix);

        int slots = Math.min(inv.getSizeInventory(), view.numSlots());
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

        if (wanted != null) {
            Collection<RecipeMap<?>> maps = machineRecipeMaps(view);
            if (maps.contains(wanted)) {
                s.flags |= Net.Entry.RECIPE_MATCH;
                if (!nonConsumables.isEmpty()) {
                    List<ItemStack> held = machineHeldItems(view);
                    boolean all = true;
                    for (ItemStack need : nonConsumables) {
                        boolean found = false;
                        for (ItemStack h : held) {
                            if (matches(need, h)) {
                                found = true;
                                break;
                            }
                        }
                        all &= found;
                    }
                    s.flags |= all ? Net.Entry.MOLD_MATCH : Net.Entry.MOLD_MISMATCH;
                }
            }
        } else if (contents != null && !contents.isEmpty()) {
            // Категории нет: ищем настоящий рецепт GT из входов шаблона и того, что лежит в машине (форма, схема).
            // Нашёлся рецепт с этим же результатом — машина подходит, и форма в ней правильная.
            Collection<RecipeMap<?>> maps = machineRecipeMaps(view);
            if (!maps.isEmpty()) {
                List<ItemStack> held = machineHeldItems(view);
                for (RecipeMap<?> map : maps) {
                    if (contents.madeBy(map, held)) {
                        s.flags |= Net.Entry.RECIPE_MATCH | Net.Entry.MOLD_MATCH;
                        break;
                    }
                }
            }
        }
        return s;
    }

    /** Входы и основной выход шаблона в виде, понятном поиску рецептов GT. */
    private static final class PatternContents {

        final List<ItemStack> items = new ArrayList<>();
        final List<FluidStack> fluids = new ArrayList<>();
        ItemStack outItem;
        FluidStack outFluid;

        static PatternContents of(ItemStack pattern, World world) {
            PatternContents pc = new PatternContents();
            if (pattern == null || !(pattern.getItem() instanceof ICraftingPatternItem) || world == null) return pc;
            try {
                ICraftingPatternDetails d = ((ICraftingPatternItem) pattern.getItem())
                    .getPatternForItem(pattern, world);
                if (d == null) return pc;
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

        /** Есть ли в карте рецепт из этих входов (плюс то, что лежит в машине) с тем же результатом. */
        boolean madeBy(RecipeMap<?> map, List<ItemStack> held) {
            List<ItemStack> in = new ArrayList<>(items);
            in.addAll(held);
            try {
                return map.findRecipeQuery()
                    .items(in.toArray(new ItemStack[0]))
                    .fluids(fluids.toArray(new FluidStack[0]))
                    .dontCheckStackSizes(true)
                    .findAll()
                    .anyMatch(this::producedBy);
            } catch (Throwable t) {
                return false;
            }
        }

        private boolean producedBy(GTRecipe r) {
            if (outItem != null && r.mOutputs != null) {
                for (ItemStack o : r.mOutputs) if (o != null && o.isItemEqual(outItem)) return true;
            }
            if (outFluid != null && r.mFluidOutputs != null) {
                for (FluidStack o : r.mFluidOutputs) if (o != null && o.isFluidEqual(outFluid)) return true;
            }
            return false;
        }
    }

    // ------------------------------------------------------------------ извлечение старого шаблона

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
            Net.CH.sendTo(new Net.Result(Net.Result.DENIED, view.getRawName()), player);
            return;
        }
        IInventory inv = view.getPatterns();
        ItemStack now = c.slot < inv.getSizeInventory() ? inv.getStackInSlot(c.slot) : null;
        // В слоте должен лежать ровно тот шаблон, что мы показали: иначе его уже кто-то поменял.
        if (now == null || !ItemStack.areItemStacksEqual(now, c.pattern)) {
            Net.CH.sendTo(new Net.Result(Net.Result.GONE, view.getRawName()), player);
            return;
        }
        ItemStack taken = now.copy();
        inv.setInventorySlotContents(c.slot, null);
        inv.markDirty();
        if (!player.inventory.addItemStackToInventory(taken)) {
            inv.setInventorySlotContents(c.slot, now); // инвентарь полон — возвращаем как было
            inv.markDirty();
            Net.CH.sendTo(new Net.Result(Net.Result.INV_FULL, view.getRawName()), player);
            return;
        }
        player.inventoryContainer.detectAndSendChanges();
        open.detectAndSendChanges();
        Net.CH.sendTo(new Net.Result(Net.Result.EXTRACTED, view.getRawName()), player);
    }

    // ------------------------------------------------------------------ загрузка

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
            Net.CH.sendTo(new Net.Result(Net.Result.DENIED, view.getRawName()), player);
            return;
        }
        IInventory patternInv = term.getPatternTerminal()
            .getInventoryByName(StorageName.CRAFTING_PATTERN.getName());
        ItemStack pattern = patternInv == null ? null : patternInv.getStackInSlot(1);
        if (pattern == null || !(pattern.getItem() instanceof ItemEncodedPattern)) {
            Net.CH.sendTo(new Net.Result(Net.Result.NO_PATTERN, view.getRawName()), player);
            return;
        }
        IInventory target = view.getPatterns();
        int slots = Math.min(target.getSizeInventory(), view.numSlots());
        for (int i = 0; i < slots; i++) {
            if (target.getStackInSlot(i) == null && target.isItemValidForSlot(i, pattern)) {
                ItemStack one = pattern.copy();
                one.stackSize = 1;
                target.setInventorySlotContents(i, one);
                target.markDirty();
                pattern.stackSize--;
                patternInv.setInventorySlotContents(1, pattern.stackSize > 0 ? pattern : null);
                term.detectAndSendChanges();
                Net.CH.sendTo(new Net.Result(Net.Result.OK, view.getRawName()), player);
                PatternRouter.LOG
                    .debug("{} uploaded a pattern to {}", player.getCommandSenderName(), view.getRawName());
                return;
            }
        }
        Net.CH.sendTo(new Net.Result(Net.Result.FULL, view.getRawName()), player);
    }

    // ------------------------------------------------------------------ помощники

    private static ItemStack encodedPattern(ContainerPatternTerm term) {
        IInventory inv = term.getPatternTerminal()
            .getInventoryByName(StorageName.CRAFTING_PATTERN.getName());
        if (inv == null || inv.getSizeInventory() < 2) return null;
        ItemStack s = inv.getStackInSlot(1);
        return s != null && s.getItem() instanceof ItemEncodedPattern ? s : null;
    }

    /**
     * Основной выход шаблона. Разбираем сами в мире сервера: ItemEncodedPattern.getOutput() берёт мир у клиентского
     * прокси AE2 и на сервере возвращает null.
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

    /** Карта рецептов GregTech по идентификатору категории NEI. */
    public static RecipeMap<?> recipeMapOf(String category) {
        if (category == null || category.isEmpty()) return null;
        for (RecipeCategory c : RecipeCategory.ALL_RECIPE_CATEGORIES.values()) {
            if (category.equals(c.unlocalizedName)) return c.recipeMap;
        }
        return null;
    }

    /** Какие карты рецептов у машины, в которую этот интерфейс/CRIB подаёт предметы. */
    private static Collection<RecipeMap<?>> machineRecipeMaps(IInterfaceViewable view) {
        Set<RecipeMap<?>> maps = new HashSet<>();
        TileEntity self = view.getTileEntity();
        if (self == null) return maps;
        // CRIB и прочие люки GT: машина — контроллер многоблока.
        maps.addAll(recipeMapsOf(self, 0));
        // ME-интерфейс AE2: машина — соседний блок, в который он выдаёт.
        if (view instanceof IInterfaceHost) {
            IInterfaceHost host = (IInterfaceHost) view;
            for (TileEntity n : neighbours(self, host.getTargets())) maps.addAll(recipeMapsOf(n, 0));
        }
        return maps;
    }

    private static Collection<RecipeMap<?>> recipeMapsOf(TileEntity te, int depth) {
        if (!(te instanceof IGregTechTileEntity)) return Collections.emptyList();
        IMetaTileEntity mte = ((IGregTechTileEntity) te).getMetaTileEntity();
        if (mte instanceof RecipeMapWorkable) {
            Collection<RecipeMap<?>> c = ((RecipeMapWorkable) mte).getAvailableRecipeMaps();
            if (!c.isEmpty()) return c;
        }
        if (mte instanceof MTEHatch && depth == 0) {
            ChunkCoordinates owner = ((MTEHatchAccessor) mte).patternrouter$getOwner();
            World w = te.getWorldObj();
            if (owner != null && w != null && w.blockExists(owner.posX, owner.posY, owner.posZ)) {
                return recipeMapsOf(w.getTileEntity(owner.posX, owner.posY, owner.posZ), 1);
            }
        }
        return Collections.emptyList();
    }

    /** Что лежит в машине из несъедаемого: формы, линзы, схемы. */
    private static List<ItemStack> machineHeldItems(IInterfaceViewable view) {
        List<ItemStack> held = new ArrayList<>();
        TileEntity self = view.getTileEntity();
        if (self instanceof IGregTechTileEntity) {
            IMetaTileEntity mte = ((IGregTechTileEntity) self).getMetaTileEntity();
            if (mte instanceof MTEHatchCraftingInputME) {
                for (ItemStack s : ((MTEHatchCraftingInputME) mte).getSharedItems()) if (s != null) held.add(s);
            }
        }
        if (view instanceof IInterfaceHost) {
            for (TileEntity n : neighbours(self, ((IInterfaceHost) view).getTargets())) {
                if (!(n instanceof IGregTechTileEntity)) continue;
                IGregTechTileEntity gt = (IGregTechTileEntity) n;
                IMetaTileEntity mte = gt.getMetaTileEntity();
                if (mte instanceof MTEBasicMachine || mte instanceof MTEHatchInputBus) {
                    // Входные слоты одноблочной машины или входной шины: форма лежит там же.
                    for (int i = 0; i < gt.getSizeInventory(); i++) {
                        ItemStack s = gt.getStackInSlot(i);
                        if (s != null) held.add(s);
                    }
                }
            }
        }
        return held;
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

    private static boolean matches(ItemStack need, ItemStack have) {
        if (need == null || have == null || need.getItem() != have.getItem()) return false;
        int dn = need.getItemDamage();
        return dn == 32767 || dn == have.getItemDamage();
    }

    private static boolean sameItem(ItemStack a, ItemStack b) {
        return a != null && b != null && a.isItemEqual(b) && ItemStack.areItemStackTagsEqual(a, b);
    }
}

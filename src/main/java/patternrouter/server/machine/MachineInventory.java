package patternrouter.server.machine;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;

import appeng.api.util.IInterfaceViewable;
import appeng.helpers.IInterfaceHost;
import gregtech.api.interfaces.IConfigurationCircuitSupport;
import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.implementations.MTEBasicMachine;
import gregtech.api.metatileentity.implementations.MTEHatch;
import gregtech.api.metatileentity.implementations.MTEHatchInput;
import gregtech.api.metatileentity.implementations.MTEHatchInputBus;
import gregtech.api.metatileentity.implementations.MTEMultiBlockBase;
import gregtech.api.recipe.RecipeMap;
import gregtech.common.tileentities.machines.MTEHatchCraftingInputME;
import patternrouter.common.Items;
import patternrouter.server.recipe.RecipeMaps;

/** What the machine behind a provider holds besides the pattern inputs. */
public final class MachineInventory {

    /** MTEMultiBlockBase.getStoredInputs() also takes a programmed circuit from this controller slot. */
    private static final int CONTROLLER_CIRCUIT_SLOT = 1;

    /** Everything that takes part in the machine's recipe lookup. */
    public final List<ItemStack> items = new ArrayList<>();
    /** The setup among them: circuits, molds, lenses. */
    public final List<ItemStack> setupItems = new ArrayList<>();
    /**
     * Multiblock with input separation fed only through fluid hatches: items may land in any of its buses, so
     * each bus is a separate possibility (GT runs each bus with all fluids). Null otherwise.
     */
    public List<MachineInventory> perBus;

    private MachineInventory() {}

    public static MachineInventory of(IInterfaceViewable view, Collection<RecipeMap<?>> maps) {
        MachineInventory inv = new MachineInventory();
        if (view.getTileEntity() instanceof IGregTechTileEntity gtTile
            && gtTile.getMetaTileEntity() instanceof MTEHatchCraftingInputME crib) {
            inv.addCribSharedItems(crib);
        }
        if (view instanceof IInterfaceHost) inv.addInterfaceNeighbours(view, maps);
        return inv;
    }

    /** CRIB circuit + manual slots: shared by every pattern, all of it is setup. */
    private void addCribSharedItems(MTEHatchCraftingInputME crib) {
        for (ItemStack s : crib.getSharedItems()) {
            if (s == null) continue;
            items.add(s);
            setupItems.add(s);
        }
    }

    private void addInterfaceNeighbours(IInterfaceViewable view, Collection<RecipeMap<?>> maps) {
        Set<Object> added = Collections.newSetFromMap(new IdentityHashMap<>());
        boolean touchesItemInput = false;
        List<MTEMultiBlockBase> separated = new ArrayList<>();
        for (TileEntity n : MachineLocator.deliveryTargets(view)) {
            if (!(n instanceof IGregTechTileEntity gtTile)) continue;
            IMetaTileEntity mte = gtTile.getMetaTileEntity();
            if (mte instanceof MTEBasicMachine || mte instanceof MTEHatchInputBus) {
                touchesItemInput = true;
                if (added.add(mte)) addSlots(this, mte, maps);
            } else if (mte instanceof MTEHatchInput && added.add(mte)) {
                // Fluid hatches may hold setup too: GT++ Solidifier Hatch has a mold slot and a circuit.
                addSlots(this, mte, maps);
            }
            if (mte instanceof MTEHatch && !(mte instanceof MTEHatchCraftingInputME)
                && HatchOwners.controllerTile(mte, n) instanceof IGregTechTileEntity controllerTile
                && controllerTile.getMetaTileEntity() instanceof MTEMultiBlockBase multi) {
                addMultiblockInputs(multi, mte, added, separated, maps);
            }
        }
        // Only fluid hatches touched, input separation on: the items reach some bus by other means (chest,
        // conduit). Try each bus; the caption and NEI fallback see all of them.
        if (!touchesItemInput && !separated.isEmpty()) addPerBusVariants(separated, added, maps);
    }

    /**
     * Multiblock without input separation: GT combines all its input buses (circuit, mold, lens may be in another one
     * than the hatch the interface touches, e.g. an interface on a fluid input hatch). With separation the buses are
     * only collected, see {@link #addPerBusVariants}.
     */
    private void addMultiblockInputs(MTEMultiBlockBase multi, IMetaTileEntity touched, Set<Object> added,
        List<MTEMultiBlockBase> separated, Collection<RecipeMap<?>> maps) {
        ItemStack controllerCircuit = multi.getStackInSlot(CONTROLLER_CIRCUIT_SLOT);
        if (controllerCircuit != null && Items.isProgrammedCircuit(controllerCircuit) && added.add(multi)) {
            items.add(controllerCircuit);
            setupItems.add(controllerCircuit);
        }
        if (multi.mInputBusses == null) return;
        if (multi.isInputSeparationEnabled()) {
            if (!separated.contains(multi)) separated.add(multi);
            return;
        }
        for (MTEHatchInputBus bus : multi.mInputBusses) {
            // Same rule as MTEMultiBlockBase.getStoredInputs(): a CRIB is listed among the buses but its
            // contents (patterns, manual slots) are never combined with them.
            if (bus != null && bus != touched
                && !(bus instanceof MTEHatchCraftingInputME)
                && bus.getBaseMetaTileEntity() != null
                && added.add(bus)) {
                addSlots(this, bus, maps);
            }
        }
    }

    private void addPerBusVariants(List<MTEMultiBlockBase> separated, Set<Object> added,
        Collection<RecipeMap<?>> maps) {
        List<MachineInventory> variants = new ArrayList<>();
        List<ItemStack> baseItems = new ArrayList<>(items), baseSetup = new ArrayList<>(setupItems);
        for (MTEMultiBlockBase multi : separated) {
            for (MTEHatchInputBus bus : multi.mInputBusses) {
                if (bus == null || bus instanceof MTEHatchCraftingInputME
                    || bus.getBaseMetaTileEntity() == null
                    || !added.add(bus)) continue;
                MachineInventory variant = new MachineInventory();
                variant.items.addAll(baseItems);
                variant.setupItems.addAll(baseSetup);
                addSlots(variant, bus, maps);
                variants.add(variant);
                addSlots(this, bus, maps);
            }
        }
        if (!variants.isEmpty()) perBus = variants;
    }

    /** Adds a GT machine's or bus's slots; the circuit slot and recipe non-consumables count as setup. */
    private static void addSlots(MachineInventory inv, IMetaTileEntity mte, Collection<RecipeMap<?>> maps) {
        int circuitSlot = -1;
        if (mte instanceof IConfigurationCircuitSupport circuitSupport && circuitSupport.allowSelectCircuit()) {
            circuitSlot = circuitSupport.getCircuitSlot();
        }
        boolean stocking = isStockingBus(mte);
        for (int i = 0; i < mte.getSizeInventory(); i++) {
            ItemStack s = mte.getStackInSlot(i);
            if (s == null) continue;
            boolean setup = i == circuitSlot || RecipeMaps.isNonConsumable(s, maps);
            if (!stocking || setup) inv.items.add(s);
            if (setup) inv.setupItems.add(s);
        }
    }

    /** ME stocking buses show items of the network in their slots: only their setup counts. */
    private static boolean isStockingBus(IMetaTileEntity mte) {
        return mte instanceof MTEHatchInputBus && !(mte instanceof MTEHatchCraftingInputME)
            && mte.getClass()
                .getSimpleName()
                .endsWith("ME");
    }
}

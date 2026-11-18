package patternrouter.server;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.IChatComponent;

import appeng.api.util.IInterfaceViewable;
import gregtech.api.recipe.RecipeMap;
import patternrouter.PatternRouter;
import patternrouter.common.Items;
import patternrouter.net.ProviderEntry;
import patternrouter.server.ae2.Patterns;
import patternrouter.server.ae2.ProviderCompat;
import patternrouter.server.machine.MachineDiagnostics;
import patternrouter.server.machine.MachineInventory;
import patternrouter.server.machine.MachineLocator;
import patternrouter.server.recipe.PatternContents;
import patternrouter.server.recipe.RecipeFit;
import patternrouter.server.recipe.RecipeMaps;

/** Checks one provider against the pattern: free slots, same-output patterns, the machine and its setup. */
final class ProviderScorer {

    private ProviderScorer() {}

    /**
     * @param wanted         recipe map of the NEI category, or null: then the GT lookup alone decides
     * @param nonConsumables what NEI showed as not consumed (molds, circuits), the fallback when the lookup fails
     * @param output         the pattern's main output, for same-output patterns
     */
    static ProviderScore score(IInterfaceViewable view, IInventory patterns, RecipeMap<?> wanted,
        List<ItemStack> nonConsumables, ItemStack pattern, ItemStack output, PatternContents contents) {
        ProviderScore s = new ProviderScore(view);
        readIdentity(s, view);
        countSlots(s, view, patterns, pattern, output);
        if (contents.isCraftingPattern()) {
            // Crafting pattern: AE2 itself knows which providers craft (interface next to a molecular assembler,
            // GT Large Molecular Assembler).
            if (ProviderCompat.isCraftingProvider(view))
                s.flags |= ProviderEntry.RECIPE_MATCH | ProviderEntry.SETUP_FITS;
            return s;
        }
        Collection<RecipeMap<?>> maps = MachineLocator.recipeMapsFed(view);
        if (wanted != null && !maps.contains(wanted) && PatternRouter.LOG.isDebugEnabled()) {
            PatternRouter.LOG.debug(
                "  {} ({}): machine {}, maps {}, wanted {}",
                s.name,
                view.getClass()
                    .getSimpleName(),
                MachineDiagnostics.describe(view),
                MachineDiagnostics.names(maps),
                wanted.unlocalizedName);
        }
        if (maps.isEmpty()) {
            s.feedsUnformedMultiblock = MachineLocator.feedsUnformedMultiblock(view);
            return s;
        }
        s.machineSeen = true;
        MachineInventory machine = MachineInventory.of(view, maps);
        if (PatternRouter.LOG.isDebugEnabled()) logMachine(s, view, maps, machine);
        // AE2 captions only CRIBs; for other providers show what the machine is set up with ([5], mold, lens).
        if (s.suffix.isEmpty()) s.setupItems.addAll(oneOfEach(machine.setupItems));
        if (wanted != null) scoreForCategory(s, maps, wanted, contents, machine, nonConsumables);
        else if (!contents.isEmpty()) scoreByLookup(s, maps, contents, machine);
        return s;
    }

    private static void readIdentity(ProviderScore s, IInterfaceViewable view) {
        int[] position = ProviderCompat.position(view);
        if (position != null) {
            s.dim = position[0];
            s.x = position[1];
            s.y = position[2];
            s.z = position[3];
        }
        s.icon = ProviderCompat.icon(view);
        s.name = ProviderCompat.rawName(view);
        IChatComponent suffix = ProviderCompat.nameSuffix(view);
        // Sent as JSON so the client translates it in the player's language.
        // AE2 returns an empty component (not null) for plain interfaces: treat it as "no caption".
        s.suffix = suffix == null || suffix.getUnformattedText()
            .trim()
            .isEmpty() ? "" : IChatComponent.Serializer.func_150696_a(suffix);
    }

    private static void countSlots(ProviderScore s, IInterfaceViewable view, IInventory patterns, ItemStack pattern,
        ItemStack output) {
        TileEntity te = view.getTileEntity();
        int slots = Math.min(patterns.getSizeInventory(), ProviderCompat.numSlots(view));
        for (int i = 0; i < slots; i++) {
            ItemStack in = patterns.getStackInSlot(i);
            if (in == null) {
                if (patterns.isItemValidForSlot(i, pattern)) s.freeSlots++;
            } else if (output != null
                && Items.sameItem(Patterns.storedOutput(in, te != null ? te.getWorldObj() : null), output)) {
                    s.flags |= ProviderEntry.HAS_SAME_OUTPUT;
                    s.sameOutputSlots.add(i);
                }
        }
    }

    private static void scoreForCategory(ProviderScore s, Collection<RecipeMap<?>> maps, RecipeMap<?> wanted,
        PatternContents contents, MachineInventory machine, List<ItemStack> nonConsumables) {
        if (!maps.contains(wanted)) return;
        s.flags |= ProviderEntry.RECIPE_MATCH;
        RecipeFit fit = contents.isEmpty() ? RecipeFit.NONE : RecipeFit.best(wanted, contents, machine);
        if (fit.fits()) {
            s.flags |= ProviderEntry.SETUP_FITS;
            s.hasSpareSetup = fit == RecipeFit.WITH_SPARE_SETUP;
        } else if (fit == RecipeFit.CONFLICTING) {
            s.flags |= ProviderEntry.SETUP_CONFLICTS;
        } else if (!nonConsumables.isEmpty()) {
            // GT lookup failed (e.g. odd pattern amounts): fall back to the items NEI marked as not consumed.
            s.flags |= holdsAll(machine, nonConsumables) ? ProviderEntry.SETUP_FITS : ProviderEntry.SETUP_CONFLICTS;
        }
    }

    private static boolean holdsAll(MachineInventory machine, List<ItemStack> needed) {
        boolean all = true;
        for (ItemStack need : needed) {
            boolean found = false;
            for (ItemStack h : machine.items) found |= Items.matches(need, h);
            all &= found;
        }
        return all;
    }

    /** No category: the GT lookup alone decides which machine fits. */
    private static void scoreByLookup(ProviderScore s, Collection<RecipeMap<?>> maps, PatternContents contents,
        MachineInventory machine) {
        for (RecipeMap<?> map : maps) {
            if (!RecipeMaps.canMake(map, contents)) continue;
            RecipeFit fit = RecipeFit.best(map, contents, machine);
            if (fit.fits()) {
                s.flags = (s.flags & ~ProviderEntry.SETUP_CONFLICTS) | ProviderEntry.RECIPE_MATCH
                    | ProviderEntry.SETUP_FITS;
                s.hasSpareSetup = fit == RecipeFit.WITH_SPARE_SETUP;
                if (fit == RecipeFit.EXACT) break;
                continue;
            }
            if (s.has(ProviderEntry.SETUP_FITS)) continue; // a loose fit in another map beats a mismatch
            if (fit == RecipeFit.CONFLICTING) s.flags |= ProviderEntry.RECIPE_MATCH | ProviderEntry.SETUP_CONFLICTS;
        }
    }

    private static void logMachine(ProviderScore s, IInterfaceViewable view, Collection<RecipeMap<?>> maps,
        MachineInventory machine) {
        List<String> setup = new ArrayList<>();
        for (ItemStack st : machine.setupItems) setup.add(st.getUnlocalizedName() + "@" + st.getItemDamage());
        PatternRouter.LOG.debug(
            "  {}: machine {}, maps {}, held {} items, config {}",
            s.name,
            MachineDiagnostics.describe(view),
            MachineDiagnostics.names(maps),
            machine.items.size(),
            setup);
    }

    private static List<ItemStack> oneOfEach(List<ItemStack> stacks) {
        List<ItemStack> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (ItemStack st : stacks) {
            if (st != null && st.getItem() != null && seen.add(Items.idAndDamage(st))) {
                ItemStack one = st.copy();
                one.stackSize = 1;
                out.add(one);
            }
        }
        return out;
    }
}

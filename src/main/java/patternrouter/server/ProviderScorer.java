package patternrouter.server;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.IChatComponent;

import appeng.api.util.IInterfaceViewable;
import gregtech.api.recipe.RecipeMap;
import gregtech.api.util.GTRecipe;
import gregtech.api.util.GTRecipe.RecipeAssemblyLine;
import gregtech.common.tileentities.machines.multi.MTEAssemblyLine;
import patternrouter.PatternRouter;
import patternrouter.common.Items;
import patternrouter.net.ProviderEntry;
import patternrouter.server.ae2.Patterns;
import patternrouter.server.ae2.ProviderCompat;
import patternrouter.server.machine.AssemblyLineData;
import patternrouter.server.machine.MachineDiagnostics;
import patternrouter.server.machine.MachineInventory;
import patternrouter.server.machine.MachineLimits;
import patternrouter.server.machine.MachineLocator;
import patternrouter.server.machine.MachineModes;
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
        MachineModes modes = MachineLocator.recipeMapsFed(view);
        Collection<RecipeMap<?>> maps = modes.current;
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
        if (modes.isEmpty()) {
            s.feedsUnformedMultiblock = MachineLocator.feedsUnformedMultiblock(view);
            return s;
        }
        s.machineSeen = true;
        MachineInventory machine = MachineInventory.of(view, modes.all());
        if (PatternRouter.LOG.isDebugEnabled()) logMachine(s, view, maps, machine);
        // AE2 captions only CRIBs; for other providers show what the machine is set up with ([5], mold, lens).
        if (s.suffix.isEmpty()) s.setupItems.addAll(oneOfEach(machine.setupItems));
        if (wanted != null) scoreForCategory(s, maps, wanted, contents, machine, nonConsumables);
        else if (!contents.isEmpty()) scoreByLookup(s, maps, contents, machine);
        if (!s.has(ProviderEntry.RECIPE_MATCH)) {
            RecipeMap<?> mode = otherModeFor(modes.otherModes, wanted, contents, machine);
            if (mode != null) {
                s.flags |= ProviderEntry.RECIPE_MATCH | ProviderEntry.SETUP_CONFLICTS | ProviderEntry.OTHER_MODE;
                s.reason = MachineLimits.Reason.ours(
                    new ChatComponentTranslation(
                        "patternrouter.why.othermode",
                        new ChatComponentTranslation(mode.unlocalizedName))).wire;
            }
        }
        scoreAssemblyLineResearch(s, modes, wanted, contents);
        scoreLimits(s, modes, contents, machine);
        return s;
    }

    /**
     * The machine would take the recipe but can't run it: not enough power for its EU/t (lasers, amps and the
     * machine's discount counted), too little coil heat, too low a glass or casing tier, ... as the machine itself
     * checks before starting a recipe.
     */
    private static void scoreLimits(ProviderScore s, MachineModes modes, PatternContents contents,
        MachineInventory machine) {
        if (!s.has(ProviderEntry.RECIPE_MATCH) || s.has(ProviderEntry.SETUP_CONFLICTS) || contents.isEmpty()) return;
        // The map the pattern was matched in: the NEI tab, or the one GT's lookup fitted. Never just the first map
        // with some recipe from these inputs, that may be another recipe of a multi-mode machine.
        RecipeMap<?> map = s.matchedMap;
        if (map == null) return;
        MachineLimits.Reason why;
        if (map == gregtech.api.recipe.RecipeMaps.assemblylineVisualRecipes) {
            RecipeAssemblyLine r = AssemblyLineData.recipeFor(modes.machines, contents.outputItem());
            if (r == null) return;
            why = MachineLimits.whyNotPower(modes.machines, MTEAssemblyLine.class, r.mEUt);
        } else {
            GTRecipe recipe = contents.recipeIn(map, machine.items);
            if (recipe == null) return;
            why = MachineLimits.whyNot(modes.machines, map, recipe);
        }
        if (why == null) return;
        s.flags = (s.flags & ~ProviderEntry.SETUP_FITS) | ProviderEntry.RECIPE_MATCH | ProviderEntry.SETUP_CONFLICTS;
        s.reason = why.wire;
    }

    /**
     * GT can't look assembly line recipes up; the data sticks the line reads (data access hatches, optical data
     * input) tell whether it can run this one. Without an NEI tab only a researched line counts as a match.
     */
    private static void scoreAssemblyLineResearch(ProviderScore s, MachineModes modes, RecipeMap<?> wanted,
        PatternContents contents) {
        RecipeMap<?> assemblyLine = gregtech.api.recipe.RecipeMaps.assemblylineVisualRecipes;
        if (!modes.current.contains(assemblyLine) || (wanted != null && wanted != assemblyLine)) return;
        // Another mode decided already (its reason says which); a recipe matched for another machine behind the
        // same provider is not the assembly line's business.
        if (s.has(ProviderEntry.OTHER_MODE)) return;
        // Only a machine that actually fits it in another map wins over the line; a mere conflict elsewhere doesn't.
        if (s.matchedMap != null && s.matchedMap != assemblyLine && s.has(ProviderEntry.SETUP_FITS)) return;
        Boolean researched = AssemblyLineData.researched(modes.machines, contents.outputItem());
        if (researched == null) {
            // Sticks unreadable: GT's lookup "conflict" says nothing about an assembly line (lines share
            // components), so don't claim a wrong circuit; the recipe kind matches.
            s.flags &= ~ProviderEntry.SETUP_CONFLICTS;
            return;
        }
        // No NEI tab: an unresearched line still counts when GT's lookup found the pattern among assembly line
        // recipes. That lookup's "conflict" means nothing here: many lines share components (hatches of each tier),
        // the line runs only what its sticks hold.
        if (wanted == null && !researched && !s.has(ProviderEntry.RECIPE_MATCH)) return;
        s.flags &= ~(ProviderEntry.SETUP_FITS | ProviderEntry.SETUP_CONFLICTS);
        s.flags |= ProviderEntry.RECIPE_MATCH | (researched ? ProviderEntry.SETUP_FITS : ProviderEntry.SETUP_CONFLICTS);
        s.matchedMap = assemblyLine;
        s.reason = researched ? ""
            : MachineLimits.Reason.ours(new ChatComponentTranslation("patternrouter.why.research")).wire;
    }

    /**
     * The mode (recipe map) the machine would need for the pattern when it is set to another one (Precise Assembler
     * set to plain assembling); null if none.
     */
    private static RecipeMap<?> otherModeFor(Collection<RecipeMap<?>> otherModes, RecipeMap<?> wanted,
        PatternContents contents, MachineInventory machine) {
        if (wanted != null) return otherModes.contains(wanted) ? wanted : null;
        if (contents.isEmpty()) return null;
        for (RecipeMap<?> map : otherModes) {
            if (RecipeMaps.canMake(map, contents) && RecipeFit.best(map, contents, machine) != RecipeFit.NONE) {
                return map;
            }
        }
        return null;
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
        // A click on a full AE2 interface adds a capacity card first: it is full only with all cards in.
        if (s.freeSlots == 0) s.takesCard = CapacityCards.canTakeCard(view);
    }

    private static void scoreForCategory(ProviderScore s, Collection<RecipeMap<?>> maps, RecipeMap<?> wanted,
        PatternContents contents, MachineInventory machine, List<ItemStack> nonConsumables) {
        if (!maps.contains(wanted)) return;
        s.flags |= ProviderEntry.RECIPE_MATCH;
        s.matchedMap = wanted;
        RecipeFit fit = contents.isEmpty() ? RecipeFit.NONE : RecipeFit.best(wanted, contents, machine);
        if (fit.fits()) {
            s.flags |= ProviderEntry.SETUP_FITS;
            s.hasSpareSetup = fit == RecipeFit.WITH_SPARE_SETUP;
        } else if (fit == RecipeFit.CONFLICTING) {
            s.flags |= ProviderEntry.SETUP_CONFLICTS;
        } else {
            // GT lookup failed (odd pattern amounts, or the assembly line, whose recipe comes from a data stick):
            // fall back to the items NEI marked as not consumed that this machine keeps in its inputs.
            List<ItemStack> setup = inputSetup(nonConsumables, wanted);
            if (!setup.isEmpty()) {
                s.flags |= holdsAll(machine, setup) ? ProviderEntry.SETUP_FITS : ProviderEntry.SETUP_CONFLICTS;
            }
        }
    }

    /**
     * Circuits, molds and lenses among NEI's non-consumables: things recipes of the map take from the input buses.
     * The assembly line's data stick sits in a data hatch the provider never sees.
     */
    private static List<ItemStack> inputSetup(List<ItemStack> nonConsumables, RecipeMap<?> map) {
        List<ItemStack> setup = new ArrayList<>();
        for (ItemStack st : nonConsumables) {
            if (RecipeMaps.isNonConsumable(st, Collections.singletonList(map))) setup.add(st);
        }
        return setup;
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
                // The first fit, or an exact one after it: the map the machine will run the pattern in.
                if (!s.has(ProviderEntry.SETUP_FITS) || fit == RecipeFit.EXACT) s.matchedMap = map;
                s.flags = (s.flags & ~ProviderEntry.SETUP_CONFLICTS) | ProviderEntry.RECIPE_MATCH
                    | ProviderEntry.SETUP_FITS;
                s.hasSpareSetup = fit == RecipeFit.WITH_SPARE_SETUP;
                if (fit == RecipeFit.EXACT) break;
                continue;
            }
            if (s.has(ProviderEntry.SETUP_FITS)) continue; // a loose fit in another map beats a mismatch
            if (fit == RecipeFit.CONFLICTING) {
                s.flags |= ProviderEntry.RECIPE_MATCH | ProviderEntry.SETUP_CONFLICTS;
                if (s.matchedMap == null) s.matchedMap = map;
            }
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

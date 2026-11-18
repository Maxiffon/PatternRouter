package patternrouter.server;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.item.ItemStack;

import appeng.api.util.IInterfaceViewable;
import patternrouter.Net;

/** One provider as checked for the pattern; becomes a list row. */
final class ProviderScore {

    final IInterfaceViewable view;
    ItemStack icon;
    String name;
    /** AE2's caption (CRIBs), as chat JSON; empty if none. */
    String suffix;
    int freeSlots;
    /** Net.Entry flags. */
    int flags;
    int dim, x, y, z;
    /** Setup items of the machine (deduplicated) for the client-side caption. */
    final List<ItemStack> setupItems = new ArrayList<>();
    boolean feedsUnformedMultiblock;
    /** The machine behind this provider is known (its recipe maps were found). */
    boolean machineSeen;
    /** Fits, but also holds circuits/molds this recipe doesn't use (multi-circuit setups). */
    boolean hasSpareSetup;
    final List<Integer> sameOutputSlots = new ArrayList<>();

    ProviderScore(IInterfaceViewable view) {
        this.view = view;
    }

    boolean has(int flag) {
        return (flags & flag) != 0;
    }

    Net.Entry toEntry() {
        Net.Entry e = new Net.Entry();
        e.icon = icon;
        e.name = name;
        e.suffix = suffix;
        e.free = freeSlots;
        e.flags = flags;
        e.dim = dim;
        e.x = x;
        e.y = y;
        e.z = z;
        e.config.addAll(setupItems);
        return e;
    }
}

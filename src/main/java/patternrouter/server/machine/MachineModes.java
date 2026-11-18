package patternrouter.server.machine;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.recipe.RecipeMap;

/**
 * Recipe maps of the machines behind a provider. A multiblock with modes (Precise Assembler: precise / plain
 * assembling, distillation towers, GT++ wash plant...) lists every mode's map but runs only the current one.
 */
public final class MachineModes {

    /** Maps the machines run as they are set now. */
    public final Set<RecipeMap<?>> current = new HashSet<>();
    /** Maps of modes the machines are not set to: the player has to switch the mode first. */
    public final Set<RecipeMap<?>> otherModes = new HashSet<>();
    /** The machines themselves (multiblock controllers, single-block machines). */
    public final List<IMetaTileEntity> machines = new ArrayList<>();

    /** A machine's maps; {@code now} is what its current mode runs, null for machines without modes. */
    void add(Collection<RecipeMap<?>> available, RecipeMap<?> now) {
        if (now == null || available.size() < 2 || !available.contains(now)) {
            current.addAll(available);
            otherModes.removeAll(available);
            return;
        }
        current.add(now);
        otherModes.remove(now);
        for (RecipeMap<?> map : available) if (!current.contains(map)) otherModes.add(map);
    }

    public boolean isEmpty() {
        return current.isEmpty() && otherModes.isEmpty();
    }

    public Set<RecipeMap<?>> all() {
        Set<RecipeMap<?>> all = new HashSet<>(current);
        all.addAll(otherModes);
        return all;
    }
}

package patternrouter.server;

import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.inventory.Container;
import net.minecraft.item.ItemStack;

import appeng.api.util.IInterfaceViewable;

/** The last list sent to each player: what its rows and Extract buttons point at. */
final class PlayerSessions {

    private static final Map<UUID, ShownList> SHOWN = new HashMap<>();
    private static int nextToken = 1;

    private PlayerSessions() {}

    /** A list sent to the player. */
    static final class ShownList {

        final int token;
        final WeakReference<Container> container;
        /** The pattern the list was built for; an upload of anything else is refused. */
        final ItemStack pattern;
        final List<WeakReference<IInterfaceViewable>> targets;
        final List<ConflictRef> conflicts;
        /** What is being placed (see RememberedChoices.keyFor), remembered on upload. */
        final String choiceKey;

        ShownList(int token, Container container, ItemStack pattern, List<WeakReference<IInterfaceViewable>> targets,
            List<ConflictRef> conflicts, String choiceKey) {
            this.token = token;
            this.container = new WeakReference<>(container);
            this.pattern = pattern.copy();
            this.targets = targets;
            this.conflicts = conflicts;
            this.choiceKey = choiceKey;
        }
    }

    /** Existing pattern with the same output: where it is and which one (re-checked before extraction). */
    static final class ConflictRef {

        final WeakReference<IInterfaceViewable> view;
        final int slot;
        final ItemStack pattern;

        ConflictRef(IInterfaceViewable view, int slot, ItemStack pattern) {
            this.view = new WeakReference<>(view);
            this.slot = slot;
            this.pattern = pattern.copy();
        }
    }

    static int nextToken() {
        return nextToken++;
    }

    static void put(UUID player, ShownList list) {
        SHOWN.put(player, list);
    }

    static ShownList get(UUID player) {
        return SHOWN.get(player);
    }

    static void forget(UUID player) {
        SHOWN.remove(player);
    }
}

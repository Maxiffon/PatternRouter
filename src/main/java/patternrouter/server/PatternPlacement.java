package patternrouter.server;

import java.lang.ref.WeakReference;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;

import appeng.api.networking.IGrid;
import appeng.api.util.IInterfaceViewable;
import appeng.container.implementations.ContainerPatternTerm;
import patternrouter.PatternRouter;
import patternrouter.common.Items;
import patternrouter.net.ActionResultMessage;
import patternrouter.net.PatternRouterNetwork;
import patternrouter.server.PlayerSessions.ConflictRef;
import patternrouter.server.PlayerSessions.ShownList;
import patternrouter.server.ae2.GridAccess;
import patternrouter.server.ae2.Patterns;
import patternrouter.server.ae2.ProviderCompat;

/**
 * Clicks on a sent list: put the terminal's pattern into row #index, or move conflicting pattern #index into the
 * player's inventory. Nothing the client sends is trusted beyond picking a row of the list the server made.
 */
public final class PatternPlacement {

    private PatternPlacement() {}

    public static void upload(EntityPlayerMP player, int token, int index) {
        ShownList shown = shownList(player, token);
        if (shown == null || index < 0 || index >= shown.targets.size()) {
            reply(player, ActionResultMessage.GONE, "");
            return;
        }
        ContainerPatternTerm term = (ContainerPatternTerm) player.openContainer;
        IGrid grid = GridAccess.gridOf(term);
        IInterfaceViewable view = checkedProvider(player, shown.targets.get(index), grid);
        if (view == null) return;
        IInventory patternSlots = Patterns.patternSlots(term);
        ItemStack pattern = Patterns.encodedPattern(term);
        if (patternSlots == null || pattern == null) {
            reply(player, ActionResultMessage.NO_PATTERN, ProviderCompat.rawName(view));
            return;
        }
        // The list was made for another pattern (re-encoded since, or changed by another player at this terminal).
        if (!Items.sameItem(pattern, shown.pattern)) {
            reply(player, ActionResultMessage.GONE, "");
            return;
        }
        int slot = freeSlot(view, pattern);
        boolean cardAdded = false;
        if (slot < 0) {
            int card = CapacityCards.install(view, grid, term, player);
            if (card != CapacityCards.INSTALLED) {
                reply(player, card, ProviderCompat.rawName(view));
                return;
            }
            cardAdded = true;
            slot = freeSlot(view, pattern);
            if (slot < 0) {
                reply(player, ActionResultMessage.FULL, ProviderCompat.rawName(view));
                return;
            }
        }
        IInventory target = view.getPatterns();
        ItemStack one = pattern.copy();
        one.stackSize = 1;
        target.setInventorySlotContents(slot, one);
        target.markDirty();
        pattern.stackSize--;
        patternSlots.setInventorySlotContents(Patterns.ENCODED_OUTPUT_SLOT, pattern.stackSize > 0 ? pattern : null);
        term.detectAndSendChanges();
        rememberChoice(player, shown.choiceKey, view);
        reply(
            player,
            cardAdded ? ActionResultMessage.OK_WITH_CARD : ActionResultMessage.OK,
            ProviderCompat.rawName(view));
        PatternRouter.LOG
            .debug("{} uploaded a pattern to {}", player.getCommandSenderName(), ProviderCompat.rawName(view));
    }

    public static void extract(EntityPlayerMP player, int token, int index) {
        ShownList shown = shownList(player, token);
        if (shown == null || index < 0 || index >= shown.conflicts.size()) {
            reply(player, ActionResultMessage.GONE, "");
            return;
        }
        Container open = player.openContainer;
        ConflictRef conflict = shown.conflicts.get(index);
        IInterfaceViewable view = checkedProvider(
            player,
            conflict.view,
            GridAccess.gridOf((ContainerPatternTerm) open));
        if (view == null) return;
        IInventory inv = view.getPatterns();
        ItemStack now = inv != null && conflict.slot < inv.getSizeInventory() ? inv.getStackInSlot(conflict.slot)
            : null;
        // The slot must still hold exactly the pattern we listed; otherwise someone changed it.
        if (now == null || !ItemStack.areItemStacksEqual(now, conflict.pattern)) {
            reply(player, ActionResultMessage.GONE, ProviderCompat.rawName(view));
            return;
        }
        // Room first: in creative mode a full inventory "takes" the stack and deletes it.
        if (!hasRoomFor(player.inventory, now)) {
            reply(player, ActionResultMessage.INVENTORY_FULL, ProviderCompat.rawName(view));
            return;
        }
        ItemStack taken = now.copy();
        inv.setInventorySlotContents(conflict.slot, null);
        inv.markDirty();
        try {
            player.inventory.addItemStackToInventory(taken);
        } catch (Throwable t) {
            // Whatever did not reach the player goes back; a half-added stack is not lost either way.
            PatternRouter.LOG
                .warn("Pattern Router could not give the pattern back to {}", player.getCommandSenderName(), t);
        }
        if (taken.stackSize > 0) {
            // Inventory full (or only part fit): what is left goes back into the slot.
            inv.setInventorySlotContents(conflict.slot, taken.stackSize == now.stackSize ? now : taken);
            inv.markDirty();
            player.inventoryContainer.detectAndSendChanges();
            reply(player, ActionResultMessage.INVENTORY_FULL, ProviderCompat.rawName(view));
            return;
        }
        player.inventoryContainer.detectAndSendChanges();
        open.detectAndSendChanges();
        reply(player, ActionResultMessage.EXTRACTED, ProviderCompat.rawName(view));
    }

    /** The whole stack fits into the player's main inventory: free slots plus room on equal stacks. */
    static boolean hasRoomFor(net.minecraft.entity.player.InventoryPlayer inv, ItemStack stack) {
        int left = stack.stackSize;
        int limit = Math.min(stack.getMaxStackSize(), inv.getInventoryStackLimit());
        for (ItemStack s : inv.mainInventory) {
            if (s == null) left -= limit;
            else if (stack.isStackable() && s.getItem() == stack.getItem()
                && (!s.getHasSubtypes() || s.getItemDamage() == stack.getItemDamage())
                && ItemStack.areItemStackTagsEqual(s, stack)) left -= Math.max(0, limit - s.stackSize);
            if (left <= 0) return true;
        }
        return false;
    }

    /** The player's list with this token, if it is the one shown in the pattern terminal still open; else null. */
    private static ShownList shownList(EntityPlayerMP player, int token) {
        ShownList shown = PlayerSessions.get(player.getUniqueID());
        Container open = player.openContainer;
        if (shown == null || shown.token != token
            || shown.container.get() != open
            || !(open instanceof ContainerPatternTerm)) return null;
        return shown;
    }

    /**
     * The provider of a row, if it still exists, is loaded, is in the terminal's networks and the player may build
     * there; otherwise the player is told why and null is returned.
     */
    private static IInterfaceViewable checkedProvider(EntityPlayerMP player, WeakReference<IInterfaceViewable> ref,
        IGrid terminalGrid) {
        IInterfaceViewable view = ref.get();
        if (view == null || terminalGrid == null
            || !ProviderCompat.isLoaded(view)
            || !GridAccess.isInTerminalNetworks(view, terminalGrid)) {
            reply(player, ActionResultMessage.GONE, "");
            return null;
        }
        if (!GridAccess.canBuild(terminalGrid, player) || !GridAccess.canBuildAt(view, player)) {
            reply(player, ActionResultMessage.DENIED, ProviderCompat.rawName(view));
            return null;
        }
        return view;
    }

    private static int freeSlot(IInterfaceViewable view, ItemStack pattern) {
        IInventory target = view.getPatterns();
        if (target == null) return -1;
        int slots = Math.min(target.getSizeInventory(), ProviderCompat.numSlots(view));
        for (int i = 0; i < slots; i++) {
            if (target.getStackInSlot(i) == null && target.isItemValidForSlot(i, pattern)) return i;
        }
        return -1;
    }

    private static void rememberChoice(EntityPlayerMP player, String key, IInterfaceViewable view) {
        int[] position = ProviderCompat.position(view);
        RememberedChoices choices = key == null || key.isEmpty() || position == null ? null : RememberedChoices.get();
        if (choices != null) choices.remember(player.getUniqueID(), key, position);
    }

    private static void reply(EntityPlayerMP player, int code, String providerName) {
        PatternRouterNetwork.channel.sendTo(new ActionResultMessage(code, providerName), player);
    }
}

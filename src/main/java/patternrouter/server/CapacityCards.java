package patternrouter.server;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;

import appeng.api.AEApi;
import appeng.api.config.Actionable;
import appeng.api.networking.IGrid;
import appeng.api.networking.security.IActionHost;
import appeng.api.networking.security.PlayerSource;
import appeng.api.networking.storage.IStorageGrid;
import appeng.api.storage.data.IAEItemStack;
import appeng.api.util.IInterfaceViewable;
import appeng.container.implementations.ContainerPatternTerm;
import appeng.helpers.IInterfaceHost;
import appeng.util.item.AEItemStack;
import patternrouter.Net;
import patternrouter.PatternRouter;
import patternrouter.server.ae2.GridAccess;
import patternrouter.server.ae2.ProviderCompat;

/** Installs a Pattern Capacity Card into a full AE2 interface: from the player's inventory, else from the network. */
final class CapacityCards {

    /** {@link #install} succeeded; any other value is a Net.Result code. */
    static final int INSTALLED = -1;

    private CapacityCards() {}

    static int install(IInterfaceViewable view, IGrid grid, ContainerPatternTerm term, EntityPlayerMP player) {
        if (!(view instanceof IInterfaceHost host)) return Net.Result.FULL; // CRIB, LMA: no upgrade slots
        try {
            IInventory upgrades = host.getInterfaceDuality()
                .getInventoryByName("upgrades");
            ItemStack card = AEApi.instance()
                .definitions()
                .materials()
                .cardPatternCapacity()
                .maybeStack(1)
                .orNull();
            if (upgrades == null || card == null) return Net.Result.FULL;
            int slot = freeUpgradeSlot(upgrades, card);
            if (slot < 0) return Net.Result.MAX_CARDS;
            if (!takeFromPlayer(player, card) && !takeFromNetwork(grid, term, player, card)) {
                return Net.Result.NO_CARD;
            }
            try {
                upgrades.setInventorySlotContents(slot, card.copy());
                upgrades.markDirty();
            } catch (Throwable t) {
                // Only if the card really did not land: the failure may come from a callback after it was placed.
                if (upgrades.getStackInSlot(slot) == null) giveBack(player, card);
                throw t;
            }
            return INSTALLED;
        } catch (Throwable t) {
            PatternRouter.LOG.debug("Cannot add a capacity card to {}: {}", ProviderCompat.rawName(view), t.toString());
            return Net.Result.FULL;
        }
    }

    /** AE2's upgrade inventory refuses cards beyond the per-type maximum: -1 then. */
    private static int freeUpgradeSlot(IInventory upgrades, ItemStack card) {
        for (int i = 0; i < upgrades.getSizeInventory(); i++) {
            if (upgrades.getStackInSlot(i) == null && upgrades.isItemValidForSlot(i, card)) return i;
        }
        return -1;
    }

    /** A card already taken but not installed: back to the player, dropped at their feet if the inventory is full. */
    private static void giveBack(EntityPlayerMP player, ItemStack card) {
        ItemStack back = card.copy();
        player.inventory.addItemStackToInventory(back);
        if (back.stackSize > 0) player.dropPlayerItemWithRandomChoice(back, false);
        player.inventoryContainer.detectAndSendChanges();
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
        if (!GridAccess.canExtract(grid, player)) return false;
        IStorageGrid storage = grid.getCache(IStorageGrid.class);
        if (storage == null) return false;
        IActionHost host = term.getTarget() instanceof IActionHost actionHost ? actionHost : null;
        IAEItemStack got = storage.getItemInventory()
            .extractItems(AEItemStack.create(card), Actionable.MODULATE, new PlayerSource(player, host));
        return got != null && got.getStackSize() >= 1;
    }
}

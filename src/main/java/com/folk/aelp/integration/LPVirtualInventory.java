package com.folk.aelp.integration;

import net.minecraft.item.ItemStack;

import com.folk.aelp.loader.BlocksLoader;
import com.folk.aelp.tiles.TileLPBridge;

import appeng.api.AEApi;
import appeng.api.config.AccessRestriction;
import appeng.api.config.Actionable;
import appeng.api.networking.security.BaseActionSource;
import appeng.api.storage.IMEInventoryHandler;
import appeng.api.storage.StorageChannel;
import appeng.api.storage.data.IAEItemStack;
import appeng.api.storage.data.IAEStackType;
import appeng.api.storage.data.IItemList;
import appeng.util.item.AEItemStackType;

/**
 * Read-only projection of an LP network inside an ME network.
 * <p>
 * It answers {@code getAvailableItems} with the current LP snapshot and refuses every other
 * operation: extraction always returns {@code null}, injection is rejected. That is what makes the
 * "virtual LP storage" impossible to pull out of the ME network, impossible to output through a bus,
 * and impossible to store anywhere.
 * <p>
 * The snapshot is refreshed by {@link TileLPBridge}; this class never touches the LP network itself.
 */
public final class LPVirtualInventory implements IMEInventoryHandler<IAEItemStack> {

    private final TileLPBridge bridge;

    public LPVirtualInventory(TileLPBridge bridge) {
        this.bridge = bridge;
    }

    // ------------------------------------------------------------------
    // read-only content
    // ------------------------------------------------------------------

    @Override
    public IItemList<IAEItemStack> getAvailableItems(IItemList<IAEItemStack> out, int iteration) {
        for (IAEItemStack stack : this.bridge.getVirtualSnapshot()) {
            if (stack == null) {
                continue;
            }
            IAEItemStack copy = stack.copy();
            copy.setCraftable(false);
            out.add(copy);
        }
        return out;
    }

    @Override
    public IAEItemStack getAvailableItem(IAEItemStack request, int iteration) {
        if (request == null) {
            return null;
        }
        for (IAEItemStack stack : this.bridge.getVirtualSnapshot()) {
            if (stack != null && stack.isSameType(request)) {
                return stack.copy();
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // never extractable, never injectable
    // ------------------------------------------------------------------

    @Override
    public IAEItemStack extractItems(IAEItemStack request, Actionable mode, BaseActionSource src) {
        // The projection is a view, not a store: nothing can ever be taken out of it.
        return null;
    }

    @Override
    public IAEItemStack injectItems(IAEItemStack input, Actionable type, BaseActionSource src) {
        // Refuse everything, so items can never be "stored" as virtual LP stock.
        return input;
    }

    @Override
    public AccessRestriction getAccess() {
        return AccessRestriction.READ;
    }

    @Override
    public boolean isPrioritized(IAEItemStack input) {
        return false;
    }

    @Override
    public boolean canAccept(IAEItemStack input) {
        return false;
    }

    @Override
    public int getPriority() {
        return this.bridge.getStoragePriority();
    }

    @Override
    public int getSlot() {
        return 0;
    }

    @Override
    public boolean validForPass(int i) {
        return true;
    }

    @Override
    public boolean getSticky() {
        return false;
    }

    @Override
    public boolean isAutoCraftingInventory() {
        return false;
    }

    @Override
    public AccessRestriction getReshuffleAccess() {
        return AccessRestriction.NO_ACCESS;
    }

    // ------------------------------------------------------------------
    // channel plumbing
    // ------------------------------------------------------------------

    @Override
    public StorageChannel getChannel() {
        return StorageChannel.ITEMS;
    }

    @Override
    public IAEStackType<?> getStackType() {
        return AEItemStackType.ITEM_STACK_TYPE;
    }

    // ------------------------------------------------------------------
    // helper
    // ------------------------------------------------------------------

    /** Builds the virtual AE stack that stands for {@code real} with the given amount. */
    public static IAEItemStack createVirtualStack(ItemStack real, long amount) {
        if (real == null || amount <= 0) {
            return null;
        }
        ItemStack virtual = BlocksLoader.LP_VIRTUAL_STORAGE.create(real);
        if (virtual == null) {
            return null;
        }
        IAEItemStack stack = AEApi.instance()
            .storage()
            .createItemStack(virtual);
        if (stack == null) {
            return null;
        }
        stack.setStackSize(amount);
        stack.setCraftable(false);
        return stack;
    }
}

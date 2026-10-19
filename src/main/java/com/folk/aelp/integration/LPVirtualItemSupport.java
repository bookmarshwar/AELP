package com.folk.aelp.integration;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

import com.folk.aelp.items.ItemLPVirtualStorage;

import appeng.api.storage.data.IAEItemStack;
import appeng.api.storage.data.IAEStack;

/**
 * Type test for the LP virtual projection token.
 * <p>
 * Kept free of any registry lookup so it can be called from a late mixin at any point in the tick:
 * it only checks the item class, never {@code BlocksLoader}'s static fields.
 */
public final class LPVirtualItemSupport {

    private LPVirtualItemSupport() {}

    /** True if {@code stack} is the virtual LP projection token. */
    public static boolean isVirtual(IAEStack<?> stack) {
        if (!(stack instanceof IAEItemStack itemStack)) {
            return false;
        }
        return isVirtualItem(itemStack.getItem());
    }

    /** True if {@code stack} is the virtual LP projection token. */
    public static boolean isVirtual(ItemStack stack) {
        return stack != null && isVirtualItem(stack.getItem());
    }

    /** True if {@code item} is the virtual LP projection item. */
    public static boolean isVirtualItem(Item item) {
        return item instanceof ItemLPVirtualStorage;
    }
}

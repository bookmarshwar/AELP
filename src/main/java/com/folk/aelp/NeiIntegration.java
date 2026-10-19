package com.folk.aelp;

import net.minecraft.item.ItemStack;

import com.folk.aelp.loader.BlocksLoader;

import codechicken.nei.api.API;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/**
 * Isolated NEI access so that a missing NEI installation only fails inside the caller's try/catch
 * instead of breaking {@link ClientProxy} class loading.
 */
@SideOnly(Side.CLIENT)
final class NeiIntegration {

    private NeiIntegration() {}

    static void hideVirtualItem() {
        API.hideItem(new ItemStack(BlocksLoader.LP_VIRTUAL_STORAGE, 1, 0));
    }
}

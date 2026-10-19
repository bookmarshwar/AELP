package com.folk.aelp.mixins.late;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import appeng.api.config.Actionable;
import appeng.api.networking.security.BaseActionSource;
import appeng.api.storage.IMEMonitor;
import appeng.api.storage.data.IAEStack;
import appeng.me.cluster.implementations.CraftingCPUCluster;

import com.folk.aelp.integration.LPVirtualItemSupport;

/**
 * Drops LP virtual projection tokens when a crafting CPU flushes its inventory back to the network.
 * <p>
 * {@link CraftingCPUCluster#storeItems()} runs both when a job completes and when it is cancelled. It
 * moves everything left in the CPU's {@code MECraftingInventory} into the real network. LP tokens live
 * in that inventory while a job runs, so a cancelled job would otherwise <b>store the tokens as real
 * items</b>: the terminal would then show a phantom "LP virtual" stack that can be pulled out, and
 * every cancel would add more of them.
 * <p>
 * Returning {@code null} means "fully accepted" in AE's contract, so the token is discarded instead of
 * being handed back to the CPU inventory. Real items are delegated unchanged.
 */
@Mixin(CraftingCPUCluster.class)
public class Mixin_CraftingCPUCluster_StoreItems {

    @Redirect(
        method = "storeItems",
        at = @At(
            value = "INVOKE",
            target = "Lappeng/api/storage/IMEMonitor;injectItems(Lappeng/api/storage/data/IAEStack;"
                + "Lappeng/api/config/Actionable;Lappeng/api/networking/security/BaseActionSource;)"
                + "Lappeng/api/storage/data/IAEStack;"),
        remap = false)
    @SuppressWarnings("rawtypes")
    private IAEStack<?> aelp$discardVirtualOnFlush(IMEMonitor monitor, IAEStack input, Actionable mode,
        BaseActionSource src) {
        if (LPVirtualItemSupport.isVirtual(input)) {
            // Discard: a projection token must never be written into any storage.
            return null;
        }
        return monitor.injectItems(input, mode, src);
    }
}

package com.folk.aelp.mixins.late;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.folk.aelp.integration.LPVirtualItemSupport;

import appeng.api.config.Actionable;
import appeng.api.networking.security.BaseActionSource;
import appeng.api.storage.data.IAEStack;
import appeng.me.cache.NetworkMonitor;

/**
 * Hard guarantee that an LP virtual projection token can never enter ME network storage.
 * <p>
 * {@link NetworkMonitor#injectItems} is the single choke point every item injection into a network
 * passes through: storage cells, storage buses, interfaces, import buses and the crafting CPU flush
 * all end up here. Refusing the token at this boundary means no code path — present or future — can
 * turn a read-only projection into a storable item.
 * <p>
 * This is the backstop for {@code Mixin_CraftingCPUCluster_StoreItems} and
 * {@code Mixin_MECraftingInventory_Commit}: those fix specific flows, this one closes the boundary.
 */
@Mixin(NetworkMonitor.class)
public class Mixin_NetworkMonitor_BlockVirtual {

    @Inject(method = "injectItems", at = @At("HEAD"), cancellable = true, remap = false)
    private void aelp$refuseVirtualInjection(IAEStack input, Actionable mode, BaseActionSource src,
        CallbackInfoReturnable<IAEStack> callback) {
        if (LPVirtualItemSupport.isVirtual(input)) {
            // Returning the input unchanged is AE's "nothing was accepted" result.
            callback.setReturnValue(input);
        }
    }
}

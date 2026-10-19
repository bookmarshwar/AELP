package com.folk.aelp.mixins.late;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import com.folk.aelp.integration.LPVirtualItemSupport;

import appeng.api.config.Actionable;
import appeng.api.networking.security.BaseActionSource;
import appeng.api.storage.IMEMonitor;
import appeng.api.storage.data.IAEStack;
import appeng.crafting.MECraftingInventory;

/**
 * Lets the LP virtual projection tokens satisfy the "commit" step of a crafting job.
 * <p>
 * When a crafting CPU starts a job, {@link MECraftingInventory#commit} re-extracts every planned item
 * from the <b>real</b> network. LP tokens are not real items: the projection deliberately refuses
 * every extraction, so without this hook the commit always fails and no job ever starts.
 * <p>
 * The two redirects keep the token out of all storage at the same time:
 * <ul>
 * <li>{@code extractItems} is faked as successful, so the job starts and the CPU keeps its tokens;</li>
 * <li>{@code injectItems} is swallowed (returns {@code null} = "everything accepted"), so the rollback
 * path can never push a token into the network.</li>
 * </ul>
 * Real items are never touched: both handlers delegate unchanged.
 */
@Mixin(MECraftingInventory.class)
public class Mixin_MECraftingInventory_Commit {

    @Redirect(
        method = "commit",
        at = @At(
            value = "INVOKE",
            target = "Lappeng/api/storage/IMEMonitor;extractItems(Lappeng/api/storage/data/IAEStack;"
                + "Lappeng/api/config/Actionable;Lappeng/api/networking/security/BaseActionSource;)"
                + "Lappeng/api/storage/data/IAEStack;"),
        remap = false)
    @SuppressWarnings("rawtypes")
    private IAEStack<?> aelp$fakeVirtualExtract(IMEMonitor monitor, IAEStack request, Actionable mode,
        BaseActionSource src) {
        if (LPVirtualItemSupport.isVirtual(request)) {
            // Report the token as extracted without touching any inventory.
            return request.copy();
        }
        return monitor.extractItems(request, mode, src);
    }

    @Redirect(
        method = "commit",
        at = @At(
            value = "INVOKE",
            target = "Lappeng/api/storage/IMEMonitor;injectItems(Lappeng/api/storage/data/IAEStack;"
                + "Lappeng/api/config/Actionable;Lappeng/api/networking/security/BaseActionSource;)"
                + "Lappeng/api/storage/data/IAEStack;"),
        remap = false)
    @SuppressWarnings("rawtypes")
    private IAEStack<?> aelp$swallowVirtualInject(IMEMonitor monitor, IAEStack input, Actionable mode,
        BaseActionSource src) {
        if (LPVirtualItemSupport.isVirtual(input)) {
            // null means "all accepted": the rollback path must never store a token.
            return null;
        }
        return monitor.injectItems(input, mode, src);
    }
}

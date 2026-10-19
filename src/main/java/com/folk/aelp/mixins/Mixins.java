package com.folk.aelp.mixins;

import javax.annotation.Nonnull;

import com.gtnewhorizon.gtnhmixins.builders.IMixins;
import com.gtnewhorizon.gtnhmixins.builders.MixinBuilder;

public enum Mixins implements IMixins {

    /**
     * Lets the AE crafting CPU accept LP virtual projection tokens as crafting inputs.
     * Late, because it targets AE2's internal {@code MECraftingInventory}.
     */
    LP_BRIDGE_CRAFTING_COMMIT(new MixinBuilder().setPhase(Phase.LATE)
        .addRequiredMod(TargetMods.AE2)
        .addCommonMixins("Mixin_MECraftingInventory_Commit")),

    /**
     * Drops LP tokens when a CPU flushes its inventory back to the network, so cancelling a job can
     * never turn a read-only projection into a stored item.
     */
    LP_BRIDGE_CPU_FLUSH(new MixinBuilder().setPhase(Phase.LATE)
        .addRequiredMod(TargetMods.AE2)
        .addCommonMixins("Mixin_CraftingCPUCluster_StoreItems")),

    /**
     * Boundary guard: no LP token may ever be injected into ME network storage through any path.
     */
    LP_BRIDGE_STORAGE_GUARD(new MixinBuilder().setPhase(Phase.LATE)
        .addRequiredMod(TargetMods.AE2)
        .addCommonMixins("Mixin_NetworkMonitor_BlockVirtual"));

    private final MixinBuilder builder;

    Mixins(MixinBuilder builder) {
        this.builder = builder;
    }

    @Nonnull
    @Override
    public MixinBuilder getBuilder() {
        return builder;
    }
}

package com.folk.aelp.mixins;

import javax.annotation.Nonnull;

import com.gtnewhorizon.gtnhmixins.builders.ITargetMod;
import com.gtnewhorizon.gtnhmixins.builders.TargetModBuilder;

public enum TargetMods implements ITargetMod {

    /** Applied Energistics 2, identified by mod id (late mixins only). */
    AE2(new TargetModBuilder().setModId("appliedenergistics2")),

    /** Logistics Pipes, identified by mod id (late mixins only). */
    LOGISTICSPIPES(new TargetModBuilder().setModId("LogisticsPipes"));

    private final TargetModBuilder builder;

    TargetMods(TargetModBuilder builder) {
        this.builder = builder;
    }

    @Nonnull
    @Override
    public TargetModBuilder getBuilder() {
        return builder;
    }
}

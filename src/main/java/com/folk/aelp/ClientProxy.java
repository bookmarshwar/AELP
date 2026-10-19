package com.folk.aelp;

import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;

public class ClientProxy extends CommonProxy {

    @Override
    public void preInit(FMLPreInitializationEvent event) {
        super.preInit(event);
    }

    @Override
    public void postInit(FMLPostInitializationEvent event) {
        super.postInit(event);
        hideVirtualItemFromNei();
    }

    /**
     * The LP virtual stack is an internal projection token. It must never look like a normal item a
     * player could obtain or craft with, so keep it out of NEI.
     */
    private void hideVirtualItemFromNei() {
        try {
            NeiIntegration.hideVirtualItem();
        } catch (Throwable t) {
            AELP.LOG.warn("Could not hide the LP virtual item from NEI", t);
        }
    }

    @Override
    public void registerRenderers() {
        // No custom renderers needed: the block reuses AE's interface textures.
    }
}

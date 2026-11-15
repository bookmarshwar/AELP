package com.folk.aelp.loader;

import com.folk.aelp.blocks.BlockLPBridge;
import com.folk.aelp.tiles.TileLPBridge;

import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.registry.GameRegistry;

/** Registers the bridge block and its tile entity. The items are registered in {@link ItemsLoader}. */
public class BlocksLoader {

    public static final BlockLPBridge LP_BRIDGE = new BlockLPBridge();

    public static void init(FMLPreInitializationEvent event) {
        GameRegistry.registerBlock(LP_BRIDGE, "lpBridge");
        GameRegistry.registerTileEntity(TileLPBridge.class, "aelp.lpBridge");
    }
}

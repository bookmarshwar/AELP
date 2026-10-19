package com.folk.aelp.loader;

import com.folk.aelp.blocks.BlockLPBridge;
import com.folk.aelp.items.ItemLPVirtualStorage;
import com.folk.aelp.tiles.TileLPBridge;

import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.registry.GameRegistry;

/** Registers every block, tile and item owned by AELP. */
public class BlocksLoader {

    public static final BlockLPBridge LP_BRIDGE = new BlockLPBridge();
    public static final ItemLPVirtualStorage LP_VIRTUAL_STORAGE = new ItemLPVirtualStorage();

    public BlocksLoader(FMLPreInitializationEvent event) {
        register();
    }

    private static void register() {
        GameRegistry.registerBlock(LP_BRIDGE, "lpBridge");
        GameRegistry.registerTileEntity(TileLPBridge.class, "aelp.lpBridge");
        GameRegistry.registerItem(LP_VIRTUAL_STORAGE, "lpVirtualStorage");
    }
}

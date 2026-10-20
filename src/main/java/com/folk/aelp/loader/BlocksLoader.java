package com.folk.aelp.loader;

import com.folk.aelp.blocks.BlockLPBridge;
import com.folk.aelp.items.ItemLPVirtualStorage;
import com.folk.aelp.tiles.TileLPBridge;

import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.registry.GameRegistry;

/**
 * Registers every block, tile and item owned by AELP.
 * <p>
 * Called from {@code postInit} on purpose. Registration order decides the numeric ids the global
 * registries hand out, and everything registered after us gets shifted by one. Most mods cope, but a
 * few resolve ore dictionary entries or item references during their own {@code init} and bake the
 * result straight into a recipe; if they run while the registries are still being reshuffled they can
 * end up with an empty ingredient list, which yields a recipe that exists but never matches anything.
 * <p>
 * Registering in {@code postInit} removes that race entirely: every other mod has finished its
 * {@code preInit} and {@code init} by then, so no one can observe our ids mid-flight. FML accepts
 * registrations at this point - the registries are only frozen at {@code LoadComplete}.
 */
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

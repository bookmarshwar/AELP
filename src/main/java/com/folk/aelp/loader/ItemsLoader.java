package com.folk.aelp.loader;

import com.folk.aelp.items.ItemLPVirtualStorage;

import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.registry.GameRegistry;

public class ItemsLoader {

    public static final ItemLPVirtualStorage LP_VIRTUAL_STORAGE = new ItemLPVirtualStorage();

    public static void init(FMLPreInitializationEvent event) {
        GameRegistry.registerItem(LP_VIRTUAL_STORAGE, "lpVirtualStorage");
    }

}

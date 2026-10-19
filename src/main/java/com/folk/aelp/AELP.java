package com.folk.aelp;

import net.minecraftforge.common.MinecraftForge;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.folk.aelp.integration.LPBridgeRegistry;
import com.folk.aelp.loader.BlocksLoader;

import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.SidedProxy;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartingEvent;
import cpw.mods.fml.common.event.FMLServerStoppedEvent;

/**
 * AE &lt;-&gt; Logistics Pipes bridge.
 * <p>
 * Exposes the LP stock to AE as a read-only virtual projection, turns it into auto-crafting targets
 * through generated patterns, and lets the physical LP deliveries flow back into the ME network.
 */
@Mod(
    modid = AELP.MODID,
    version = Tags.VERSION,
    name = AELP.MODNAME,
    acceptedMinecraftVersions = "[1.7.10]",
    dependencies = "required-after:appliedenergistics2;required-after:LogisticsPipes")
public class AELP {

    public static final String MODID = "aelp";
    public static final String MODNAME = "AE-LP Bridge";

    public static final Logger LOG = LogManager.getLogger(MODID);

    @SidedProxy(clientSide = "com.folk.aelp.ClientProxy", serverSide = "com.folk.aelp.CommonProxy")
    public static CommonProxy proxy;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        proxy.preInit(event);
        new BlocksLoader(event);
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        proxy.init(event);
        MinecraftForge.EVENT_BUS.register(this);
    }

    @Mod.EventHandler
    public void postInit(FMLPostInitializationEvent event) {
        proxy.postInit(event);
    }

    @Mod.EventHandler
    public void serverStarting(FMLServerStartingEvent event) {
        // Router IDs restart from zero in every world, so stale reservations must never survive a reload.
        LPBridgeRegistry.reset("server starting");
        proxy.serverStarting(event);
    }

    @Mod.EventHandler
    public void serverStopped(FMLServerStoppedEvent event) {
        LPBridgeRegistry.reset("world unload");
    }
}

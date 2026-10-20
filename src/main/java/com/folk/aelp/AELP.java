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
 * <p>
 * <b>Registration happens in {@code postInit}, and that is deliberate.</b> Registering blocks and items
 * shifts the numeric ids the global registries hand out for everything registered afterwards. A mod
 * that resolves ore dictionary entries or item references during its own {@code init} can then bake a
 * broken ingredient list into a recipe - in practice a Thaumcraft arcane recipe that existed but never
 * matched anything, plus a crash when its research page was drawn. Doing it in {@code postInit} means
 * every other mod has finished initialising before our ids exist, so nothing can observe them mid-flight
 * regardless of what this mod is called or where it lands in the load order.
 */
@Mod(
    modid = AELP.MODID,
    version = Tags.VERSION,
    name = AELP.MODNAME,
    acceptedMinecraftVersions = "[1.7.10]",
    dependencies = "required-after:appliedenergistics2;required-after:LogisticsPipes")
public class AELP {

    public static final String MODID = "lpae";
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
        // Registered here, not in preInit: see the class javadoc.

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

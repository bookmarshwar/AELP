package com.folk.aelp;

import java.io.File;

import net.minecraftforge.common.config.Configuration;

/** Mod configuration. All keys live in the {@code aelp} category. */
public class Config {

    private static final String CATEGORY = "aelp";
    private static final String CRAFTING = "aelp.crafting";

    // ----- projection -----

    public static boolean enabled = true;
    public static int refreshInterval = 20;
    public static int storagePriority = 0;
    public static double idlePowerUsage = 1.0D;
    public static boolean debugLog = false;

    // ----- crafting -----

    public static int patternPriority = 1;
    public static int maxPushesPerTick = 16;
    public static int requestEnergyCost = 15;

    public static void synchronizeConfiguration(File configFile) {
        Configuration configuration = new Configuration(configFile);

        enabled = configuration
            .getBoolean("enabled", CATEGORY, enabled, "Enable the LP -> AE bridge block.");

        refreshInterval = configuration.getInt(
            "refreshInterval",
            CATEGORY,
            refreshInterval,
            1,
            1200,
            "Ticks between two LP inventory snapshots. Higher = less LP network load.");

        storagePriority = configuration.getInt(
            "storagePriority",
            CATEGORY,
            storagePriority,
            -1000,
            1000,
            "Priority of the virtual inventory. The projection is read-only, so this only affects AE sorting.");

        // Note: Forge's Configuration.get(category, key, ...) has the opposite argument order of
        // getBoolean/getInt/getString(name, category, ...). Keep (category, key) here.
        idlePowerUsage = configuration
            .get(CATEGORY, "idlePowerUsage", idlePowerUsage, "AE idle power drain of the bridge block.")
            .getDouble(idlePowerUsage);

        debugLog = configuration
            .getBoolean("debugLog", CATEGORY, debugLog, "Log LP snapshot changes and requests (spammy, debugging only).");

        patternPriority = configuration.getInt(
            "patternPriority",
            CRAFTING,
            patternPriority,
            -1000,
            1000,
            "Priority of the generated LP patterns. Only decides which recipe wins when an item can both be "
                + "crafted by an AE machine and bought from LP. It never overrides stored items.");

        maxPushesPerTick = configuration.getInt(
            "maxPushesPerTick",
            CRAFTING,
            maxPushesPerTick,
            1,
            4096,
            "Maximum number of LP crafts the bridge accepts per tick. Lower values batch LP requests more "
                + "aggressively and reduce LP network load.");

        requestEnergyCost = configuration.getInt(
            "requestEnergyCost",
            CRAFTING,
            requestEnergyCost,
            0,
            10000,
            "LP energy charged for each LP request issued by the bridge.");

        if (configuration.hasChanged()) {
            configuration.save();
        }
    }
}

package com.folk.aelp.integration;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.WeakHashMap;

import com.folk.aelp.AELP;
import com.folk.aelp.tiles.TileLPBridge;

/**
 * Server-side registry guaranteeing that a single LP network is projected into AE at most once.
 * <p>
 * Two bridges on the same LP network would report the same stock twice (double counting in the
 * terminal, and later duplicate LP requests), so the second one refuses to publish a projection.
 * <p>
 * The registry is static, so it must be cleared when a world is left. LP hands out router simple IDs
 * starting from 1 for every world, and a stale entry would otherwise make the first bridge of the next
 * world look like a duplicate and stay dead until the game restarts.
 */
public final class LPBridgeRegistry {

    /** Keys are held weakly through their owner tile, so an unloaded world can never pin the registry. */
    private static final Map<String, TileLPBridge> OWNERS = new HashMap<>();
    private static final Map<TileLPBridge, String> OWNED_KEYS = new WeakHashMap<>();

    private LPBridgeRegistry() {}

    /** Builds the registry key for one LP network in one dimension. */
    public static String key(int dimensionId, int routerId) {
        return dimensionId + ":" + routerId;
    }

    /**
     * Claims {@code key} for {@code bridge}.
     *
     * @return true if the bridge owns the key (now or already), false if another live bridge holds it
     */
    public static synchronized boolean claim(String key, TileLPBridge bridge) {
        TileLPBridge current = OWNERS.get(key);
        if (current != null && current != bridge) {
            if (isDead(current)) {
                forget(key);
            } else {
                return false;
            }
        }
        String previousKey = OWNED_KEYS.get(bridge);
        if (previousKey != null && !previousKey.equals(key)) {
            // Drop the old key, otherwise a single tile occupying two keys would silently block one.
            if (OWNERS.get(previousKey) == bridge) {
                OWNERS.remove(previousKey);
            }
        }
        OWNERS.put(key, bridge);
        OWNED_KEYS.put(bridge, key);
        return true;
    }

    /** Releases {@code key} if it is still owned by {@code bridge}. */
    public static synchronized void release(String key, TileLPBridge bridge) {
        if (key == null) {
            return;
        }
        if (OWNERS.get(key) == bridge) {
            OWNERS.remove(key);
        }
        if (key.equals(OWNED_KEYS.get(bridge))) {
            OWNED_KEYS.remove(bridge);
        }
    }

    /**
     * Drops every claim.
     * <p>
     * Called when a world stops: router IDs are handed out again from zero in the next world, so
     * keeping the old keys around would silently disable every bridge placed afterwards.
     */
    public static synchronized void reset(String reason) {
        int size = OWNERS.size();
        OWNERS.clear();
        OWNED_KEYS.clear();
        if (size > 0) {
            AELP.LOG
                .info("AELP: cleared {} LP bridge reservations ({})", size, reason == null ? "world unload" : reason);
        }
    }

    /** Number of claimed networks, for diagnostics. */
    public static synchronized int size() {
        return OWNERS.size();
    }

    private static void forget(String key) {
        TileLPBridge dead = OWNERS.remove(key);
        if (dead != null) {
            OWNED_KEYS.remove(dead);
        }
    }

    private static boolean isDead(TileLPBridge tile) {
        return tile.isInvalid() || tile.getWorldObj() == null;
    }

    /** Exposed for diagnostics: drops claims whose tile is gone. */
    public static synchronized int prune() {
        int removed = 0;
        Iterator<Map.Entry<String, TileLPBridge>> it = OWNERS.entrySet()
            .iterator();
        while (it.hasNext()) {
            Map.Entry<String, TileLPBridge> entry = it.next();
            if (isDead(entry.getValue())) {
                OWNED_KEYS.remove(entry.getValue());
                it.remove();
                removed++;
            }
        }
        return removed;
    }
}

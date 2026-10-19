package com.folk.aelp.tiles;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.IInventory;
import net.minecraft.inventory.InventoryCrafting;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraftforge.common.util.ForgeDirection;

import appeng.api.AEApi;
import appeng.api.config.AccessRestriction;
import appeng.api.config.Actionable;
import appeng.api.networking.GridFlags;
import appeng.api.networking.crafting.CraftingItemList;
import appeng.api.networking.crafting.ICraftingCPU;
import appeng.api.networking.crafting.ICraftingGrid;
import appeng.api.networking.crafting.ICraftingPatternDetails;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.networking.crafting.ICraftingProviderHelper;
import appeng.api.networking.events.MENetworkCellArrayUpdate;
import appeng.api.networking.events.MENetworkCraftingPatternChange;
import appeng.api.networking.security.MachineSource;
import appeng.api.networking.storage.IStorageGrid;
import appeng.api.storage.ICellContainer;
import appeng.api.storage.IMEInventory;
import appeng.api.storage.IMEInventoryHandler;
import appeng.api.storage.IMEMonitor;
import appeng.api.storage.data.IAEItemStack;
import appeng.api.storage.data.IAEStack;
import appeng.api.storage.data.IAEStackType;
import appeng.api.storage.data.IItemList;
import appeng.me.GridAccessException;
import appeng.me.cluster.implementations.CraftingCPUCluster;
import appeng.me.helpers.AENetworkProxy;
import appeng.tile.TileEvent;
import appeng.tile.events.TileEventType;
import appeng.tile.grid.AENetworkTile;
import appeng.util.IterationCounter;
import appeng.util.inv.MEInventoryCrafting;
import appeng.util.item.AEItemStackType;

import logisticspipes.pipes.PipeItemsRequestLogistics;
import logisticspipes.pipes.basic.CoreUnroutedPipe;
import logisticspipes.pipes.basic.LogisticsBlockGenericPipe;
import logisticspipes.request.RequestTree;
import logisticspipes.request.RequestTree.ActiveRequestType;
import logisticspipes.utils.item.ItemIdentifier;
import logisticspipes.utils.item.ItemIdentifierStack;
import logisticspipes.utils.tuples.Pair;

import com.folk.aelp.AELP;
import com.folk.aelp.Config;
import com.folk.aelp.integration.LPBridgeRegistry;
import com.folk.aelp.integration.LPVirtualInventory;
import com.folk.aelp.integration.LPVirtualItemSupport;
import com.folk.aelp.items.ItemLPVirtualStorage;
import com.folk.aelp.loader.BlocksLoader;

/**
 * Bridges one LP network into one ME network.
 * <p>
 * Two roles:
 * <ul>
 * <li><b>Read-only projection</b> ({@link ICellContainer}): publishes the LP stock as virtual items
 * that can be seen in terminals but never extracted, output or stored.</li>
 * <li><b>Crafting provider</b> ({@link ICraftingProvider}): registers one processing pattern per LP
 * item, so "request item X" can fall back to "buy X from LP" when the ME network runs out.</li>
 * </ul>
 * Physical LP deliveries land in the block's item buffer and are pushed into the ME network every
 * tick, where the waiting crafting CPU picks them up.
 * <p>
 * The LP snapshot is refreshed every {@code lpBridge.refreshInterval} ticks; AE is only notified about
 * the difference so terminals stay accurate without hammering the LP network.
 */
public class TileLPBridge extends AENetworkTile implements ICellContainer, ICraftingProvider, IInventory {

    /** Amount of slots used as the physical LP delivery target. */
    public static final int BUFFER_SIZE = 9;

    private static final String NBT_BUFFER = "lpBridgeBuffer";
    private static final String NBT_SLOT = "Slot";
    private static final String NBT_STACK = "Stack";

    private final Map<ItemIdentifier, Long> lpAvailable = new HashMap<>();
    /**
     * Immutable snapshot replaced as a whole on every refresh. AE's crafting calculator reads it from a
     * separate thread, so it must never be mutated in place.
     */
    private volatile List<IAEItemStack> virtualSnapshot = Collections.emptyList();
    /** Patterns for the current item set. Rebuilt only when the set of LP items changes. */
    private volatile List<LPRequestPatternDetails> patterns = Collections.emptyList();
    private final ItemStack[] buffer = new ItemStack[BUFFER_SIZE];
    private final LPVirtualInventory virtualInventory = new LPVirtualInventory(this);

    private String claimedKey;
    private boolean networkConflict;
    private boolean needsRefresh = true;
    private boolean wasActive;
    private int tickCounter;
    private int pushesThisTick;
    private int purgeCounter;
    private int drainFailures;

    // ------------------------------------------------------------------
    // AE grid wiring
    // ------------------------------------------------------------------

    @Override
    protected AENetworkProxy createProxy() {
        AENetworkProxy proxy = new AENetworkProxy(this, "proxy", new ItemStack(BlocksLoader.LP_BRIDGE), true);
        proxy.setIdlePowerUsage(Config.idlePowerUsage);
        proxy.setFlags(GridFlags.REQUIRE_CHANNEL);
        return proxy;
    }

    // ------------------------------------------------------------------
    // ICellContainer: the read-only projection
    // ------------------------------------------------------------------

    @Override
    public List<IMEInventoryHandler> getCellArray(IAEStackType<?> type) {
        if (type != AEItemStackType.ITEM_STACK_TYPE || this.networkConflict) {
            return Collections.emptyList();
        }
        return Collections.singletonList(this.virtualInventory);
    }

    @Override
    public int getPriority() {
        return Config.storagePriority;
    }

    @Override
    public void saveChanges(IMEInventory cellInventory) {
        // The projection holds no persistent data of its own.
    }

    @Override
    public AccessRestriction getReshuffleAccess() {
        // A reshuffler must never try to move items through the projection.
        return AccessRestriction.NO_ACCESS;
    }

    // ------------------------------------------------------------------
    // ICraftingProvider: "LP has stock" -> "X can be crafted"
    // ------------------------------------------------------------------

    @Override
    public void provideCrafting(ICraftingProviderHelper craftingTracker) {
        if (!Config.enabled || this.networkConflict) {
            return;
        }
        for (LPRequestPatternDetails pattern : this.patterns) {
            craftingTracker.addCraftingOption(this, pattern);
        }
    }

    @Override
    public boolean pushPattern(ICraftingPatternDetails patternDetails, InventoryCrafting table) {
        if (!(patternDetails instanceof LPRequestPatternDetails pattern)) {
            return false;
        }
        if (!Config.enabled || this.networkConflict || isBusy()) {
            return false;
        }

        long amount = countVirtualTokens(table);
        if (amount <= 0) {
            return false;
        }

        ItemStack real = pattern.getRealOutput();
        ItemIdentifier identifier;
        try {
            identifier = ItemIdentifier.get(real);
        } catch (Exception e) {
            return false;
        }
        if (identifier == null) {
            return false;
        }

        if (!requestFromLp(identifier, amount)) {
            return false;
        }
        this.pushesThisTick++;
        // The LP order is placed, so the projection must drop right now: waiting for the next tick (or
        // the next LP snapshot) would let another job plan for the same stock and oversell LP.
        refreshProjection();
        return true;
    }

    @Override
    public boolean isBusy() {
        if (!Config.enabled || this.networkConflict) {
            return true;
        }
        // Backpressure: stop accepting LP crafts once the per-tick budget is spent.
        return this.pushesThisTick >= Math.max(1, Config.maxPushesPerTick);
    }

    @Override
    public ItemStack getCrafterIcon() {
        return new ItemStack(BlocksLoader.LP_BRIDGE);
    }

    /**
     * Issues a Provide-only LP request (never triggers LP's own crafting).
     * <p>
     * {@code acceptPartial} is false, so the request is either fully promised (and then ordered) or
     * not ordered at all: a partial result never leaves half an order behind.
     */
    private boolean requestFromLp(ItemIdentifier identifier, long amount) {
        if (amount <= 0 || amount > Integer.MAX_VALUE) {
            return false;
        }
        PipeItemsRequestLogistics pipe = findPipe();
        if (pipe == null || pipe.stillNeedReplace()) {
            return false;
        }
        int cost = Config.requestEnergyCost;
        if (cost > 0 && !pipe.useEnergy(cost)) {
            return false;
        }
        try {
            ItemIdentifierStack request = identifier.makeStack((int) amount);
            int promised = RequestTree.request(
                request,
                pipe,
                null,
                false,
                false,
                false,
                false,
                EnumSet.of(ActiveRequestType.Provide),
                null);
            if (promised == amount) {
                if (Config.debugLog) {
                    AELP.LOG.info(
                        "AELP: requested {} x{} from LP at ({}, {}, {})",
                        identifier.getFriendlyName(),
                        amount,
                        this.xCoord,
                        this.yCoord,
                        this.zCoord);
                }
                return true;
            }
            if (Config.debugLog) {
                AELP.LOG
                    .info("AELP: LP could only promise {} of {} for {}", promised, amount, identifier.getFriendlyName());
            }
            return false;
        } catch (Exception e) {
            AELP.LOG.warn("AELP: LP request failed for " + identifier.getFriendlyName(), e);
            return false;
        }
    }

    /** Sums the virtual projection tokens found in a crafting table. */
    private long countVirtualTokens(InventoryCrafting table) {
        if (table == null) {
            return 0;
        }
        long total = 0;
        for (int slot = 0; slot < table.getSizeInventory(); slot++) {
            if (table instanceof MEInventoryCrafting crafting) {
                IAEStack<?> stack = crafting.getAEStackInSlot(slot);
                if (LPVirtualItemSupport.isVirtual(stack)) {
                    total += stack.getStackSize();
                    continue;
                }
            }
            ItemStack vanilla = table.getStackInSlot(slot);
            if (LPVirtualItemSupport.isVirtual(vanilla)) {
                total += vanilla.stackSize;
            }
        }
        return total;
    }

    // ------------------------------------------------------------------
    // public API used by the projection handler
    // ------------------------------------------------------------------

    public List<IAEItemStack> getVirtualSnapshot() {
        return this.virtualSnapshot;
    }

    public int getStoragePriority() {
        return Config.storagePriority;
    }

    /** True when this bridge currently publishes a projection into AE. */
    public boolean isProjecting() {
        return !this.networkConflict;
    }

    /** True when an LP request pipe is adjacent and usable. */
    public boolean isLpConnected() {
        return findPipe() != null;
    }

    // ------------------------------------------------------------------
    // tick
    // ------------------------------------------------------------------

    @TileEvent(TileEventType.TICK)
    public void onTick() {
        if (this.worldObj == null || this.worldObj.isRemote) {
            return;
        }
        this.pushesThisTick = 0;
        syncGridActivity();
        int interval = Math.max(1, Config.refreshInterval);
        this.tickCounter++;
        if (this.needsRefresh || this.tickCounter % interval == 0) {
            this.needsRefresh = false;
            refreshFromLp();
        } else {
            // Token counts change on every crafting push, so the projection has to follow them without
            // waiting for the next LP snapshot. Only the CPU side is recomputed here; LP is not polled.
            refreshProjection();
        }
        drainBuffer();
        // Self-heal worlds that were contaminated before the storage guard existed: any LP token
        // sitting in real storage is voided so the terminal count matches the projection again.
        if (++this.purgeCounter >= 100) {
            this.purgeCounter = 0;
            purgeStoredVirtualItems();
        }
    }

    /**
     * AE only re-evaluates cell providers on {@code MENetworkCellArrayUpdate}, so the projection must
     * announce itself whenever the node gains or loses its channel/power.
     */
    private void syncGridActivity() {
        AENetworkProxy proxy = getProxy();
        boolean active = proxy != null && proxy.isActive();
        if (active == this.wasActive) {
            return;
        }
        this.wasActive = active;
        if (active) {
            // Re-publish the snapshot as soon as the node can actually contribute to the network.
            this.needsRefresh = true;
        }
        try {
            if (proxy != null && proxy.isReady() && proxy.getGrid() != null) {
                proxy.getGrid()
                    .postEvent(new MENetworkCellArrayUpdate());
            }
        } catch (Exception ignored) {
            // The grid may be rebuilding between two ticks.
        }
    }

    // ------------------------------------------------------------------
    // LP snapshot
    // ------------------------------------------------------------------

    /** Polls the LP network and updates the cached stock. Called every {@code refreshInterval} ticks. */
    private void refreshFromLp() {
        if (!Config.enabled) {
            releaseClaim();
            setNetworkConflict(false);
            applySnapshot(Collections.emptyMap());
            return;
        }

        PipeItemsRequestLogistics pipe = findPipe();
        if (pipe == null || pipe.stillNeedReplace()) {
            releaseClaim();
            setNetworkConflict(false);
            applySnapshot(Collections.emptyMap());
            return;
        }

        String key = networkKey(pipe);
        if (key == null) {
            // The LP router is not ready yet; treat it as "no LP network" instead of a conflict.
            releaseClaim();
            setNetworkConflict(false);
            applySnapshot(Collections.emptyMap());
            return;
        }

        if (!key.equals(this.claimedKey)) {
            releaseClaim();
            if (!LPBridgeRegistry.claim(key, this)) {
                // Another bridge already projects this LP network into AE.
                setNetworkConflict(true);
                applySnapshot(Collections.emptyMap());
                return;
            }
            this.claimedKey = key;
        }

        setNetworkConflict(false);
        applySnapshot(collectLpInventory(pipe));
    }

    private String networkKey(PipeItemsRequestLogistics pipe) {
        try {
            return LPBridgeRegistry.key(
                this.worldObj.provider.dimensionId,
                pipe.getRouter()
                    .getSimpleID());
        } catch (Exception e) {
            return null;
        }
    }

    private Map<ItemIdentifier, Long> collectLpInventory(PipeItemsRequestLogistics pipe) {
        Map<ItemIdentifier, Long> result = new HashMap<>();
        try {
            for (Pair<ItemIdentifier, Integer> entry : pipe.getAvailableItems()) {
                ItemIdentifier identifier = entry.getValue1();
                Integer amount = entry.getValue2();
                if (identifier == null || amount == null || amount <= 0) {
                    continue;
                }
                result.put(identifier, amount.longValue());
            }
        } catch (Exception e) {
            // The LP router can be mid-rebuild; keep the previous snapshot and retry next interval.
            AELP.LOG.warn("AELP: failed to read the LP inventory, keeping the previous snapshot", e);
            return this.lpAvailable;
        }
        return result;
    }

    private void applySnapshot(Map<ItemIdentifier, Long> next) {
        if (next == null || next.equals(this.lpAvailable)) {
            return;
        }
        Set<ItemIdentifier> previousItems = new HashSet<>(this.lpAvailable.keySet());
        this.lpAvailable.clear();
        this.lpAvailable.putAll(next);
        refreshProjection();
        rebuildPatternsIfNeeded(previousItems, next.keySet());
        if (Config.debugLog) {
            AELP.LOG.info(
                "AELP: LP projection updated at ({}, {}, {}): {} entries",
                this.xCoord,
                this.yCoord,
                this.zCoord,
                next.size());
        }
    }

    /**
     * Recomputes the projection, and publishes the difference to AE if it changed.
     * <p>
     * Two inputs drive the number AE sees:
     * <ol>
     * <li>the LP stock snapshot (refreshed every {@code refreshInterval} ticks),</li>
     * <li>the LP tokens currently held by crafting CPUs on this network.</li>
     * </ol>
     * The second one changes on every crafting push, so this must also run on the tick path and not
     * only on snapshot refresh.
     */
    private void refreshProjection() {
        Map<ItemIdentifier, Long> previous = currentShown();
        rebuildVirtualSnapshot();
        Map<ItemIdentifier, Long> after = currentShown();
        if (previous.equals(after)) {
            return;
        }
        postShownDelta(previous, after);
        if (Config.debugLog && !previous.equals(after)) {
            AELP.LOG.info(
                "AELP: projection at ({}, {}, {}) now shows {} of {} LP items",
                this.xCoord,
                this.yCoord,
                this.zCoord,
                after.size(),
                this.lpAvailable.size());
        }
    }

    /** The per-item amounts currently advertised to AE. */
    private Map<ItemIdentifier, Long> currentShown() {
        Map<ItemIdentifier, Long> shown = new HashMap<>();
        for (IAEItemStack stack : this.virtualSnapshot) {
            if (stack == null || stack.getStackSize() <= 0) {
                continue;
            }
            ItemIdentifier identifier = identifierOf(stack);
            if (identifier != null) {
                shown.merge(identifier, stack.getStackSize(), Long::sum);
            }
        }
        return shown;
    }

    private void postShownDelta(Map<ItemIdentifier, Long> previous, Map<ItemIdentifier, Long> next) {
        IStorageGrid storage = getStorageGrid();
        if (storage == null) {
            return;
        }
        Set<ItemIdentifier> keys = new HashSet<>(previous.keySet());
        keys.addAll(next.keySet());

        List<IAEStack<?>> changes = new ArrayList<>();
        for (ItemIdentifier identifier : keys) {
            long before = previous.getOrDefault(identifier, 0L);
            long after = next.getOrDefault(identifier, 0L);
            long delta = after - before;
            if (delta == 0) {
                continue;
            }
            IAEItemStack stack = createVirtualStack(identifier, Math.abs(delta));
            if (stack == null) {
                continue;
            }
            stack.setStackSize(delta);
            changes.add(stack);
        }
        if (changes.isEmpty()) {
            return;
        }
        try {
            storage.postAlterationOfStoredItems(AEItemStackType.ITEM_STACK_TYPE, changes, new MachineSource(this));
        } catch (Exception e) {
            AELP.LOG.warn("AELP: failed to publish the LP projection change", e);
        }
    }

    /**
     * Rebuilds what AE sees as "LP stock".
     * <p>
     * The number shown is <b>not</b> the raw LP stock: it is {@code LP stock - tokens currently sitting in
     * crafting CPUs}. Those tokens were already taken out of the projection by the crafting planner, so
     * reporting the raw stock would let a second job plan for the same items and oversell LP — which
     * shows up as a crafting job that waits forever for deliveries LP never makes.
     * <p>
     * Reading the CPUs is deliberate: unlike a globally maintained reservation table, the CPU inventory
     * is the single source of truth, so cancellation, failure and world reload all relax the projection
     * automatically without any bookkeeping to get wrong.
     */
    private void rebuildVirtualSnapshot() {
        Map<ItemIdentifier, Long> reserved = countCpuTokens();
        List<IAEItemStack> next = new ArrayList<>(this.lpAvailable.size());
        Set<String> seen = new HashSet<>();
        for (Map.Entry<ItemIdentifier, Long> entry : this.lpAvailable.entrySet()) {
            long available = entry.getValue() - reserved.getOrDefault(entry.getKey(), 0L);
            if (available <= 0) {
                continue;
            }
            IAEItemStack stack = createVirtualStack(entry.getKey(), available);
            if (stack != null && seen.add(stackKey(stack))) {
                next.add(stack);
            }
        }
        this.virtualSnapshot = Collections.unmodifiableList(next);
    }

    /** Stable identity key of an AE stack, used to de-duplicate the projection. */
    private static String stackKey(IAEStack<?> stack) {
        if (!(stack instanceof IAEItemStack items)) {
            return String.valueOf(System.identityHashCode(stack));
        }
        ItemStack real = items.getItemStack();
        if (real == null) {
            return String.valueOf(System.identityHashCode(stack));
        }
        ItemStack copy = real.copy();
        copy.stackSize = 1;
        NBTTagCompound tag = new NBTTagCompound();
        copy.writeToNBT(tag);
        return tag.toString();
    }

    /**
     * Sums the LP projection tokens currently held by crafting CPUs on this bridge's network.
     * <p>
     * Tokens live in {@code CraftingCPUCluster.inventory} (exposed through
     * {@link CraftingItemList#STORAGE}), which is exactly what {@code ExtractItemTask.startOnCpu} filled
     * when the job was accepted.
     */
    private Map<ItemIdentifier, Long> countCpuTokens() {
        Map<ItemIdentifier, Long> totals = new HashMap<>();
        AENetworkProxy proxy = getProxy();
        if (proxy == null || !proxy.isReady()) {
            return totals;
        }
        ICraftingGrid crafting;
        try {
            crafting = proxy.getCrafting();
        } catch (GridAccessException e) {
            return totals;
        }
        if (crafting == null) {
            return totals;
        }

        Set<ICraftingCPU> cpus = crafting.getCpus();
        for (ICraftingCPU cpu : cpus) {
            if (!(cpu instanceof CraftingCPUCluster cluster)) {
                continue;
            }
            IItemList<IAEItemStack> stored = AEApi.instance()
                .storage()
                .createItemList();
            try {
                cluster.getListOfItem(stored, CraftingItemList.STORAGE);
            } catch (Exception e) {
                continue;
            }
            for (IAEItemStack stack : stored) {
                if (!LPVirtualItemSupport.isVirtual(stack) || stack.getStackSize() <= 0) {
                    continue;
                }
                ItemIdentifier identifier = identifierOf(stack);
                if (identifier == null) {
                    continue;
                }
                totals.merge(identifier, stack.getStackSize(), Long::sum);
            }
        }
        return totals;
    }

    /** Resolves the LP item a virtual projection stack stands for. */
    private static ItemIdentifier identifierOf(IAEStack<?> stack) {
        if (!(stack instanceof IAEItemStack items) || !(items.getItem() instanceof ItemLPVirtualStorage virt)) {
            return null;
        }
        ItemStack real = virt.getRealStack(items.getItemStack());
        return real == null ? null : ItemIdentifier.get(real);
    }

    /**
     * Patterns only depend on the <b>set</b> of LP items, not on their amounts, so they are rebuilt
     * (and AE is told) only when items appear or disappear. Amount changes just move the token counts.
     */
    private void rebuildPatternsIfNeeded(Set<ItemIdentifier> previous, Set<ItemIdentifier> next) {
        if (previous.equals(next)) {
            return;
        }
        List<LPRequestPatternDetails> built = new ArrayList<>(next.size());
        int priority = Config.patternPriority;
        for (ItemIdentifier identifier : next) {
            ItemStack real;
            try {
                real = identifier.makeNormalStack(1);
            } catch (Exception e) {
                continue;
            }
            if (real == null) {
                continue;
            }
            ItemStack virtual = BlocksLoader.LP_VIRTUAL_STORAGE.create(real);
            if (virtual == null) {
                continue;
            }
            LPRequestPatternDetails details = new LPRequestPatternDetails(virtual, real);
            details.setPriority(priority);
            built.add(details);
        }
        this.patterns = Collections.unmodifiableList(built);
        postCraftingPatternChange();
        if (Config.debugLog) {
            AELP.LOG.info("AELP: registered {} LP crafting patterns", built.size());
        }
    }

    private void postCraftingPatternChange() {
        AENetworkProxy proxy = getProxy();
        if (proxy == null || !proxy.isReady()) {
            return;
        }
        try {
            if (proxy.getGrid() == null) {
                return;
            }
            proxy.getGrid()
                .postEvent(new MENetworkCraftingPatternChange(this, proxy.getNode()));
        } catch (Exception ignored) {
            // The grid may be rebuilding between two ticks.
        }
    }

    private IAEItemStack createVirtualStack(ItemIdentifier identifier, long amount) {
        if (identifier == null || amount <= 0) {
            return null;
        }
        ItemStack real;
        try {
            real = identifier.makeNormalStack(1);
        } catch (Exception e) {
            return null;
        }
        return LPVirtualInventory.createVirtualStack(real, amount);
    }

    // ------------------------------------------------------------------
    // delivery buffer
    // ------------------------------------------------------------------

    private void drainBuffer() {
        boolean hasItems = false;
        for (ItemStack stack : this.buffer) {
            if (stack != null) {
                hasItems = true;
                break;
            }
        }
        if (!hasItems) {
            return;
        }

        IStorageGrid storage = getStorageGrid();
        if (storage == null) {
            // Keep the deliveries: they are physically inside the block, so dropping them would lose
            // items. Report once in a while so the cause is visible instead of silent.
            if (++this.drainFailures == 1 || this.drainFailures % 200 == 0) {
                AELP.LOG.warn(
                    "AELP: buffer at ({}, {}, {}) is waiting for an active ME network ({} ticks)",
                    this.xCoord,
                    this.yCoord,
                    this.zCoord,
                    this.drainFailures);
            }
            return;
        }
        this.drainFailures = 0;

        boolean dirty = false;
        for (int slot = 0; slot < BUFFER_SIZE; slot++) {
            ItemStack stack = this.buffer[slot];
            if (stack == null) {
                continue;
            }
            IAEItemStack request = AEApi.instance()
                .storage()
                .createItemStack(stack);
            if (request == null) {
                this.buffer[slot] = null;
                dirty = true;
                continue;
            }
            IAEItemStack leftover = storage.getItemInventory()
                .injectItems(request, Actionable.MODULATE, new MachineSource(this));
            long remaining = leftover == null ? 0L : leftover.getStackSize();
            if (remaining <= 0) {
                this.buffer[slot] = null;
                dirty = true;
                if (Config.debugLog) {
                    AELP.LOG.info(
                        "AELP: delivered {} x{} into the ME network at ({}, {}, {})",
                        stack.getDisplayName(),
                        stack.stackSize,
                        this.xCoord,
                        this.yCoord,
                        this.zCoord);
                }
            } else if (remaining != stack.stackSize) {
                stack.stackSize = (int) Math.min(Integer.MAX_VALUE, remaining);
                dirty = true;
            }
        }
        if (dirty) {
            markDirty();
        }
    }

    // ------------------------------------------------------------------
    // phantom token cleanup
    // ------------------------------------------------------------------

    /**
     * Voids LP tokens that ended up in real storage.
     * <p>
     * A projection token is never supposed to be stored, but a cancelled crafting job used to flush
     * the CPU inventory into the network, leaving a real {@code ItemLPVirtualStorage} behind. Such a
     * phantom both inflates the terminal count and can be pulled out by the player.
     * <p>
     * The network reports {@code projection + stored}, so anything above the projected amount is a
     * phantom. Extracting it goes to the cells (the projection itself refuses extraction), and the
     * result is simply dropped.
     */
    private void purgeStoredVirtualItems() {
        IStorageGrid storage = getStorageGrid();
        if (storage == null) {
            return;
        }
        IMEMonitor<IAEItemStack> monitor = storage.getItemInventory();
        if (monitor == null) {
            return;
        }

        IItemList<IAEItemStack> present = AEApi.instance()
            .storage()
            .createItemList();
        try {
            monitor.getAvailableItems(present, IterationCounter.fetchNewId());
        } catch (Exception e) {
            return;
        }

        // Snapshot first: extracting would otherwise mutate the list we are iterating.
        List<IAEItemStack> candidates = new ArrayList<>();
        for (IAEItemStack stack : present) {
            if (stack != null && LPVirtualItemSupport.isVirtual(stack) && stack.getStackSize() > 0) {
                candidates.add(stack.copy());
            }
        }
        if (candidates.isEmpty()) {
            return;
        }

        for (IAEItemStack stored : candidates) {
            long projected = 0L;
            for (IAEItemStack virtual : this.virtualSnapshot) {
                if (virtual != null && virtual.isSameType(stored)) {
                    projected = virtual.getStackSize();
                    break;
                }
            }
            long excess = stored.getStackSize() - projected;
            if (excess <= 0) {
                continue;
            }
            IAEItemStack request = stored.copy();
            request.setStackSize(excess);
            try {
                IAEItemStack removed = monitor.extractItems(request, Actionable.MODULATE, new MachineSource(this));
                if (removed != null && removed.getStackSize() > 0) {
                    AELP.LOG.warn(
                        "AELP: voided {} phantom LP virtual items at ({}, {}, {})",
                        removed.getStackSize(),
                        this.xCoord,
                        this.yCoord,
                        this.zCoord);
                }
            } catch (Exception e) {
                AELP.LOG.warn("AELP: failed to void a phantom LP virtual item", e);
            }
        }
    }

    // ------------------------------------------------------------------
    // LP lookup
    // ------------------------------------------------------------------

    private PipeItemsRequestLogistics findPipe() {
        if (this.worldObj == null) {
            return null;
        }
        for (ForgeDirection side : ForgeDirection.VALID_DIRECTIONS) {
            CoreUnroutedPipe pipe = LogisticsBlockGenericPipe.getPipe(
                this.worldObj,
                this.xCoord + side.offsetX,
                this.yCoord + side.offsetY,
                this.zCoord + side.offsetZ);
            if (pipe instanceof PipeItemsRequestLogistics requester && !requester.stillNeedReplace()) {
                return requester;
            }
        }
        return null;
    }

    private void releaseClaim() {
        if (this.claimedKey != null) {
            LPBridgeRegistry.release(this.claimedKey, this);
            this.claimedKey = null;
        }
    }

    private void setNetworkConflict(boolean conflict) {
        if (this.networkConflict == conflict) {
            return;
        }
        this.networkConflict = conflict;
        if (conflict) {
            AELP.LOG.warn(
                "AELP: another bridge already projects this LP network at ({}, {}, {}); this one will stay inactive",
                this.xCoord,
                this.yCoord,
                this.zCoord);
        }
        try {
            if (getProxy() != null && getProxy().isReady() && getProxy().getGrid() != null) {
                getProxy().getGrid()
                    .postEvent(new MENetworkCellArrayUpdate());
            }
        } catch (Exception ignored) {
            // The grid may be rebuilding between two ticks.
        }
        // The pattern set changed too (empty vs full), so let the crafting cache know.
        postCraftingPatternChange();
    }

    private IStorageGrid getStorageGrid() {
        AENetworkProxy proxy = getProxy();
        if (proxy == null || !proxy.isActive()) {
            return null;
        }
        try {
            return proxy.getStorage();
        } catch (GridAccessException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // lifecycle
    // ------------------------------------------------------------------

    @Override
    public void invalidate() {
        super.invalidate();
        releaseClaim();
        this.lpAvailable.clear();
        this.virtualSnapshot = Collections.emptyList();
        this.patterns = Collections.emptyList();
    }

    @Override
    public void onChunkUnload() {
        releaseClaim();
        super.onChunkUnload();
    }

    // ------------------------------------------------------------------
    // persistence
    // ------------------------------------------------------------------

    @TileEvent(TileEventType.WORLD_NBT_WRITE)
    public void writeBufferToNBT(NBTTagCompound data) {
        NBTTagList list = new NBTTagList();
        for (int slot = 0; slot < BUFFER_SIZE; slot++) {
            ItemStack stack = this.buffer[slot];
            if (stack == null) {
                continue;
            }
            NBTTagCompound entry = new NBTTagCompound();
            entry.setByte(NBT_SLOT, (byte) slot);
            NBTTagCompound stackTag = new NBTTagCompound();
            stack.writeToNBT(stackTag);
            entry.setTag(NBT_STACK, stackTag);
            list.appendTag(entry);
        }
        data.setTag(NBT_BUFFER, list);
    }

    @TileEvent(TileEventType.WORLD_NBT_READ)
    public void readBufferFromNBT(NBTTagCompound data) {
        for (int slot = 0; slot < BUFFER_SIZE; slot++) {
            this.buffer[slot] = null;
        }
        NBTTagList list = data.getTagList(NBT_BUFFER, 10);
        for (int i = 0; i < list.tagCount(); i++) {
            NBTTagCompound entry = list.getCompoundTagAt(i);
            int slot = entry.getByte(NBT_SLOT) & 0xFF;
            if (slot >= BUFFER_SIZE) {
                continue;
            }
            ItemStack stack = ItemStack.loadItemStackFromNBT(entry.getCompoundTag(NBT_STACK));
            if (stack != null) {
                this.buffer[slot] = stack;
            }
        }
        this.needsRefresh = true;
    }

    // ------------------------------------------------------------------
    // IInventory: the physical LP delivery target
    // ------------------------------------------------------------------

    @Override
    public int getSizeInventory() {
        return BUFFER_SIZE;
    }

    @Override
    public ItemStack getStackInSlot(int slot) {
        return isValidSlot(slot) ? this.buffer[slot] : null;
    }

    @Override
    public ItemStack decrStackSize(int slot, int amount) {
        if (!isValidSlot(slot) || amount <= 0) {
            return null;
        }
        ItemStack stack = this.buffer[slot];
        if (stack == null) {
            return null;
        }
        ItemStack taken;
        if (stack.stackSize <= amount) {
            taken = stack;
            this.buffer[slot] = null;
        } else {
            taken = stack.splitStack(amount);
        }
        markDirty();
        return taken;
    }

    @Override
    public ItemStack getStackInSlotOnClosing(int slot) {
        if (!isValidSlot(slot)) {
            return null;
        }
        ItemStack stack = this.buffer[slot];
        this.buffer[slot] = null;
        return stack;
    }

    @Override
    public void setInventorySlotContents(int slot, ItemStack stack) {
        if (!isValidSlot(slot)) {
            return;
        }
        this.buffer[slot] = stack;
        if (stack != null && stack.stackSize > getInventoryStackLimit()) {
            stack.stackSize = getInventoryStackLimit();
        }
        markDirty();
    }

    @Override
    public String getInventoryName() {
        return "container.aelp.lpBridge";
    }

    @Override
    public boolean hasCustomInventoryName() {
        return false;
    }

    @Override
    public int getInventoryStackLimit() {
        return 64;
    }

    @Override
    public boolean isUseableByPlayer(EntityPlayer player) {
        if (this.worldObj == null || this.worldObj.getTileEntity(this.xCoord, this.yCoord, this.zCoord) != this) {
            return false;
        }
        return player.getDistanceSq(this.xCoord + 0.5D, this.yCoord + 0.5D, this.zCoord + 0.5D) <= 64.0D;
    }

    @Override
    public void openInventory() {}

    @Override
    public void closeInventory() {}

    @Override
    public boolean isItemValidForSlot(int slot, ItemStack stack) {
        // Never allow the virtual projection token to be stored as a real item.
        return stack != null && !LPVirtualItemSupport.isVirtual(stack);
    }

    private static boolean isValidSlot(int slot) {
        return slot >= 0 && slot < BUFFER_SIZE;
    }
}

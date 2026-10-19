package com.folk.aelp.tiles;

import net.minecraft.inventory.InventoryCrafting;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.World;

import com.folk.aelp.items.ItemLPVirtualStorage;

import appeng.api.AEApi;
import appeng.api.networking.crafting.ICraftingPatternDetails;
import appeng.api.storage.data.IAEItemStack;

/**
 * AE crafting description that turns "the LP network can supply X" into "X can be crafted".
 * <p>
 * The input is the virtual stack (a read-only projection token, never extractable), the output is the
 * real item. {@link #isCraftable()} is {@code false} so AE treats it as a processing pattern: no
 * crafting grid, no per-item complex-pattern tasks, and no container item handling.
 * <p>
 * Equality is based on the real item identity so that pattern instances recreated by
 * {@link ItemLPVirtualStorage#getPatternForItem} (during task deserialization) still resolve to the
 * medium registered in {@code CraftingGridCache}.
 */
public final class LPRequestPatternDetails implements ICraftingPatternDetails {

    private final ItemStack virtualInput;
    private final ItemStack realOutput;
    private final IAEItemStack[] inputs;
    private final IAEItemStack[] outputs;
    private final String identityKey;
    private int priority;

    public LPRequestPatternDetails(ItemStack virtualInput, ItemStack realOutput) {
        if (virtualInput == null || realOutput == null) {
            throw new IllegalArgumentException("virtual input and real output are required");
        }
        this.virtualInput = virtualInput.copy();
        this.virtualInput.stackSize = 1;
        this.realOutput = realOutput.copy();
        this.realOutput.stackSize = 1;

        this.inputs = new IAEItemStack[] { createStack(this.virtualInput) };
        this.outputs = new IAEItemStack[] { createStack(this.realOutput) };

        this.identityKey = identityKeyOf(this.realOutput);
    }

    private static IAEItemStack createStack(ItemStack stack) {
        IAEItemStack ae = AEApi.instance()
            .storage()
            .createItemStack(stack);
        if (ae != null) {
            ae.setCraftable(false);
        }
        return ae;
    }

    private static String identityKeyOf(ItemStack stack) {
        ItemStack copy = stack.copy();
        copy.stackSize = 1;
        NBTTagCompound tag = new NBTTagCompound();
        copy.writeToNBT(tag);
        return tag.toString();
    }

    // ------------------------------------------------------------------
    // bridge accessors
    // ------------------------------------------------------------------

    public ItemStack getRealOutput() {
        return this.realOutput.copy();
    }

    public ItemStack getVirtualInput() {
        return this.virtualInput.copy();
    }

    /** Stable key of the real item this pattern produces. */
    public String getIdentityKey() {
        return this.identityKey;
    }

    // ------------------------------------------------------------------
    // ICraftingPatternDetails
    // ------------------------------------------------------------------

    @Override
    public ItemStack getPattern() {
        return this.virtualInput.copy();
    }

    @Override
    public boolean isValidItemForSlot(int slot, ItemStack stack, World world) {
        if (slot != 0 || stack == null) {
            return false;
        }
        return stack.getItem() == this.virtualInput.getItem()
            && stack.getItemDamage() == this.virtualInput.getItemDamage()
            && ItemStack.areItemStackTagsEqual(this.virtualInput, stack);
    }

    @Override
    public boolean isCraftable() {
        return false;
    }

    @Override
    public IAEItemStack[] getInputs() {
        return this.inputs;
    }

    @Override
    public IAEItemStack[] getCondensedInputs() {
        return this.inputs;
    }

    @Override
    public IAEItemStack[] getOutputs() {
        return this.outputs;
    }

    @Override
    public IAEItemStack[] getCondensedOutputs() {
        return this.outputs;
    }

    @Override
    public boolean canSubstitute() {
        return false;
    }

    @Override
    public ItemStack getOutput(InventoryCrafting inventory, World world) {
        return this.realOutput.copy();
    }

    @Override
    public int getPriority() {
        return this.priority;
    }

    @Override
    public void setPriority(int priority) {
        this.priority = priority;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof LPRequestPatternDetails other)) {
            return false;
        }
        return this.identityKey.equals(other.identityKey);
    }

    @Override
    public int hashCode() {
        return this.identityKey.hashCode();
    }

    @Override
    public String toString() {
        return "LPRequestPatternDetails{" + this.identityKey + '}';
    }
}

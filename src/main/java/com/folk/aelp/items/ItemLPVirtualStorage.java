package com.folk.aelp.items;

import java.util.List;

import net.minecraft.client.renderer.texture.IIconRegister;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.IIcon;
import net.minecraft.world.World;

import com.folk.aelp.tiles.LPRequestPatternDetails;

import appeng.api.implementations.ICraftingPatternItem;
import appeng.api.networking.crafting.ICraftingPatternDetails;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/**
 * Virtual, read-only projection of one LP network item.
 * <p>
 * This item never exists inside any AE storage: the bridge creates it on the fly to describe "the LP
 * network can supply this stack". It has no recipe, no creative tab entry, is rejected by every
 * injection path, and can never be extracted from the network.
 */
public class ItemLPVirtualStorage extends Item implements ICraftingPatternItem {

    /** NBT key holding the real (LP) item stack this virtual stack stands for. */
    public static final String REAL_STACK_TAG = "lpRealStack";

    @SideOnly(Side.CLIENT)
    private IIcon fallbackIcon;

    public ItemLPVirtualStorage() {
        setUnlocalizedName("aelp.lpVirtualStorage");
        setTextureName("aelp:lp_virtual_storage");
        setMaxStackSize(64);
        setNoRepair();
    }

    // ------------------------------------------------------------------
    // virtual <-> real conversion
    // ------------------------------------------------------------------

    /** Creates the virtual stack that stands for {@code real}, or {@code null} for invalid input. */
    public ItemStack create(ItemStack real) {
        if (real == null || real.getItem() == null || real.stackSize <= 0 || real.getItem() == this) {
            return null;
        }
        ItemStack identity = real.copy();
        identity.stackSize = 1;

        NBTTagCompound realTag = new NBTTagCompound();
        identity.writeToNBT(realTag);

        NBTTagCompound wrapper = new NBTTagCompound();
        wrapper.setTag(REAL_STACK_TAG, realTag);

        ItemStack virtual = new ItemStack(this, 1, 0);
        virtual.setTagCompound(wrapper);
        return virtual;
    }

    /** Resolves the real stack behind a virtual stack, or {@code null} if the NBT is missing/invalid. */
    public ItemStack getRealStack(ItemStack virtual) {
        if (virtual == null || virtual.getItem() != this || virtual.getTagCompound() == null) {
            return null;
        }
        if (!virtual.getTagCompound()
            .hasKey(REAL_STACK_TAG, 10)) {
            return null;
        }
        NBTTagCompound realTag = virtual.getTagCompound()
            .getCompoundTag(REAL_STACK_TAG);
        if (!realTag.hasKey("id")) {
            return null;
        }
        ItemStack real = ItemStack.loadItemStackFromNBT(realTag);
        if (real == null || real.getItem() == null || real.getItem() == this) {
            return null;
        }
        real.stackSize = 1;
        return real;
    }

    /** Stable identity string of the underlying real stack, used by the pattern for equals/hashCode. */
    public String getIdentityKey(ItemStack virtual) {
        ItemStack real = getRealStack(virtual);
        if (real == null) {
            return "";
        }
        NBTTagCompound tag = new NBTTagCompound();
        real.writeToNBT(tag);
        return tag.toString();
    }

    // ------------------------------------------------------------------
    // AE pattern support (pattern registration happens in the bridge tile)
    // ------------------------------------------------------------------

    @Override
    public ICraftingPatternDetails getPatternForItem(ItemStack stack, World world) {
        ItemStack real = getRealStack(stack);
        return real == null ? null : new LPRequestPatternDetails(stack, real);
    }

    // ------------------------------------------------------------------
    // display
    // ------------------------------------------------------------------

    @Override
    public String getItemStackDisplayName(ItemStack stack) {
        ItemStack real = getRealStack(stack);
        return "[LP] " + (real == null ? "无效投影" : real.getDisplayName());
    }

    @Override
    @SideOnly(Side.CLIENT)
    @SuppressWarnings({ "unchecked", "rawtypes" })
    public void addInformation(ItemStack stack, EntityPlayer player, List tooltip, boolean advanced) {
        if (getRealStack(stack) == null) {
            tooltip.add(EnumChatFormatting.RED + "无效的 LP 虚拟存储（缺少物品数据）");
            return;
        }
        tooltip.add(EnumChatFormatting.GRAY + "LP 库存的只读投影");
        tooltip.add(EnumChatFormatting.GRAY + "无法从 ME 网络取出，仅用于自动合成");
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void registerIcons(IIconRegister register) {
        // Dedicated icon: the virtual stack must never look like the real item it stands for,
        // otherwise players confuse a read-only projection with actual stock.
        this.fallbackIcon = register.registerIcon("aelp:lp_virtual_storage");
    }

    @Override
    @SideOnly(Side.CLIENT)
    public IIcon getIconFromDamage(int damage) {
        return this.fallbackIcon;
    }

    @Override
    @SideOnly(Side.CLIENT)
    public IIcon getIcon(ItemStack stack, int pass) {
        // Always the dedicated icon, even for a well-formed virtual stack.
        return this.fallbackIcon;
    }

    @Override
    @SideOnly(Side.CLIENT)
    public IIcon getIconIndex(ItemStack stack) {
        return getIcon(stack, 0);
    }
}

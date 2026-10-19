package com.folk.aelp.blocks;

import net.minecraft.block.Block;
import net.minecraft.block.BlockContainer;
import net.minecraft.block.material.Material;
import net.minecraft.client.renderer.texture.IIconRegister;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.IIcon;
import net.minecraft.world.World;

import com.folk.aelp.tiles.TileLPBridge;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/**
 * LP -> AE bridge block.
 * <p>
 * Place it next to an LP request pipe (any side works). The block projects the LP stock into the ME
 * network as a read-only virtual inventory and receives the physical LP deliveries into its internal
 * buffer, which it pushes into the ME network every tick.
 * <p>
 * The look is the ME Interface with a yellow frame, so players read it as "an interface, LP flavored"
 * at a glance. AE already ships that exact texture as a dyed interface variant, so it is reused
 * directly instead of duplicating assets.
 * <p>
 * All six faces use the same texture on purpose: the bridge has no facing direction — LP items come in
 * through the request pipe and leave through the ME network, independent of which side they touch.
 */
public class BlockLPBridge extends BlockContainer {

    /** Yellow-framed ME Interface face, used on every side. */
    private static final String TEXTURE = "appliedenergistics2:interface/BlockInterface_Yellow";

    @SideOnly(Side.CLIENT)
    private IIcon icon;

    public BlockLPBridge() {
        super(Material.iron);
        setBlockName("aelp.lpBridge");
        setHardness(3.0F);
        setResistance(10.0F);
        setStepSound(soundTypeMetal);
        setHarvestLevel("pickaxe", 1);
        setCreativeTab(CreativeTabs.tabBlock);
    }

    @Override
    public TileEntity createNewTileEntity(World world, int metadata) {
        return new TileLPBridge();
    }

    // ------------------------------------------------------------------
    // rendering: reuse AE's dyed interface texture on all faces
    // ------------------------------------------------------------------

    @Override
    @SideOnly(Side.CLIENT)
    public void registerBlockIcons(IIconRegister register) {
        this.icon = register.registerIcon(TEXTURE);
        this.blockIcon = this.icon;
    }

    @Override
    @SideOnly(Side.CLIENT)
    public IIcon getIcon(int side, int meta) {
        return this.icon;
    }

    // ------------------------------------------------------------------
    // inventory drops
    // ------------------------------------------------------------------

    @Override
    public void breakBlock(World world, int x, int y, int z, Block block, int meta) {
        TileEntity tile = world.getTileEntity(x, y, z);
        if (tile instanceof TileLPBridge bridge) {
            for (int slot = 0; slot < bridge.getSizeInventory(); slot++) {
                ItemStack stack = bridge.getStackInSlot(slot);
                if (stack != null) {
                    dropBlockAsItem(world, x, y, z, stack);
                    bridge.setInventorySlotContents(slot, null);
                }
            }
        }
        super.breakBlock(world, x, y, z, block, meta);
    }
}

package com.folk.aelp.blocks;

import net.minecraft.block.Block;
import net.minecraft.block.BlockContainer;
import net.minecraft.block.material.Material;
import net.minecraft.client.renderer.texture.IIconRegister;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.IIcon;
import net.minecraft.world.IBlockAccess;
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
 */
public class BlockLPBridge extends BlockContainer {

    /** Yellow-framed ME Interface face, used for the four sides. */
    private static final String TEXTURE_SIDE = "appliedenergistics2:interface/BlockInterface_Yellow";
    /** Yellow-framed ME Interface face including the direction arrow, used for the front. */
    private static final String TEXTURE_FRONT = "appliedenergistics2:interface/BlockInterfaceAlternateArrow_Yellow";

    @SideOnly(Side.CLIENT)
    private IIcon sideIcon;
    @SideOnly(Side.CLIENT)
    private IIcon frontIcon;

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
    // rendering: reuse AE's dyed interface textures
    // ------------------------------------------------------------------

    @Override
    @SideOnly(Side.CLIENT)
    public void registerBlockIcons(IIconRegister register) {
        this.sideIcon = register.registerIcon(TEXTURE_SIDE);
        this.frontIcon = register.registerIcon(TEXTURE_FRONT);
        this.blockIcon = this.sideIcon;
    }

    @Override
    @SideOnly(Side.CLIENT)
    public IIcon getIcon(int side, int meta) {
        // Item rendering / unplaced blocks: treat "north" as the front.
        return side == 3 ? this.frontIcon : this.sideIcon;
    }

    @Override
    @SideOnly(Side.CLIENT)
    public IIcon getIcon(IBlockAccess world, int x, int y, int z, int side) {
        int facing = world.getBlockMetadata(x, y, z) & 7;
        return side == facing ? this.frontIcon : this.sideIcon;
    }

    @Override
    public int onBlockPlaced(World world, int x, int y, int z, int side, float hitX, float hitY, float hitZ,
        int metadata) {
        // The face you click becomes the front, so the arrow always points at the player.
        return side;
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

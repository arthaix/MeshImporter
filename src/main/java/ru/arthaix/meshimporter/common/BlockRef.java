package ru.arthaix.meshimporter.common;

import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;

/** "modid:name" or "modid:name:meta" ↔ block state. */
public final class BlockRef {

    private BlockRef() {}

    public static IBlockState parse(String id) {
        if (id == null || id.isEmpty()) return Blocks.STONE.getDefaultState();
        String name = id;
        int meta = 0;
        int first = id.indexOf(':');
        int last = id.lastIndexOf(':');
        if (last > first && first >= 0) {
            try {
                meta = Integer.parseInt(id.substring(last + 1));
                name = id.substring(0, last);
            } catch (NumberFormatException ignored) {}
        }
        Block block = Block.REGISTRY.getObject(new ResourceLocation(name));
        if (block == null || block == Blocks.AIR) return Blocks.STONE.getDefaultState();
        try {
            return block.getStateFromMeta(meta);
        } catch (RuntimeException e) {
            return block.getDefaultState();
        }
    }

    public static String toId(Block block, int meta) {
        ResourceLocation name = block.getRegistryName();
        String s = name == null ? "minecraft:stone" : name.toString();
        return meta != 0 ? s + ":" + meta : s;
    }

    public static String toId(ItemStack stack) {
        Block block = Block.getBlockFromItem(stack.getItem());
        if (block == null || block == Blocks.AIR) return "minecraft:stone";
        return toId(block, stack.getMetadata());
    }

    public static ItemStack toStack(String id) {
        IBlockState state = parse(id);
        return new ItemStack(state.getBlock(), 1, state.getBlock().getMetaFromState(state));
    }
}

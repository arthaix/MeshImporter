package ru.arthaix.meshimporter.compat;

import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * The places where a mesh has carried a rail. Immersive Railroading counts how much of a track floats and breaks it
 * after a while, so a model taken away for editing would take its rails with it. These places keep answering "there is
 * ground here" while the model is gone: the track simply hangs in the air and waits for it to come back. A place is
 * forgotten once its rail is gone, so the list does not grow forever.
 */
public final class RailSupport {

    public static final RailSupport SERVER = new RailSupport();
    public static final RailSupport CLIENT = new RailSupport();

    private static final String IR = "immersiverailroading";

    private final Map<Integer, Set<Long>> byDim = new ConcurrentHashMap<>();
    /** Run when something changed, so the save file follows; the server sets it, the client leaves it null. */
    public volatile Runnable onChange;

    private RailSupport() {}

    public static RailSupport of(World world) {
        return world.isRemote ? CLIENT : SERVER;
    }

    public boolean has(int dim, BlockPos pos) {
        Set<Long> set = byDim.get(dim);
        return set != null && set.contains(pos.toLong());
    }

    public void remember(int dim, BlockPos pos) {
        if (byDim.computeIfAbsent(dim, d -> ConcurrentHashMap.newKeySet()).add(pos.toLong())) changed();
    }

    public int count() {
        int n = 0;
        for (Set<Long> set : byDim.values()) n += set.size();
        return n;
    }

    public void clear() {
        byDim.clear();
    }

    /** Forgets the places whose rail is gone; looks only at chunks that are loaded anyway. */
    public int prune(World world) {
        Set<Long> set = byDim.get(world.provider.getDimension());
        if (set == null || set.isEmpty()) return 0;
        int dropped = 0;
        for (Long key : new ArrayList<>(set)) {
            BlockPos pos = BlockPos.fromLong(key);
            if (!world.isBlockLoaded(pos)) continue;
            ResourceLocation name = world.getBlockState(pos).getBlock().getRegistryName();
            if (name != null && IR.equals(name.getNamespace())) continue;
            set.remove(key);
            dropped++;
        }
        if (dropped > 0) changed();
        return dropped;
    }

    public NBTTagCompound write() {
        NBTTagCompound nbt = new NBTTagCompound();
        NBTTagList list = new NBTTagList();
        for (Map.Entry<Integer, Set<Long>> e : byDim.entrySet()) {
            if (e.getValue().isEmpty()) continue;
            NBTTagCompound tag = new NBTTagCompound();
            tag.setInteger("dim", e.getKey());
            long[] places = new long[e.getValue().size()];
            int i = 0;
            for (Long key : e.getValue()) {
                if (i == places.length) break;
                places[i++] = key;
            }
            tag.setTag("places", newLongArray(places, i));
            list.appendTag(tag);
        }
        nbt.setTag("dims", list);
        return nbt;
    }

    public void read(NBTTagCompound nbt) {
        byDim.clear();
        NBTTagList list = nbt.getTagList("dims", 10);
        for (int i = 0; i < list.tagCount(); i++) {
            NBTTagCompound tag = list.getCompoundTagAt(i);
            Set<Long> set = ConcurrentHashMap.newKeySet();
            for (long key : readLongArray(tag)) set.add(key);
            if (!set.isEmpty()) byDim.put(tag.getInteger("dim"), set);
        }
    }

    private void changed() {
        Runnable r = onChange;
        if (r != null) r.run();
    }

    /** Minecraft 1.12 has no long array tag: two ints per place. */
    private static net.minecraft.nbt.NBTTagIntArray newLongArray(long[] places, int size) {
        int[] ints = new int[size * 2];
        for (int i = 0; i < size; i++) {
            ints[i * 2] = (int) (places[i] >> 32);
            ints[i * 2 + 1] = (int) places[i];
        }
        return new net.minecraft.nbt.NBTTagIntArray(ints);
    }

    private static long[] readLongArray(NBTTagCompound tag) {
        int[] ints = tag.getIntArray("places");
        long[] places = new long[ints.length / 2];
        for (int i = 0; i < places.length; i++) places[i] = ((long) ints[i * 2] << 32) | (ints[i * 2 + 1] & 0xFFFFFFFFL);
        return places;
    }
}

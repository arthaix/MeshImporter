package ru.arthaix.meshimporter.server;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.world.WorldServer;
import net.minecraft.world.storage.MapStorage;
import net.minecraft.world.storage.WorldSavedData;
import ru.arthaix.meshimporter.compat.RailSupport;
import ru.arthaix.meshimporter.instance.MeshInstance;

/** All placed models of a save (every dimension), in data/meshimporter_instances.dat. Model files live next to it. */
public final class MeshRegistry extends WorldSavedData {

    public static final String NAME = "meshimporter_instances";

    private final List<MeshInstance> instances = new ArrayList<>();
    private int nextId = 1;

    public MeshRegistry() {
        super(NAME);
    }

    public MeshRegistry(String name) {
        super(name);
    }

    public static MeshRegistry get(WorldServer world) {
        MapStorage storage = world.getMapStorage();
        MeshRegistry r = (MeshRegistry) storage.getOrLoadData(MeshRegistry.class, NAME);
        if (r == null) {
            r = new MeshRegistry();
            storage.setData(NAME, r);
        }
        return r;
    }

    public int nextId() {
        int id = nextId++;
        markDirty();
        return id;
    }

    public void add(MeshInstance in) {
        instances.add(in);
        markDirty();
    }

    public boolean remove(MeshInstance in) {
        boolean r = instances.remove(in);
        if (r) markDirty();
        return r;
    }

    public List<MeshInstance> all() {
        return instances;
    }

    public MeshInstance find(int id) {
        for (MeshInstance in : instances) if (in.id == id) return in;
        return null;
    }

    /** Every model placed from one anchor, in the order they were added. */
    public List<MeshInstance> allByAnchor(int dim, int x, int y, int z) {
        List<MeshInstance> list = new ArrayList<>();
        for (MeshInstance in : instances)
            if (in.dim == dim && in.anchorX == x && in.anchorY == y && in.anchorZ == z) list.add(in);
        return list;
    }

    public MeshInstance byAnchor(int dim, int x, int y, int z) {
        for (MeshInstance in : instances)
            if (in.dim == dim && in.anchorX == x && in.anchorY == y && in.anchorZ == z) return in;
        return null;
    }

    public int referencesTo(String hash) {
        int n = 0;
        for (MeshInstance in : instances) if (in.hash.equals(hash)) n++;
        return n;
    }

    @Override
    public void readFromNBT(NBTTagCompound nbt) {
        instances.clear();
        nextId = Math.max(1, nbt.getInteger("nextId"));
        NBTTagList list = nbt.getTagList("instances", 10);
        for (int i = 0; i < list.tagCount(); i++) instances.add(MeshInstance.read(list.getCompoundTagAt(i)));
        RailSupport.SERVER.read(nbt.getCompoundTag("railSupport"));
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound nbt) {
        nbt.setInteger("nextId", nextId);
        NBTTagList list = new NBTTagList();
        for (MeshInstance in : instances) list.appendTag(in.write());
        nbt.setTag("instances", list);
        nbt.setTag("railSupport", RailSupport.SERVER.write());
        return nbt;
    }
}

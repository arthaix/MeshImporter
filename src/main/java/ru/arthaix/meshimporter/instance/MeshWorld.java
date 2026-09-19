package ru.arthaix.meshimporter.instance;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.world.World;
import ru.arthaix.meshimporter.collision.TriangleGrid;

/** The models currently loaded on one side (server or client), by dimension and id. */
public final class MeshWorld {

    public static final MeshWorld SERVER = new MeshWorld();
    public static final MeshWorld CLIENT = new MeshWorld();

    private final Map<Integer, Map<Integer, LoadedInstance>> byDim = new ConcurrentHashMap<>();

    private MeshWorld() {}

    public static MeshWorld of(World world) {
        return world.isRemote ? CLIENT : SERVER;
    }

    public void add(LoadedInstance li) {
        byDim.computeIfAbsent(li.instance.dim, d -> new ConcurrentHashMap<>()).put(li.instance.id, li);
    }

    public LoadedInstance remove(int dim, int id) {
        Map<Integer, LoadedInstance> m = byDim.get(dim);
        return m == null ? null : m.remove(id);
    }

    public LoadedInstance get(int dim, int id) {
        Map<Integer, LoadedInstance> m = byDim.get(dim);
        return m == null ? null : m.get(id);
    }

    public Collection<LoadedInstance> inDimension(int dim) {
        Map<Integer, LoadedInstance> m = byDim.get(dim);
        return m == null ? Collections.emptyList() : m.values();
    }

    public List<LoadedInstance> all() {
        List<LoadedInstance> list = new ArrayList<>();
        for (Map<Integer, LoadedInstance> m : byDim.values()) list.addAll(m.values());
        return list;
    }

    public boolean isEmpty(int dim) {
        Map<Integer, LoadedInstance> m = byDim.get(dim);
        return m == null || m.isEmpty();
    }

    public void clear() {
        byDim.clear();
    }

    public LoadedInstance byAnchor(int dim, int x, int y, int z) {
        for (LoadedInstance li : inDimension(dim))
            if (!li.preview && li.instance.anchorX == x && li.instance.anchorY == y && li.instance.anchorZ == z) return li;
        return null;
    }

    /** Closest mesh hit along the world-space segment, or null. */
    public TriangleGrid.Hit raycast(int dim, double fx, double fy, double fz, double tx, double ty, double tz) {
        TriangleGrid.Hit best = null;
        AxisAlignedBB seg = new AxisAlignedBB(Math.min(fx, tx), Math.min(fy, ty), Math.min(fz, tz), Math.max(fx, tx), Math.max(fy, ty), Math.max(fz, tz));
        for (LoadedInstance li : inDimension(dim)) {
            if (!li.instance.intersects(seg)) continue;
            TriangleGrid.Hit h = li.grid.raycast(fx, fy, fz, tx, ty, tz);
            if (h != null && (best == null || h.t < best.t)) best = h;
        }
        return best;
    }
}

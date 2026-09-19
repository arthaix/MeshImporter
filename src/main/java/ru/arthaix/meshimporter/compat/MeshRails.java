package ru.arthaix.meshimporter.compat;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import ru.arthaix.meshimporter.MeshImporter;
import ru.arthaix.meshimporter.instance.LoadedInstance;
import ru.arthaix.meshimporter.instance.MeshWorld;

/**
 * Immersive Railroading only lays track where the block under it is solid ground. A mesh is not made of blocks, so
 * track on a mesh bridge or deck would count as floating; this answers for the mesh surface instead. The mod is read
 * through reflection, so MeshImporter neither depends on it nor breaks when it is missing or changes.
 */
public final class MeshRails {

    private static boolean looked;
    private static Method trackPos, vecPos;
    private static Field trackBuilder, builderWorld, worldInternal;

    private MeshRails() {}

    /** True when a mesh surface carries the rail at this track position (Immersive Railroading's TrackBase). */
    public static boolean onMesh(Object track) {
        if (!lookup(track)) return false;
        try {
            BlockPos pos = (BlockPos) vecPos.invoke(trackPos.invoke(track));
            World world = (World) worldInternal.get(builderWorld.get(trackBuilder.get(track)));
            if (pos == null || world == null) return false;
            int dim = world.provider.getDimension();
            if (carried(world, pos)) {
                RailSupport.of(world).remember(dim, pos);
                return true;
            }
            // the model may be away for editing: the rails keep their ground and wait in the air for it
            return RailSupport.of(world).has(dim, pos);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }

    /** Mesh geometry right under the rail (or just below its own block), the way solid ground would be. */
    private static boolean carried(World world, BlockPos pos) {
        MeshWorld meshes = MeshWorld.of(world);
        int dim = world.provider.getDimension();
        if (meshes.isEmpty(dim)) return false;
        AxisAlignedBB box = new AxisAlignedBB(pos.getX() + 0.05, pos.getY() - 0.4, pos.getZ() + 0.05, pos.getX() + 0.95, pos.getY() + 0.3, pos.getZ() + 0.95);
        List<AxisAlignedBB> boxes = new ArrayList<>();
        for (LoadedInstance li : meshes.inDimension(dim)) {
            if (li.preview || !li.instance.intersects(box)) continue;
            li.grid.collectBoxes(box, boxes);
            if (!boxes.isEmpty()) return true;
        }
        return false;
    }

    private static synchronized boolean lookup(Object track) {
        if (looked) return trackPos != null;
        looked = true;
        try {
            trackPos = method(track.getClass(), "getPos");
            trackBuilder = field(track.getClass(), "builder");
            Object builder = trackBuilder.get(track);
            builderWorld = field(builder.getClass(), "world");
            worldInternal = field(builderWorld.get(builder).getClass(), "internal");
            vecPos = method(trackPos.invoke(track).getClass(), "internal");
            MeshImporter.logger.info("Immersive Railroading: mesh surfaces count as ground for track");
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            MeshImporter.logger.warn("Immersive Railroading track support off (its internals changed): " + e);
            trackPos = null;
            return false;
        }
    }

    private static Method method(Class<?> c, String name) throws NoSuchMethodException {
        for (Class<?> k = c; k != null; k = k.getSuperclass())
            try {
                Method m = k.getDeclaredMethod(name);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException ignored) {
                // keep looking up the hierarchy
            }
        throw new NoSuchMethodException(name + " in " + c);
    }

    private static Field field(Class<?> c, String name) throws NoSuchFieldException {
        for (Class<?> k = c; k != null; k = k.getSuperclass())
            try {
                Field f = k.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {
                // keep looking up the hierarchy
            }
        throw new NoSuchFieldException(name + " in " + c);
    }
}

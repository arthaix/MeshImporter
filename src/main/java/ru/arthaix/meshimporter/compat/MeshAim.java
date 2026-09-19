package ru.arthaix.meshimporter.compat;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/**
 * The mesh surface the local player aims at while holding a LittleTiles tool: the block and hit vector of the aim
 * result MeshImporter put into Minecraft. Read by the LittleTiles placement mixin (client only).
 */
public final class MeshAim {

    private static volatile BlockPos pos;
    private static volatile Vec3d hitVec;

    private MeshAim() {}

    public static void set(BlockPos blockPos, Vec3d hit) {
        hitVec = null;
        pos = blockPos;
        hitVec = hit;
    }

    public static void clear() {
        hitVec = null;
        pos = null;
    }

    /** True for exactly the aim result MeshImporter created (same hit vector object). */
    public static boolean matches(BlockPos blockPos, Vec3d hit) {
        Vec3d v = hitVec;
        return v != null && hit == v && blockPos != null && blockPos.equals(pos);
    }
}

package ru.arthaix.meshimporter.compat;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.function.Consumer;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import ru.arthaix.meshimporter.MeshImporter;
import ru.arthaix.meshimporter.server.RailPaths;

/**
 * Lays Immersive Railroading track along a line drawn in Blender. Every piece is built as IR's own cubic curve
 * between two points, with the heading of the line at each end, so the track follows the drawing instead of being
 * approximated by straight steps. Gauge, rail bed and track style come from the blueprint in hand; the type and the
 * length come from the line. Everything goes through reflection, so MeshImporter neither depends on Immersive
 * Railroading nor breaks when it is missing.
 */
public final class MeshRailLayer {

    private static boolean looked;
    private static Constructor<?> umcStack, umcPlayer, umcVec3d, umcVec3i, placementInfo, railInfo;
    private static Method settingsFrom, withSettings, build;
    private static Object trackCustom;
    private static Field mType, mLength, mCurvosity, mPreview;

    private MeshRailLayer() {}

    /** The track laid, as {laid, refused}; the sender gets the reason for the first refusal in the log. */
    public static int[] lay(EntityPlayerMP player, ItemStack blueprint, List<double[]> points, double curvosity,
        int from, int count) {
        if (!lookup()) return new int[] { 0, 0 };
        int laid = 0, refused = 0;
        try {
            Object stack = umcStack.newInstance(blueprint);
            Object who = umcPlayer.newInstance(player);
            for (int i = from; i + 1 < points.size() && i < from + count; i++) {
                double[] a = points.get(i), b = points.get(i + 1);
                double[] before = i > 0 ? points.get(i - 1) : a;
                double[] after = i + 2 < points.size() ? points.get(i + 2) : b;
                // the heading of the line at each end, so neighbouring pieces meet without a kink
                float yawA = RailPaths.yaw(before, b);
                float yawB = RailPaths.yaw(a, after);
                int length = Math.max(1, (int) Math.ceil(RailPaths.distance(a, b)));

                Object start = placementInfo.newInstance(stack, yawA, vec3d(a));
                Object end = placementInfo.newInstance(stack, yawB, vec3d(b));
                Object info = railInfo.newInstance(stack, start, end);
                info = withSettings.invoke(info, (Consumer<Object>) mutable -> {
                    try {
                        mType.set(mutable, trackCustom);
                        mLength.setInt(mutable, length);
                        mCurvosity.setFloat(mutable, (float) curvosity);
                        mPreview.setBoolean(mutable, false);
                    } catch (IllegalAccessException e) {
                        throw new IllegalStateException(e);
                    }
                });
                Object pos = umcVec3i.newInstance(Math.floor(a[0]), Math.floor(a[1]), Math.floor(a[2]));
                if (Boolean.TRUE.equals(build.invoke(info, who, pos))) laid++;
                else refused++;
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            MeshImporter.logger.warn("Laying track stopped after " + laid + " pieces: " + e);
        }
        return new int[] { laid, refused };
    }

    /** Takes out the Immersive Railroading blocks near a line, so new track has room. */
    public static int clear(World world, List<double[]> points, double radius) {
        int removed = 0;
        int r = (int) Math.ceil(radius);
        java.util.Set<Long> done = new java.util.HashSet<>();
        for (double[] p : points) {
            BlockPos centre = new BlockPos(p[0], p[1], p[2]);
            for (int dx = -r; dx <= r; dx++)
                for (int dy = -r; dy <= r; dy++)
                    for (int dz = -r; dz <= r; dz++) {
                        BlockPos at = centre.add(dx, dy, dz);
                        if (!done.add(at.toLong()) || !world.isBlockLoaded(at)) continue;
                        ResourceLocation name = world.getBlockState(at).getBlock().getRegistryName();
                        if (name == null || !"immersiverailroading".equals(name.getNamespace())) continue;
                        world.setBlockToAir(at);
                        removed++;
                    }
        }
        return removed;
    }

    private static Object vec3d(double[] p) throws ReflectiveOperationException {
        return umcVec3d.newInstance(p[0], p[1], p[2]);
    }

    @SuppressWarnings("unchecked")
    private static synchronized boolean lookup() {
        if (looked) return railInfo != null;
        looked = true;
        try {
            ClassLoader cl = MeshRailLayer.class.getClassLoader();
            Class<?> cStack = Class.forName("cam72cam.mod.item.ItemStack", false, cl);
            Class<?> cPlayer = Class.forName("cam72cam.mod.entity.Player", false, cl);
            Class<?> cVec3d = Class.forName("cam72cam.mod.math.Vec3d", false, cl);
            Class<?> cVec3i = Class.forName("cam72cam.mod.math.Vec3i", false, cl);
            Class<?> cSettings = Class.forName("cam72cam.immersiverailroading.items.nbt.RailSettings", false, cl);
            Class<?> cMutable = Class.forName("cam72cam.immersiverailroading.items.nbt.RailSettings$Mutable", false, cl);
            Class<?> cPlacement = Class.forName("cam72cam.immersiverailroading.util.PlacementInfo", false, cl);
            Class<?> cInfo = Class.forName("cam72cam.immersiverailroading.util.RailInfo", false, cl);
            Class<?> cItems = Class.forName("cam72cam.immersiverailroading.library.TrackItems", false, cl);

            umcStack = cStack.getConstructor(net.minecraft.item.ItemStack.class);
            umcPlayer = cPlayer.getConstructor(net.minecraft.entity.player.EntityPlayer.class);
            umcVec3d = cVec3d.getConstructor(double.class, double.class, double.class);
            umcVec3i = cVec3i.getConstructor(double.class, double.class, double.class);
            placementInfo = cPlacement.getConstructor(cStack, float.class, cVec3d);
            railInfo = cInfo.getConstructor(cStack, cPlacement, cPlacement);
            settingsFrom = cSettings.getMethod("from", cStack);
            withSettings = cInfo.getMethod("withSettings", Consumer.class);
            build = cInfo.getMethod("build", cPlayer, cVec3i);
            trackCustom = Enum.valueOf((Class<Enum>) cItems.asSubclass(Enum.class), "CUSTOM");
            mType = cMutable.getField("type");
            mLength = cMutable.getField("length");
            mCurvosity = cMutable.getField("curvosity");
            mPreview = cMutable.getField("isPreview");
            MeshImporter.logger.info("Immersive Railroading: track can be laid along a drawn line");
            return true;
        } catch (ReflectiveOperationException | LinkageError e) {
            MeshImporter.logger.warn("Laying track is off (Immersive Railroading is missing or changed): " + e);
            railInfo = null;
            return false;
        }
    }

    /** True when the item is Immersive Railroading's track blueprint. */
    public static boolean isBlueprint(ItemStack stack) {
        if (stack.isEmpty()) return false;
        ResourceLocation name = stack.getItem().getRegistryName();
        return name != null && "immersiverailroading".equals(name.getNamespace()) && name.getPath().contains("rail");
    }

    /** The settings of the blueprint as text, so the player sees what the track will be built from. */
    public static String describe(ItemStack blueprint) {
        if (!lookup()) return "Immersive Railroading is not here";
        try {
            Object settings = settingsFrom.invoke(null, umcStack.newInstance(blueprint));
            Class<?> c = settings.getClass();
            return "gauge " + c.getField("gauge").get(settings) + ", style " + c.getField("track").get(settings)
                + ", bed " + c.getField("railBed").get(settings);
        } catch (ReflectiveOperationException e) {
            return "unreadable blueprint";
        }
    }
}

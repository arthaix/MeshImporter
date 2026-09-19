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
    private static Method settingsFrom, withSettings, build, getBuilder, canBuild, umcWorldGet;
    private static Object trackCustom, directionNone;
    private static Field mType, mLength, mCurvosity, mPreview, overrideFlexible;
    /** The first few pieces that were refused, for the log: without them a refusal is silent. */
    private static int complained;

    private MeshRailLayer() {}

    /**
     * The track laid, as {laid, refused}; the sender gets the reason for the first refusal in the log.
     *
     * <p>With {@code over}, a piece may be laid through track that is already there. Immersive Railroading allows
     * this of its own accord: a piece is one anchor block and a crowd of gag blocks around it, and a gag always
     * gives way to a builder that asks. Only the anchor is held. That is the one way two routes can share ground
     * the way they do at a turnout, and without it a crossover cannot be laid at all - the tracks it joins are
     * five blocks apart and a track reserves four, so every part of the connection falls inside one or the other.
     */
    public static int[] lay(EntityPlayerMP player, ItemStack blueprint, List<double[][]> pieces, double curvosity,
        boolean over, int from, int count) {
        if (!lookup()) return new int[] { 0, 0 };
        int laid = 0, refused = 0;
        try {
            Object stack = umcStack.newInstance(blueprint);
            Object who = umcPlayer.newInstance(player);
            for (int i = from; i < pieces.size() && i < from + count; i++) {
                double[][] piece = pieces.get(i);
                double[] a = piece[0], b = piece[1];
                // the heading of the line at each end, so neighbouring pieces meet without a kink
                float yawA = (float) piece[2][0];
                float yawB = (float) piece[2][1];
                double chord = RailPaths.distance(a, b);
                int length = Math.max(1, (int) Math.ceil(chord));

                // Everything a track piece is made of is written relative to the block it is built from, and the
                // placement constructor that takes an item snaps both the heading and the position to the grid of the
                // blueprint's position mode. The four-part one keeps exactly what it is given, so the piece can follow
                // the drawn line: the two ends, their headings, and a control point a third of the way along each
                // heading - the plain smooth fit between two points and two directions.
                double bx = Math.floor(a[0]), by = Math.floor(a[1]), bz = Math.floor(a[2]);
                double[] ra = { a[0] - bx, a[1] - by, a[2] - bz };
                double[] rb = { b[0] - bx, b[1] - by, b[2] - bz };
                double arm = chord / 3, rise = (rb[1] - ra[1]) / 3;
                double[] ca = RailPaths.control(ra, yawA, arm, rise);
                double[] cb = RailPaths.control(rb, yawB + 180, arm, -rise);
                Object start = placementInfo.newInstance(vec3d(ra), directionNone, yawA, vec3d(ca));
                Object end = placementInfo.newInstance(vec3d(rb), directionNone, yawB, vec3d(cb));
                Object info = railInfo.newInstance(stack, start, end);
                info = withSettings.invoke(info, (Consumer<Object>) mutable -> {
                    try {
                        mType.set(mutable, trackCustom);
                        mLength.setInt(mutable, length);
                        if (curvosity > 0) mCurvosity.setFloat(mutable, (float) curvosity);
                        mPreview.setBoolean(mutable, false);
                    } catch (IllegalAccessException e) {
                        throw new IllegalStateException(e);
                    }
                });
                Object pos = umcVec3i.newInstance(bx, by, bz);
                // the ground has to be there to be asked about: a piece 300 blocks away is in no loaded chunk
                for (double t = 0; t <= 1.0001; t += 8.0 / Math.max(8, chord))
                    player.getServerWorld().getChunk(new BlockPos(a[0] + (b[0] - a[0]) * t, a[1], a[2] + (b[2] - a[2]) * t));
                Object world = umcWorldGet.invoke(null, player.getServerWorld());
                // the builder is kept by position, so the one asked here is the one that does the building
                Object builder = getBuilder.invoke(info, world, pos);
                if (over) overrideFlexible.setBoolean(builder, true);
                if (!Boolean.TRUE.equals(canBuild.invoke(builder))) {
                    refused++;
                    if (complained++ < 5)
                        MeshImporter.logger.info("[meshimporter] no room for track at " + String.format("%.1f %.1f %.1f", a[0], a[1], a[2])
                            + " (block " + new BlockPos(a[0], a[1], a[2]) + ", the mesh there "
                            + (MeshRails.onMeshAt(player.getServerWorld(), new BlockPos(a[0], a[1], a[2])) ? "does" : "does NOT") + " count as ground)");
                    continue;
                }
                // the two-argument build only says the list came back non-null, which it does even after a refusal
                build.invoke(info, who, pos, true);
                laid++;
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
            world.getChunk(new BlockPos(p[0], p[1], p[2]));
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

            Class<?> cWorld = Class.forName("cam72cam.mod.world.World", false, cl);
            Class<?> cBuilder = Class.forName("cam72cam.immersiverailroading.track.BuilderBase", false, cl);
            umcWorldGet = cWorld.getMethod("get", net.minecraft.world.World.class);
            getBuilder = cInfo.getMethod("getBuilder", cWorld, cVec3i);
            canBuild = cBuilder.getMethod("canBuild");
            overrideFlexible = cBuilder.getField("overrideFlexible");
            umcStack = cStack.getConstructor(net.minecraft.item.ItemStack.class);
            umcPlayer = cPlayer.getConstructor(net.minecraft.entity.player.EntityPlayer.class);
            umcVec3d = cVec3d.getConstructor(double.class, double.class, double.class);
            umcVec3i = cVec3i.getConstructor(double.class, double.class, double.class);
            Class<?> cDirection = Class.forName("cam72cam.immersiverailroading.library.TrackDirection", false, cl);
            placementInfo = cPlacement.getConstructor(cVec3d, cDirection, float.class, cVec3d);
            directionNone = Enum.valueOf((Class<Enum>) cDirection.asSubclass(Enum.class), "NONE");
            railInfo = cInfo.getConstructor(cStack, cPlacement, cPlacement);
            settingsFrom = cSettings.getMethod("from", cStack);
            withSettings = cInfo.getMethod("withSettings", Consumer.class);
            build = cInfo.getMethod("build", cPlayer, cVec3i, boolean.class);
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

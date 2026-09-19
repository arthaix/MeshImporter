package ru.arthaix.meshimporter;

import java.io.File;

import net.minecraftforge.common.config.Configuration;

/** config/meshimporter.cfg */
public final class MeshImporterConfig {

    // server
    public static int maxModelMegabytes = 256;
    public static boolean collision = true;
    public static boolean placeOnMesh = true;
    public static int downloadPartsPerTick = 4;
    public static String webExportDir = "";
    public static int webTriangles = 500_000;
    public static double webCell = 1.2;

    // client
    public static int renderDistance = 512;
    public static int lightMillisPerFrame = 2;
    public static boolean selfShadow = true;
    public static int shadowLevel = 4;
    public static boolean shaderLighting = true;
    public static int cellSize = 64;
    public static boolean mapOverlay = true;
    public static int mapPixels = 4;

    private MeshImporterConfig() {}

    public static void load(File file) {
        Configuration cfg = new Configuration(file);
        cfg.load();
        maxModelMegabytes = cfg.getInt("maxModelMegabytes", "server", maxModelMegabytes, 1, 4096, "Largest model file a player may upload");
        collision = cfg.getBoolean("collision", "server", collision, "Players and mobs collide with imported meshes (client and server)");
        placeOnMesh = cfg.getBoolean("placeOnMesh", "server", placeOnMesh, "Right-clicking a mesh uses the item in hand on its surface: blocks, Immersive Railroading blueprints and the rest");
        downloadPartsPerTick = cfg.getInt("downloadPartsPerTick", "server", downloadPartsPerTick, 1, 64, "Model download speed: parts of 256 KB sent to a client per tick");
        webExportDir = cfg.getString("webExportDir", "server", webExportDir, "Folder of a web map (for example BlueMap's web/meshimporter) that receives simplified 3D copies of placed models; empty = no export");
        webTriangles = cfg.getInt("webTriangles", "server", webTriangles, 1000, 5_000_000, "Most triangles one model may have in the web map export (the ceiling, not the target)");
        webCell = cfg.get("server", "webCell", webCell, "Grid the web map copy is built on, in blocks: anything thinner is lost, so a rail line on an embankment needs about 1. The grid is made coarser only when a model would pass the triangle ceiling").getDouble();

        renderDistance = cfg.getInt("renderDistance", "client", renderDistance, 64, 8192, "Mesh parts farther than this many blocks from the camera are not drawn");
        lightMillisPerFrame = cfg.getInt("lightMillisPerFrame", "client", lightMillisPerFrame, 0, 50, "Time per frame spent refreshing world light on meshes (0 = only when a mesh is loaded)");
        selfShadow = cfg.getBoolean("selfShadow", "client", selfShadow, "A mesh shades itself: sky light is baked by ray casting when the mesh loads, so rooms, arcades and bridge undersides get soft light through openings and glass");
        shadowLevel = cfg.getInt("shadowLevel", "client", shadowLevel, 0, 15, "Lowest sky light a mesh casts on itself (0 = as dark as a cave, 15 = no shadow)");
        cfg.getCategory("client").remove("lightDetail"); // replaced by the baked light volume
        shaderLighting = cfg.getBoolean("shaderLighting", "client", shaderLighting, "Light every pixel of a mesh with a shader (smooth on any triangle size). Off = light per vertex, for very old graphics cards");
        cellSize = cfg.getInt("cellSize", "client", cellSize, 8, 128, "Meshes are split into cells of this many blocks for culling and light updates");
        mapOverlay = cfg.getBoolean("mapOverlay", "client", mapOverlay, "Draw placed models on JourneyMap: the model seen from above is laid over its footprint");
        mapPixels = cfg.getInt("mapPixels", "client", mapPixels, 1, 16, "Pixels per block of the picture a model gets on the map");
        if (cfg.hasChanged()) cfg.save();
    }
}

package ru.arthaix.meshimporter.client;

import java.awt.image.BufferedImage;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.lwjgl.opengl.GL11;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.culling.ClippingHelperImpl;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.common.network.FMLNetworkEvent;
import ru.arthaix.meshimporter.MeshImporter;
import ru.arthaix.meshimporter.MeshImporterConfig;
import ru.arthaix.meshimporter.client.render.EntityMeshRender;
import ru.arthaix.meshimporter.client.render.RenderData;
import ru.arthaix.meshimporter.client.render.Shaders;
import ru.arthaix.meshimporter.client.render.Textures;
import ru.arthaix.meshimporter.compat.MeshMapOverlays;
import ru.arthaix.meshimporter.instance.LoadedInstance;
import ru.arthaix.meshimporter.instance.MeshInstance;
import ru.arthaix.meshimporter.instance.MeshWorld;
import ru.arthaix.meshimporter.network.MsgInstances;

/**
 * Client world state: placed models announced by the server, their collision (MeshWorld.CLIENT) and render data,
 * the build preview, GPU uploads and light refresh spread over frames, and drawing.
 */
public final class ClientMeshes {

    public static final ClientMeshes INSTANCE = new ClientMeshes();

    public static final ExecutorService WORKERS = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "meshimporter-worker");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY + 1);
        return t;
    });

    private final Map<Integer, MeshInstance> known = new HashMap<>();
    private final Map<Integer, RenderData> renders = new HashMap<>();
    private final List<LoadedInstance> waitingForWorld = new ArrayList<>();
    private final ArrayDeque<RenderData> uploads = new ArrayDeque<>();
    private final List<RenderData> drawList = new ArrayList<>();
    private final BlockPos.MutableBlockPos lightPos = new BlockPos.MutableBlockPos();
    /** The picture of every loaded model seen from above, for map mods. */
    private final Map<Integer, BufferedImage> snapshots = new HashMap<>();
    private static volatile MeshMapOverlays overlays;
    private static Boolean mapMod;
    private RenderData preview;
    private EntityMeshRender placeholder;
    private int lightCursor;
    /** Whether models were built for our own shader, so a shader pack being switched can be noticed. */
    private Boolean lastShaded;

    private ClientMeshes() {}

    // ---- server messages ----

    public void onInstances(MsgInstances msg) {
        if (msg.full) clearInstances();
        for (MeshInstance in : msg.instances) {
            MeshInstance old = known.put(in.id, in);
            if (old != null) drop(old);
            load(in);
            if (preview != null && preview.li.instance.anchorX == in.anchorX && preview.li.instance.anchorY == in.anchorY
                && preview.li.instance.anchorZ == in.anchorZ && preview.li.instance.dim == in.dim) clearPreview();
        }
    }

    public void onRemove(int dim, int id) {
        MeshInstance in = known.remove(id);
        if (in != null) drop(in);
        else MeshWorld.CLIENT.remove(dim, id);
    }

    private void drop(MeshInstance in) {
        MeshWorld.CLIENT.remove(in.dim, in.id);
        snapshots.remove(in.id);
        MeshMapOverlays sink = overlays;
        if (sink != null) sink.hide(in.dim, in.id);
        RenderData rd = renders.remove(in.id);
        if (rd != null) {
            uploads.remove(rd);
            rd.dispose();
        }
        waitingForWorld.removeIf(li -> li.instance.id == in.id);
        boolean used = preview != null && preview.li.instance.hash.equals(in.hash);
        for (MeshInstance k : known.values()) if (k.hash.equals(in.hash)) used = true;
        if (!used) ModelCache.INSTANCE.forget(in.hash);
    }

    private void load(MeshInstance in) {
        ModelCache.INSTANCE.get(in.hash, model -> {
            if (known.get(in.id) != in) return;
            WORKERS.execute(() -> {
                LoadedInstance li;
                try {
                    li = LoadedInstance.load(in, model);
                } catch (RuntimeException | OutOfMemoryError e) {
                    MeshImporter.logger.error("Cannot load model #" + in.id, e);
                    return;
                }
                Minecraft.getMinecraft().addScheduledTask(() -> {
                    if (known.get(in.id) != in) return;
                    MeshWorld.CLIENT.add(li);
                    startRender(li);
                });
            });
        });
    }

    private void startRender(LoadedInstance li) {
        World world = Minecraft.getMinecraft().world;
        if (world == null) {
            waitingForWorld.add(li);
            return;
        }
        Shaders.ensure(); // decides per-pixel or per-vertex light before the model is built
        RenderData rd = new RenderData(li, world);
        // block textures and biome tints for the map picture have to be read here, on the game thread
        MapSnapshot.Look[] looks = wantsMapPicture(li) ? MapSnapshot.look(li.model, world, new BlockPos(li.instance.anchorX, li.instance.anchorY, li.instance.anchorZ)) : null;
        if (li.preview) {
            clearPreview();
            preview = rd;
        } else {
            RenderData old = renders.put(li.instance.id, rd);
            if (old != null) {
                uploads.remove(old);
                old.dispose();
            }
        }
        WORKERS.execute(() -> {
            try {
                rd.build();
            } catch (RuntimeException | OutOfMemoryError e) {
                MeshImporter.logger.error("Cannot prepare model #" + li.instance.id + " for drawing", e);
                return;
            }
            // shadows are part of the build, so the model shows up already shaded
            Minecraft.getMinecraft().addScheduledTask(() -> {
                if (!rd.disposed) uploads.add(rd);
            });
            if (looks != null) mapPicture(li, looks);
        });
    }

    // ---- map mods ----

    /** A map mod (JourneyMap) takes over drawing placed models: hand it what is already loaded. */
    public static void setMapOverlays(MeshMapOverlays sink) {
        overlays = sink;
        Minecraft.getMinecraft().addScheduledTask(() -> {
            if (overlays != sink) return;
            for (Map.Entry<Integer, BufferedImage> e : INSTANCE.snapshots.entrySet()) {
                MeshInstance in = INSTANCE.known.get(e.getKey());
                if (in != null) sink.show(in, e.getValue());
            }
        });
    }

    private static boolean wantsMapPicture(LoadedInstance li) {
        if (li.preview || !MeshImporterConfig.mapOverlay) return false;
        if (mapMod == null) mapMod = Loader.isModLoaded("journeymap");
        return mapMod;
    }

    /** Worker thread: draw the model from above, then hand the picture to the map mod on the game thread. */
    private void mapPicture(LoadedInstance li, MapSnapshot.Look[] looks) {
        BufferedImage image;
        try {
            image = MapSnapshot.render(li, looks, MeshImporterConfig.mapPixels);
        } catch (RuntimeException | OutOfMemoryError e) {
            MeshImporter.logger.warn("No map picture for model #" + li.instance.id + ": " + e);
            return;
        }
        if (image == null) return;
        int painted = 0;
        for (int y = 0; y < image.getHeight(); y++)
            for (int x = 0; x < image.getWidth(); x++)
                if ((image.getRGB(x, y) >>> 24) > 8) painted++;
        MeshImporter.logger.info("Map picture of model #" + li.instance.id + ": " + image.getWidth() + "x" + image.getHeight()
            + ", " + painted + " painted pixels");
        Minecraft.getMinecraft().addScheduledTask(() -> {
            if (known.get(li.instance.id) != li.instance) return;
            snapshots.put(li.instance.id, image);
            MeshMapOverlays sink = overlays;
            if (sink != null) sink.show(li.instance, image);
        });
    }

    // ---- preview ----

    public void setPreview(LoadedInstance li) {
        li.preview = true;
        startRender(li);
    }

    public void clearPreview() {
        if (preview == null) return;
        uploads.remove(preview);
        preview.dispose();
        preview = null;
    }

    public double[] previewBounds() {
        return preview == null ? null : preview.li.instance.bounds;
    }

    // ---- frames ----

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        WorldClient world = Minecraft.getMinecraft().world;
        if (world == null) return;
        if (placeholder == null || placeholder.world != world || !world.weatherEffects.contains(placeholder)) {
            placeholder = new EntityMeshRender(world);
            world.addWeatherEffect(placeholder);
        }
        // a shader pack switched on or off changes how a model has to be built: build them again
        boolean shaded = Shaders.active();
        if (lastShaded != null && lastShaded != shaded) rebuildAll();
        lastShaded = shaded;
        if (!waitingForWorld.isEmpty()) {
            List<LoadedInstance> copy = new ArrayList<>(waitingForWorld);
            waitingForWorld.clear();
            for (LoadedInstance li : copy) startRender(li);
        }
    }

    @SubscribeEvent
    public void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.START) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.world == null) return;
        long budget = 16L << 20;
        while (!uploads.isEmpty() && budget > 0) {
            RenderData rd = uploads.peek();
            if (rd.disposed) {
                uploads.poll();
                continue;
            }
            budget -= rd.upload(budget);
            if (rd.uploadDone()) uploads.poll();
        }
        int ms = MeshImporterConfig.lightMillisPerFrame;
        Entity view = mc.getRenderViewEntity();
        if (ms <= 0 || view == null) return;
        List<RenderData> list = current(mc.world.provider.getDimension());
        if (list.isEmpty()) return;
        long deadline = System.nanoTime() + ms * 1_000_000L;
        for (int i = 0; i < list.size(); i++) {
            int at = (lightCursor + i) % list.size();
            if (list.get(at).refreshLight(mc.world, deadline, view.posX, view.posY, view.posZ, lightPos)) {
                lightCursor = at;
                return;
            }
        }
    }

    /** Builds every loaded model again, for example after a shader pack was switched on or off. */
    private void rebuildAll() {
        List<LoadedInstance> again = new ArrayList<>();
        for (RenderData rd : renders.values()) again.add(rd.li);
        if (again.isEmpty()) return;
        MeshImporter.logger.info("MeshImporter: shader pack changed, rebuilding " + again.size() + " models");
        for (LoadedInstance li : again) startRender(li);
    }

    private List<RenderData> current(int dim) {
        drawList.clear();
        for (RenderData rd : renders.values()) if (rd.li.instance.dim == dim && rd.hasSomethingToDraw()) drawList.add(rd);
        if (preview != null && preview.li.instance.dim == dim && preview.hasSomethingToDraw()) drawList.add(preview);
        return drawList;
    }

    /** Called from the placeholder entity's renderer: pass 0 = opaque (before water), 1 = translucent. */
    public void render(int pass, double camX, double camY, double camZ) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.world == null) return;
        List<RenderData> list = current(mc.world.provider.getDimension());
        if (list.isEmpty()) return;
        Frustum frustum = new Frustum(ClippingHelperImpl.getInstance());
        frustum.setPosition(camX, camY, camZ);
        double maxDist = MeshImporterConfig.renderDistance;

        boolean shader = Shaders.active();
        boolean pack = !shader && Shaders.shaderPack();
        // glass casts no shadow: in the pack's shadow map it came out as a solid caster (depth is written for
        // see-through surfaces under a pack) and put whole facades under glazed loggias into shade
        if (pack && pass == 1 && Shaders.packShadowPass()) return;
        // first, because switching the pack's program sets its own blend state
        if (pack && pass == 1) Shaders.beginPackTranslucent();

        mc.entityRenderer.enableLightmap();
        GlStateManager.disableCull();
        GlStateManager.enableTexture2D();
        GlStateManager.disableLighting();
        GlStateManager.shadeModel(GL11.GL_SMOOTH);
        if (pass == 1) {
            GlStateManager.enableBlend();
            GlStateManager.tryBlendFuncSeparate(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA, GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ZERO);
            // a pack sorts translucent surfaces by the depth they write, as it does for water
            GlStateManager.depthMask(pack);
        } else {
            GlStateManager.disableBlend();
            GlStateManager.enableAlpha();
            GlStateManager.alphaFunc(GL11.GL_GREATER, 0.1f);
            GlStateManager.depthMask(true);
        }
        GlStateManager.glEnableClientState(GL11.GL_VERTEX_ARRAY);
        GlStateManager.glEnableClientState(GL11.GL_COLOR_ARRAY);
        GlStateManager.glEnableClientState(GL11.GL_NORMAL_ARRAY);
        OpenGlHelper.setClientActiveTexture(OpenGlHelper.defaultTexUnit);
        GlStateManager.glEnableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
        OpenGlHelper.setClientActiveTexture(OpenGlHelper.lightmapTexUnit);
        GlStateManager.glEnableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
        OpenGlHelper.setClientActiveTexture(OpenGlHelper.defaultTexUnit);

        if (shader) Shaders.begin(pass);
        if (pack) Shaders.beginPack();
        try {
            for (RenderData rd : new ArrayList<>(list)) rd.draw(pass, frustum, camX, camY, camZ, maxDist * maxDist);
        } finally {
            if (shader) Shaders.end();
            if (pack) Shaders.endPack();
            if (pack && pass == 1) Shaders.endPackTranslucent();
        }

        OpenGlHelper.glBindBuffer(OpenGlHelper.GL_ARRAY_BUFFER, 0);
        GlStateManager.glDisableClientState(GL11.GL_VERTEX_ARRAY);
        GlStateManager.glDisableClientState(GL11.GL_COLOR_ARRAY);
        GlStateManager.glDisableClientState(GL11.GL_NORMAL_ARRAY);
        OpenGlHelper.setClientActiveTexture(OpenGlHelper.lightmapTexUnit);
        GlStateManager.glDisableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
        OpenGlHelper.setClientActiveTexture(OpenGlHelper.defaultTexUnit);
        GlStateManager.glDisableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
        GlStateManager.resetColor();
        GlStateManager.shadeModel(GL11.GL_FLAT);
        if (pass == 1) {
            GlStateManager.depthMask(true);
            GlStateManager.disableBlend();
        }
        GlStateManager.enableCull();
    }

    // ---- disconnect ----

    @SubscribeEvent
    public void onDisconnect(FMLNetworkEvent.ClientDisconnectionFromServerEvent event) {
        Minecraft.getMinecraft().addScheduledTask(this::clearAll);
    }

    private void clearInstances() {
        snapshots.clear();
        MeshMapOverlays sink = overlays;
        if (sink != null) sink.hideAll();
        for (RenderData rd : renders.values()) rd.dispose();
        renders.clear();
        known.clear();
        waitingForWorld.clear();
        uploads.removeIf(rd -> rd != preview);
        MeshWorld.CLIENT.clear();
        ru.arthaix.meshimporter.compat.RailSupport.CLIENT.clear();
    }

    private void clearAll() {
        clearInstances();
        clearPreview();
        uploads.clear();
        ModelCache.INSTANCE.clear();
        Textures.clear();
        WebTextures.reset();
        placeholder = null;
        BuildSession.INSTANCE.reset();
    }
}

package ru.arthaix.meshimporter.compat.journeymap;

import java.awt.image.BufferedImage;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;

import journeymap.client.api.ClientPlugin;
import journeymap.client.api.IClientAPI;
import journeymap.client.api.IClientPlugin;
import journeymap.client.api.display.Context;
import journeymap.client.api.display.ImageOverlay;
import journeymap.client.api.event.ClientEvent;
import journeymap.client.api.model.MapImage;
import net.minecraft.util.math.BlockPos;
import ru.arthaix.meshimporter.MeshImporter;
import ru.arthaix.meshimporter.client.ClientMeshes;
import ru.arthaix.meshimporter.compat.MeshMapOverlays;
import ru.arthaix.meshimporter.instance.MeshInstance;

/**
 * Placed models on JourneyMap: every model is laid over its own footprint as the picture of it seen from above (see
 * MapSnapshot). JourneyMap finds this class by itself; nothing in MeshImporter loads it, so the mod runs fine where
 * JourneyMap is not installed.
 */
@ClientPlugin
public class MeshJourneyMap implements IClientPlugin, MeshMapOverlays {

    private IClientAPI api;
    private final Map<Integer, ImageOverlay> shown = new HashMap<>();

    @Override
    public void initialize(IClientAPI clientApi) {
        api = clientApi;
        ClientMeshes.setMapOverlays(this);
        MeshImporter.logger.info("JourneyMap: placed models are drawn on the map");
    }

    @Override
    public String getModId() {
        return MeshImporter.MOD_ID;
    }

    @Override
    public void onEvent(ClientEvent event) {
        // nothing to react to: overlays follow the models the client has loaded
    }

    @Override
    public void show(MeshInstance instance, BufferedImage image) {
        if (api == null) return;
        hide(instance.dim, instance.id);
        double[] b = instance.bounds;
        BlockPos northWest = new BlockPos((int) Math.floor(b[0]), 0, (int) Math.floor(b[2]));
        BlockPos southEast = new BlockPos((int) Math.ceil(b[3]), 0, (int) Math.ceil(b[5]));
        // JourneyMap reads rotation and display size straight off the picture as numbers; left unset they are null
        // and every draw of the map throws, so give them the plain values
        MapImage picture = new MapImage(image);
        picture.setRotation(0);
        picture.setDisplayWidth(image.getWidth());
        picture.setDisplayHeight(image.getHeight());
        ImageOverlay overlay = new ImageOverlay(MeshImporter.MOD_ID, "mesh-" + instance.id, northWest, southEast, picture);
        overlay.setDimension(instance.dim);
        overlay.setTitle(instance.name == null || instance.name.isEmpty() ? "Model #" + instance.id : instance.name);
        overlay.setActiveUIs(EnumSet.of(Context.UI.Any));
        overlay.setActiveMapTypes(EnumSet.of(Context.MapType.Any));
        overlay.setDisplayOrder(50);
        try {
            api.show(overlay);
            shown.put(instance.id, overlay);
        } catch (Exception e) {
            MeshImporter.logger.warn("JourneyMap did not take model #" + instance.id + ": " + e);
        }
    }

    @Override
    public void hide(int dim, int id) {
        ImageOverlay overlay = shown.remove(id);
        if (overlay != null && api != null) api.remove(overlay);
    }

    @Override
    public void hideAll() {
        if (api != null) api.removeAll(MeshImporter.MOD_ID);
        shown.clear();
    }
}

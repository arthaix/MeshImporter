package ru.arthaix.meshimporter.client.render;

import net.minecraft.client.renderer.culling.ICamera;
import net.minecraft.client.renderer.entity.Render;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.client.MinecraftForgeClient;
import ru.arthaix.meshimporter.MeshImporter;
import ru.arthaix.meshimporter.client.ClientMeshes;

/** Draws every mesh of the current dimension when Minecraft renders the placeholder entity. */
public class RenderMeshes extends Render<EntityMeshRender> {

    private static boolean reported;

    public RenderMeshes(RenderManager manager) {
        super(manager);
        shadowSize = 0f;
    }

    @Override
    public boolean shouldRender(EntityMeshRender entity, ICamera camera, double camX, double camY, double camZ) {
        return true;
    }

    @Override
    public void doRender(EntityMeshRender entity, double x, double y, double z, float entityYaw, float partialTicks) {
        // the placeholder sits at 0,0,0, so -x/-y/-z is the render position of the camera
        try {
            ClientMeshes.INSTANCE.render(MinecraftForgeClient.getRenderPass(), -x, -y, -z);
        } catch (RuntimeException e) {
            if (!reported) {
                reported = true;
                MeshImporter.logger.error("Mesh rendering failed (further errors are not logged)", e);
            }
        }
    }

    @Override
    protected ResourceLocation getEntityTexture(EntityMeshRender entity) {
        return TextureMap.LOCATION_BLOCKS_TEXTURE;
    }
}

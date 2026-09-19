package ru.arthaix.meshimporter.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.ModelResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.client.event.ModelRegistryEvent;
import net.minecraftforge.client.model.ModelLoader;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.client.registry.RenderingRegistry;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import ru.arthaix.meshimporter.CommonProxy;
import ru.arthaix.meshimporter.MeshImporter;
import ru.arthaix.meshimporter.client.gui.GuiMeshAnchor;
import ru.arthaix.meshimporter.client.render.EntityMeshRender;
import ru.arthaix.meshimporter.client.render.RenderMeshes;
import ru.arthaix.meshimporter.network.MsgInstances;
import ru.arthaix.meshimporter.network.MsgModelPart;
import ru.arthaix.meshimporter.network.MsgUploadAck;

public class ClientProxy extends CommonProxy {

    @Override
    public void preInit() {
        RenderingRegistry.registerEntityRenderingHandler(EntityMeshRender.class, RenderMeshes::new);
        MinecraftForge.EVENT_BUS.register(this);
        MinecraftForge.EVENT_BUS.register(ClientMeshes.INSTANCE);
        MinecraftForge.EVENT_BUS.register(MeshPicking.INSTANCE);
        MinecraftForge.EVENT_BUS.register(AnchorBreak.INSTANCE);
    }

    @SubscribeEvent
    public void onModelRegistry(ModelRegistryEvent event) {
        ModelLoader.setCustomModelResourceLocation(MeshImporter.anchorItem, 0, new ModelResourceLocation(MeshImporter.anchorItem.getRegistryName(), "inventory"));
    }

    @Override
    public void openAnchorGui(World world, BlockPos pos) {
        Minecraft.getMinecraft().displayGuiScreen(new GuiMeshAnchor(pos));
    }

    @Override
    public void handleInstances(MsgInstances msg) {
        Minecraft.getMinecraft().addScheduledTask(() -> ClientMeshes.INSTANCE.onInstances(msg));
    }

    @Override
    public void handleInstanceRemove(int dim, int id) {
        Minecraft.getMinecraft().addScheduledTask(() -> ClientMeshes.INSTANCE.onRemove(dim, id));
    }

    @Override
    public void handleModelPart(MsgModelPart msg) {
        Minecraft.getMinecraft().addScheduledTask(() -> ModelCache.INSTANCE.onPart(msg));
    }

    @Override
    public void handleUploadAck(MsgUploadAck msg) {
        Minecraft.getMinecraft().addScheduledTask(() -> BuildSession.INSTANCE.onUploadAck(msg));
    }
}

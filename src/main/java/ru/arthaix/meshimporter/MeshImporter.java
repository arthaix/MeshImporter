package ru.arthaix.meshimporter;

import org.apache.logging.log4j.Logger;

import net.minecraft.block.Block;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBlock;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegistryEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.Mod.EventHandler;
import net.minecraftforge.fml.common.SidedProxy;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.event.FMLServerStartingEvent;
import net.minecraftforge.fml.common.event.FMLServerStoppedEvent;
import net.minecraftforge.fml.common.event.FMLServerStoppingEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.registry.GameRegistry;
import net.minecraft.util.ResourceLocation;
import ru.arthaix.meshimporter.block.BlockMeshAnchor;
import ru.arthaix.meshimporter.block.TileEntityMeshAnchor;
import ru.arthaix.meshimporter.collision.CollisionHandler;
import ru.arthaix.meshimporter.network.Net;
import ru.arthaix.meshimporter.server.CommandMeshImporter;
import ru.arthaix.meshimporter.server.MeshServer;

/**
 * MeshImporter: puts 3D models (.obj) into Minecraft as real meshes. No voxels, no tiles: a model is one file on the
 * server, streamed to clients, drawn with its own geometry, lit by the world, and solid for players and mobs.
 */
@Mod(modid = MeshImporter.MOD_ID, name = MeshImporter.NAME, version = MeshImporter.VERSION)
public class MeshImporter {

    public static final String MOD_ID = "meshimporter";
    public static final String NAME = "MeshImporter";
    public static final String VERSION = "1.0.0";
    public static Logger logger;

    @Mod.Instance(MOD_ID)
    public static MeshImporter instance;

    @SidedProxy(clientSide = "ru.arthaix.meshimporter.client.ClientProxy", serverSide = "ru.arthaix.meshimporter.CommonProxy")
    public static CommonProxy proxy;

    public static BlockMeshAnchor anchorBlock;
    public static Item anchorItem;

    @EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        logger = event.getModLog();
        MeshImporterConfig.load(event.getSuggestedConfigurationFile());
        MinecraftForge.EVENT_BUS.register(this);
        MinecraftForge.EVENT_BUS.register(MeshServer.INSTANCE);
        MinecraftForge.EVENT_BUS.register(CollisionHandler.INSTANCE);
        Net.init();
        proxy.preInit();
    }

    @EventHandler
    public void init(FMLInitializationEvent event) {
        proxy.init();
    }

    @SubscribeEvent
    public void registerBlocks(RegistryEvent.Register<Block> event) {
        anchorBlock = new BlockMeshAnchor();
        event.getRegistry().register(anchorBlock);
        GameRegistry.registerTileEntity(TileEntityMeshAnchor.class, new ResourceLocation(MOD_ID, "mesh_anchor"));
    }

    @SubscribeEvent
    public void registerItems(RegistryEvent.Register<Item> event) {
        anchorItem = new ItemBlock(anchorBlock).setRegistryName(anchorBlock.getRegistryName());
        event.getRegistry().register(anchorItem);
        proxy.registerItemModels();
    }

    @EventHandler
    public void serverStarting(FMLServerStartingEvent event) {
        event.registerServerCommand(new CommandMeshImporter());
    }

    @EventHandler
    public void serverStopping(FMLServerStoppingEvent event) {
        MeshServer.INSTANCE.onServerStopping();
    }

    @EventHandler
    public void serverStopped(FMLServerStoppedEvent event) {
        MeshServer.INSTANCE.onServerStopped();
    }
}

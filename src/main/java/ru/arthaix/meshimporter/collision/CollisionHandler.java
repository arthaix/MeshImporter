package ru.arthaix.meshimporter.collision;

import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.world.World;
import net.minecraftforge.event.world.GetCollisionBoxesEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import ru.arthaix.meshimporter.MeshImporterConfig;
import ru.arthaix.meshimporter.instance.LoadedInstance;
import ru.arthaix.meshimporter.instance.MeshWorld;

/** Adds mesh collision boxes to every collision query of the world (entity movement, spawn checks, pistons...). */
public final class CollisionHandler {

    public static final CollisionHandler INSTANCE = new CollisionHandler();

    private CollisionHandler() {}

    @SubscribeEvent
    public void onCollisionBoxes(GetCollisionBoxesEvent event) {
        if (!MeshImporterConfig.collision) return;
        World world = event.getWorld();
        if (world == null) return;
        MeshWorld meshes = MeshWorld.of(world);
        int dim = world.provider.getDimension();
        if (meshes.isEmpty(dim)) return;
        AxisAlignedBB box = event.getAabb();
        for (LoadedInstance li : meshes.inDimension(dim)) {
            if (!li.instance.intersects(box)) continue;
            li.grid.collectBoxes(box, event.getCollisionBoxesList());
        }
    }
}

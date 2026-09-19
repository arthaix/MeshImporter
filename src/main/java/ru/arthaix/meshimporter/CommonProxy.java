package ru.arthaix.meshimporter;

import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import ru.arthaix.meshimporter.network.MsgInstances;
import ru.arthaix.meshimporter.network.MsgModelPart;
import ru.arthaix.meshimporter.network.MsgUploadAck;

/** Side-specific hooks; the client proxy overrides these. On a dedicated server they do nothing. */
public class CommonProxy {

    public void preInit() {}

    public void init() {}

    public void registerItemModels() {}

    public void openAnchorGui(World world, BlockPos pos) {}

    public void handleInstances(MsgInstances msg) {}

    public void handleInstanceRemove(int dim, int id) {}

    public void handleModelPart(MsgModelPart msg) {}

    public void handleUploadAck(MsgUploadAck msg) {}
}

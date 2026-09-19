package ru.arthaix.meshimporter.network;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraftforge.fml.common.network.NetworkRegistry;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;
import net.minecraftforge.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import net.minecraftforge.fml.relauncher.Side;
import ru.arthaix.meshimporter.MeshImporter;

/** The mod's channel. Client-bound messages are handed to the proxy so no client class is touched on a server. */
public final class Net {

    public static final SimpleNetworkWrapper CH = NetworkRegistry.INSTANCE.newSimpleChannel(MeshImporter.MOD_ID);

    /** Largest payload of a client → server message (vanilla drops serverbound custom payloads above 32767 bytes). */
    public static final int UPLOAD_PART = 30_000;
    /** Payload of a server → client model part. */
    public static final int DOWNLOAD_PART = 256 * 1024;

    private Net() {}

    public static void init() {
        int id = 0;
        CH.registerMessage(MsgInstances.Handler.class, MsgInstances.class, id++, Side.CLIENT);
        CH.registerMessage(MsgInstanceRemove.Handler.class, MsgInstanceRemove.class, id++, Side.CLIENT);
        CH.registerMessage(MsgModelPart.Handler.class, MsgModelPart.class, id++, Side.CLIENT);
        CH.registerMessage(MsgUploadAck.Handler.class, MsgUploadAck.class, id++, Side.CLIENT);
        CH.registerMessage(MsgModelRequest.Handler.class, MsgModelRequest.class, id++, Side.SERVER);
        CH.registerMessage(MsgUploadBegin.Handler.class, MsgUploadBegin.class, id++, Side.SERVER);
        CH.registerMessage(MsgUploadPart.Handler.class, MsgUploadPart.class, id++, Side.SERVER);
        CH.registerMessage(MsgPlace.Handler.class, MsgPlace.class, id++, Side.SERVER);
        CH.registerMessage(MsgRemove.Handler.class, MsgRemove.class, id++, Side.SERVER);
        CH.registerMessage(MsgPlaceOnMesh.Handler.class, MsgPlaceOnMesh.class, id++, Side.SERVER);
        CH.registerMessage(MsgTeleport.Handler.class, MsgTeleport.class, id++, Side.SERVER);
        CH.registerMessage(MsgAnchorSettings.Handler.class, MsgAnchorSettings.class, id++, Side.SERVER);
        CH.registerMessage(MsgWebTexture.Handler.class, MsgWebTexture.class, id++, Side.SERVER);
        CH.registerMessage(MsgBreakAnchor.Handler.class, MsgBreakAnchor.class, id++, Side.SERVER);
    }

    /** Runs the handler body on the server thread with the sending player. */
    static IMessage onServer(MessageContext ctx, java.util.function.Consumer<EntityPlayerMP> body) {
        EntityPlayerMP player = ctx.getServerHandler().player;
        player.getServerWorld().addScheduledTask(() -> body.accept(player));
        return null;
    }
}

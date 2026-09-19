package ru.arthaix.meshimporter.network;

import io.netty.buffer.ByteBuf;
import net.minecraftforge.fml.common.network.ByteBufUtils;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;
import ru.arthaix.meshimporter.server.MeshServer;

/** Client → server: send me this model file. */
public class MsgModelRequest implements IMessage {

    public String hash = "";

    public MsgModelRequest() {}

    public MsgModelRequest(String hash) {
        this.hash = hash;
    }

    @Override
    public void toBytes(ByteBuf buf) {
        ByteBufUtils.writeUTF8String(buf, hash);
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        hash = ByteBufUtils.readUTF8String(buf);
    }

    public static class Handler implements IMessageHandler<MsgModelRequest, IMessage> {
        @Override
        public IMessage onMessage(MsgModelRequest msg, MessageContext ctx) {
            return Net.onServer(ctx, p -> MeshServer.INSTANCE.requestModel(p, msg.hash));
        }
    }
}

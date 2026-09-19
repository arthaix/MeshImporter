package ru.arthaix.meshimporter.network;

import io.netty.buffer.ByteBuf;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;
import ru.arthaix.meshimporter.MeshImporter;

/** Server → client: a placed model is gone. */
public class MsgInstanceRemove implements IMessage {

    public int dim, id;

    public MsgInstanceRemove() {}

    public MsgInstanceRemove(int dim, int id) {
        this.dim = dim;
        this.id = id;
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(dim);
        buf.writeInt(id);
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        dim = buf.readInt();
        id = buf.readInt();
    }

    public static class Handler implements IMessageHandler<MsgInstanceRemove, IMessage> {
        @Override
        public IMessage onMessage(MsgInstanceRemove msg, MessageContext ctx) {
            MeshImporter.proxy.handleInstanceRemove(msg.dim, msg.id);
            return null;
        }
    }
}

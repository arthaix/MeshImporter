package ru.arthaix.meshimporter.network;

import io.netty.buffer.ByteBuf;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;
import ru.arthaix.meshimporter.server.MeshServer;

/** Client → server: teleport me (creative/op only). */
public class MsgTeleport implements IMessage {

    public double x, y, z;

    public MsgTeleport() {}

    public MsgTeleport(double x, double y, double z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeDouble(x);
        buf.writeDouble(y);
        buf.writeDouble(z);
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        x = buf.readDouble();
        y = buf.readDouble();
        z = buf.readDouble();
    }

    public static class Handler implements IMessageHandler<MsgTeleport, IMessage> {
        @Override
        public IMessage onMessage(MsgTeleport msg, MessageContext ctx) {
            return Net.onServer(ctx, p -> MeshServer.INSTANCE.teleport(p, msg.x, msg.y, msg.z));
        }
    }
}

package ru.arthaix.meshimporter.network;

import io.netty.buffer.ByteBuf;
import net.minecraftforge.fml.common.network.ByteBufUtils;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;
import ru.arthaix.meshimporter.server.MeshServer;

/** Client → server: the texture and tint of a block face used by a placed model, for the web map export. */
public class MsgWebTexture implements IMessage {

    public String key = "";
    public int tint = -1;
    public byte[] png = new byte[0];

    public MsgWebTexture() {}

    public MsgWebTexture(String key, int tint, byte[] png) {
        this.key = key;
        this.tint = tint;
        this.png = png;
    }

    @Override
    public void toBytes(ByteBuf buf) {
        ByteBufUtils.writeUTF8String(buf, key);
        buf.writeInt(tint);
        buf.writeInt(png.length);
        buf.writeBytes(png);
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        key = ByteBufUtils.readUTF8String(buf);
        tint = buf.readInt();
        int n = buf.readInt();
        if (n < 0 || n > 32_000 || n > buf.readableBytes()) {
            png = new byte[0];
            return;
        }
        png = new byte[n];
        buf.readBytes(png);
    }

    public static class Handler implements IMessageHandler<MsgWebTexture, IMessage> {
        @Override
        public IMessage onMessage(MsgWebTexture msg, MessageContext ctx) {
            return Net.onServer(ctx, p -> MeshServer.INSTANCE.storeWebTexture(p, msg.key, msg.tint, msg.png));
        }
    }
}

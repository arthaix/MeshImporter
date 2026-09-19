package ru.arthaix.meshimporter.network;

import io.netty.buffer.ByteBuf;
import net.minecraftforge.fml.common.network.ByteBufUtils;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;
import ru.arthaix.meshimporter.server.MeshServer;

/** Client → server: one part of a model file. */
public class MsgUploadPart implements IMessage {

    public String hash = "";
    public int part;
    public byte[] data = new byte[0];

    public MsgUploadPart() {}

    public MsgUploadPart(String hash, int part, byte[] data) {
        this.hash = hash;
        this.part = part;
        this.data = data;
    }

    @Override
    public void toBytes(ByteBuf buf) {
        ByteBufUtils.writeUTF8String(buf, hash);
        buf.writeInt(part);
        buf.writeInt(data.length);
        buf.writeBytes(data);
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        hash = ByteBufUtils.readUTF8String(buf);
        part = buf.readInt();
        int n = buf.readInt();
        if (n < 0 || n > Net.UPLOAD_PART) throw new IllegalArgumentException("bad upload part size " + n);
        data = new byte[n];
        buf.readBytes(data);
    }

    public static class Handler implements IMessageHandler<MsgUploadPart, IMessage> {
        @Override
        public IMessage onMessage(MsgUploadPart msg, MessageContext ctx) {
            return Net.onServer(ctx, p -> MeshServer.INSTANCE.uploadPart(p, msg.hash, msg.part, msg.data));
        }
    }
}

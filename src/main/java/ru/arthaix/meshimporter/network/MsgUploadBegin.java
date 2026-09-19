package ru.arthaix.meshimporter.network;

import io.netty.buffer.ByteBuf;
import net.minecraftforge.fml.common.network.ByteBufUtils;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;
import ru.arthaix.meshimporter.server.MeshServer;

/** Client → server: a model file upload starts. */
public class MsgUploadBegin implements IMessage {

    public String hash = "";
    public int totalBytes, parts;
    public String name = "";

    public MsgUploadBegin() {}

    public MsgUploadBegin(String hash, int totalBytes, int parts, String name) {
        this.hash = hash;
        this.totalBytes = totalBytes;
        this.parts = parts;
        this.name = name;
    }

    @Override
    public void toBytes(ByteBuf buf) {
        ByteBufUtils.writeUTF8String(buf, hash);
        buf.writeInt(totalBytes);
        buf.writeInt(parts);
        ByteBufUtils.writeUTF8String(buf, name);
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        hash = ByteBufUtils.readUTF8String(buf);
        totalBytes = buf.readInt();
        parts = buf.readInt();
        name = ByteBufUtils.readUTF8String(buf);
    }

    public static class Handler implements IMessageHandler<MsgUploadBegin, IMessage> {
        @Override
        public IMessage onMessage(MsgUploadBegin msg, MessageContext ctx) {
            return Net.onServer(ctx, p -> MeshServer.INSTANCE.beginUpload(p, msg.hash, msg.totalBytes, msg.parts, msg.name));
        }
    }
}

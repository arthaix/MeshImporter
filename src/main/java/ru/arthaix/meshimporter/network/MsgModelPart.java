package ru.arthaix.meshimporter.network;

import io.netty.buffer.ByteBuf;
import net.minecraftforge.fml.common.network.ByteBufUtils;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;
import ru.arthaix.meshimporter.MeshImporter;

/** Server → client: one part of a model file. */
public class MsgModelPart implements IMessage {

    public String hash = "";
    public int part, total, totalBytes;
    public byte[] data = new byte[0];

    public MsgModelPart() {}

    public MsgModelPart(String hash, int part, int total, int totalBytes, byte[] data) {
        this.hash = hash;
        this.part = part;
        this.total = total;
        this.totalBytes = totalBytes;
        this.data = data;
    }

    @Override
    public void toBytes(ByteBuf buf) {
        ByteBufUtils.writeUTF8String(buf, hash);
        buf.writeInt(part);
        buf.writeInt(total);
        buf.writeInt(totalBytes);
        buf.writeInt(data.length);
        buf.writeBytes(data);
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        hash = ByteBufUtils.readUTF8String(buf);
        part = buf.readInt();
        total = buf.readInt();
        totalBytes = buf.readInt();
        int n = buf.readInt();
        if (n < 0 || n > Net.DOWNLOAD_PART) throw new IllegalArgumentException("bad model part size " + n);
        data = new byte[n];
        buf.readBytes(data);
    }

    public static class Handler implements IMessageHandler<MsgModelPart, IMessage> {
        @Override
        public IMessage onMessage(MsgModelPart msg, MessageContext ctx) {
            MeshImporter.proxy.handleModelPart(msg);
            return null;
        }
    }
}

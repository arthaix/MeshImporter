package ru.arthaix.meshimporter.network;

import io.netty.buffer.ByteBuf;
import net.minecraftforge.fml.common.network.ByteBufUtils;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;
import ru.arthaix.meshimporter.MeshImporter;

/** Server → client: upload progress (send more), done, or failed. */
public class MsgUploadAck implements IMessage {

    public static final int CONTINUE = 0, DONE = 1, ERROR = 2, EXISTS = 3;

    public String hash = "";
    public int received;
    public int status;
    public String message = "";

    public MsgUploadAck() {}

    public MsgUploadAck(String hash, int received, int status, String message) {
        this.hash = hash;
        this.received = received;
        this.status = status;
        this.message = message == null ? "" : message;
    }

    @Override
    public void toBytes(ByteBuf buf) {
        ByteBufUtils.writeUTF8String(buf, hash);
        buf.writeInt(received);
        buf.writeByte(status);
        ByteBufUtils.writeUTF8String(buf, message);
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        hash = ByteBufUtils.readUTF8String(buf);
        received = buf.readInt();
        status = buf.readByte();
        message = ByteBufUtils.readUTF8String(buf);
    }

    public static class Handler implements IMessageHandler<MsgUploadAck, IMessage> {
        @Override
        public IMessage onMessage(MsgUploadAck msg, MessageContext ctx) {
            MeshImporter.proxy.handleUploadAck(msg);
            return null;
        }
    }
}

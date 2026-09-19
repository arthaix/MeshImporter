package ru.arthaix.meshimporter.network;

import java.util.ArrayList;
import java.util.List;

import io.netty.buffer.ByteBuf;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;
import ru.arthaix.meshimporter.MeshImporter;
import ru.arthaix.meshimporter.instance.MeshInstance;

/** Server → client: placed models (all of them on login, or the ones that were just added). */
public class MsgInstances implements IMessage {

    public boolean full;
    public List<MeshInstance> instances = new ArrayList<>();

    public MsgInstances() {}

    public MsgInstances(boolean full, List<MeshInstance> instances) {
        this.full = full;
        this.instances = instances;
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeBoolean(full);
        buf.writeInt(instances.size());
        for (MeshInstance in : instances) in.write(buf);
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        full = buf.readBoolean();
        int n = buf.readInt();
        instances = new ArrayList<>(n);
        for (int i = 0; i < n; i++) instances.add(MeshInstance.read(buf));
    }

    public static class Handler implements IMessageHandler<MsgInstances, IMessage> {
        @Override
        public IMessage onMessage(MsgInstances msg, MessageContext ctx) {
            MeshImporter.proxy.handleInstances(msg);
            return null;
        }
    }
}

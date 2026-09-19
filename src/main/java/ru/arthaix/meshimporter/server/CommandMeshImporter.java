package ru.arthaix.meshimporter.server;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.annotation.Nullable;

import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextFormatting;
import ru.arthaix.meshimporter.instance.MeshInstance;

/** /meshimporter list | remove <id> | tp <id> */
public class CommandMeshImporter extends CommandBase {

    @Override
    public String getName() {
        return "meshimporter";
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "/meshimporter list | remove <id> | tp <id>";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 2;
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        if (args.length == 0) throw new CommandException(getUsage(sender));
        MeshServer m = MeshServer.INSTANCE;
        switch (args[0].toLowerCase()) {
            case "list":
                m.list(sender);
                break;
            case "remove": {
                if (args.length < 2) throw new CommandException("/meshimporter remove <id>");
                m.remove(parseInt(args[1], 1), sender);
                break;
            }
            case "tp": {
                if (args.length < 2) throw new CommandException("/meshimporter tp <id>");
                EntityPlayerMP player = getCommandSenderAsPlayer(sender);
                MeshInstance in = m.registry() == null ? null : m.registry().find(parseInt(args[1], 1));
                if (in == null) throw new CommandException("No model #" + args[1]);
                if (in.dim != player.dimension) throw new CommandException("Model #" + in.id + " is in dimension " + in.dim);
                double cx = (in.bounds[0] + in.bounds[3]) / 2, cz = (in.bounds[2] + in.bounds[5]) / 2;
                m.teleport(player, cx, Math.min(255, in.bounds[4] + 3), cz);
                MeshServer.msg(sender, TextFormatting.GRAY + "Teleported above model #" + in.id);
                break;
            }
            default:
                throw new CommandException(getUsage(sender));
        }
    }

    @Override
    public List<String> getTabCompletions(MinecraftServer server, ICommandSender sender, String[] args, @Nullable BlockPos targetPos) {
        if (args.length == 1) return getListOfStringsMatchingLastWord(args, "list", "remove", "tp");
        if (args.length == 2 && MeshServer.INSTANCE.registry() != null) {
            List<String> ids = new ArrayList<>();
            for (MeshInstance in : MeshServer.INSTANCE.registry().all()) ids.add(in.id + "");
            return getListOfStringsMatchingLastWord(args, ids);
        }
        return Arrays.asList();
    }
}

package ru.arthaix.meshimporter.server;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.annotation.Nullable;

import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import java.util.Map;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextFormatting;
import ru.arthaix.meshimporter.compat.MeshRailLayer;
import ru.arthaix.meshimporter.instance.MeshInstance;

/** /meshimporter list | remove <id> | tp <id> | rails <file> <line|all> <model> [step] [clear] | rails stop */
public class CommandMeshImporter extends CommandBase {

    @Override
    public String getName() {
        return "meshimporter";
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "/meshimporter list | remove <id> | tp <id> | rails <file> <line|all> <model> [step] [clear]";
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
            case "rails": {
                rails(server, sender, args);
                break;
            }
            default:
                throw new CommandException(getUsage(sender));
        }
    }

    /**
     * Lays Immersive Railroading track along a line drawn in Blender. The line comes from a json in
     * config/meshimporter/paths, a placed model says where it lands in the world, and the blueprint in hand gives the
     * gauge, the rail bed and the track style.
     */
    private void rails(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        MeshServer m = MeshServer.INSTANCE;
        if (args.length >= 2 && "stop".equalsIgnoreCase(args[1])) {
            m.stopLayingRails(sender);
            return;
        }
        if (args.length < 4) throw new CommandException("/meshimporter rails <file> <line|all> <model> [step] [clear] [from-to]");
        if (m.layingRails()) throw new CommandException("Track is already being laid; /meshimporter rails stop");
        EntityPlayerMP player = getCommandSenderAsPlayer(sender);
        ItemStack blueprint = player.getHeldItemMainhand();
        if (!MeshRailLayer.isBlueprint(blueprint)) throw new CommandException("Hold an Immersive Railroading track blueprint");

        File file = new File(RailPaths.folder(), args[1].endsWith(".json") ? args[1] : args[1] + ".json");
        if (!file.isFile()) throw new CommandException("No line file " + file.getPath());
        MeshInstance in = m.registry() == null ? null : m.registry().find(parseInt(args[3], 1));
        if (in == null) throw new CommandException("No model #" + args[3]);
        double step = args.length > 4 ? parseDouble(args[4], 2, 64) : 16;
        boolean clear = args.length > 5 && "clear".equalsIgnoreCase(args[5]);

        Map<String, List<double[][]>> lines;
        try {
            lines = RailPaths.read(file);
        } catch (IOException e) {
            throw new CommandException("Cannot read " + file.getName() + ": " + e.getMessage());
        }
        List<double[]> points = new ArrayList<>();
        int used = 0;
        for (Map.Entry<String, List<double[][]>> e : lines.entrySet()) {
            if (!"all".equalsIgnoreCase(args[2]) && !e.getKey().equalsIgnoreCase(args[2])) continue;
            used++;
            for (double[][] line : e.getValue())
                for (double[][] run : RailPaths.split(RailPaths.toWorld(line, in), 32))
                    points.addAll(RailPaths.resample(run, step));
        }
        if (used == 0) throw new CommandException("No line called " + args[2] + " in " + file.getName() + " (it holds: " + String.join(", ", lines.keySet()) + ")");
        if (points.size() < 2) throw new CommandException("That line has no length");

        // an optional stretch along the line, in blocks: "0-300" lays the first 300 blocks, for a look before the rest
        String range = null;
            for (int i = 4; i < args.length; i++) if (args[i].matches("[0-9]+-[0-9]+")) range = args[i];
        if (range != null) {
            String[] parts = range.split("-");
            double from = Double.parseDouble(parts[0]), to = Double.parseDouble(parts[1]);
            List<double[]> cut = new ArrayList<>();
            double along = 0;
            for (int i = 0; i < points.size(); i++) {
                if (i > 0) along += RailPaths.distance(points.get(i - 1), points.get(i));
                if (along >= from && along <= to) cut.add(points.get(i));
            }
            if (cut.size() < 2) throw new CommandException("Nothing of the line lies between " + from + " and " + to);
            points = cut;
            MeshServer.msg(sender, TextFormatting.GRAY + "Only the stretch " + range + " blocks along the line");
        }

        if (clear) {
            int removed = MeshRailLayer.clear(player.getServerWorld(), points, 3);
            MeshServer.msg(sender, TextFormatting.GRAY + "Removed " + removed + " blocks of old track along the line");
        }
        MeshServer.msg(sender, TextFormatting.GRAY + "Blueprint: " + MeshRailLayer.describe(blueprint));
        m.layRails(player, blueprint, points, 1.0 / 6, 4, args[2] + " of " + file.getName());
    }

    @Override
    public List<String> getTabCompletions(MinecraftServer server, ICommandSender sender, String[] args, @Nullable BlockPos targetPos) {
        if (args.length == 1) return getListOfStringsMatchingLastWord(args, "list", "remove", "tp", "rails");
        if (args.length == 2 && "rails".equalsIgnoreCase(args[0])) {
            List<String> names = new ArrayList<>();
            names.add("stop");
            File[] files = RailPaths.folder().listFiles((d, n) -> n.endsWith(".json"));
            if (files != null) for (File f : files) names.add(f.getName().replace(".json", ""));
            return getListOfStringsMatchingLastWord(args, names);
        }
        if (args.length == 2 && MeshServer.INSTANCE.registry() != null) {
            List<String> ids = new ArrayList<>();
            for (MeshInstance in : MeshServer.INSTANCE.registry().all()) ids.add(in.id + "");
            return getListOfStringsMatchingLastWord(args, ids);
        }
        return Arrays.asList();
    }
}

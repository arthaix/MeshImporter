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
    /**
     * The player the command is laying track for. {@code getCommandSenderAsPlayer} wants the sender itself to be the
     * player, which rules out {@code /execute <player> ~ ~ ~ meshimporter ...} - and that is the one way a whole
     * sequence of these can be run from a console or over rcon. Whoever the command is run as counts here.
     */
    private static EntityPlayerMP playerOf(ICommandSender sender) throws CommandException {
        if (sender instanceof EntityPlayerMP) return (EntityPlayerMP) sender;
        if (sender.getCommandSenderEntity() instanceof EntityPlayerMP) return (EntityPlayerMP) sender.getCommandSenderEntity();
        throw new CommandException("Run this as a player, or with /execute <player> ~ ~ ~ meshimporter ...");
    }

    private void rails(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        MeshServer m = MeshServer.INSTANCE;
        if (args.length >= 2 && "stop".equalsIgnoreCase(args[1])) {
            m.stopLayingRails(sender);
            return;
        }
        if (args.length < 4) throw new CommandException("/meshimporter rails <file> <line|all> <model> [longest piece] [tolerance] [clear|clearonly] [over] [turnout] [from-to]");
        if (m.layingRails()) throw new CommandException("Track is already being laid; /meshimporter rails stop");
        EntityPlayerMP player = playerOf(sender);
        ItemStack blueprint = player.getHeldItemMainhand();
        if (!MeshRailLayer.isBlueprint(blueprint)) throw new CommandException("Hold an Immersive Railroading track blueprint");

        File file = new File(RailPaths.folder(), args[1].endsWith(".json") ? args[1] : args[1] + ".json");
        if (!file.isFile()) throw new CommandException("No line file " + file.getPath());
        MeshInstance in = m.registry() == null ? null : m.registry().find(parseInt(args[3], 1));
        if (in == null) throw new CommandException("No model #" + args[3]);
        // A layout worked out beforehand: every piece given outright as four points - where it starts, a point along
        // its heading there, a point along its heading at the far end, and where it ends - in the order they are to
        // be laid. Nothing is cut, fitted or estimated here, so the joints are the ones that were drawn and every
        // anchor lands where it was planned to. Entries with "switch" in their name are built as turnouts.
        if ("pieces".equalsIgnoreCase(args[2])) {
            boolean laidOver = false;
            for (String arg : args) if ("over".equalsIgnoreCase(arg)) laidOver = true;
            Map<String, List<double[][]>> given;
            try {
                given = RailPaths.read(file);
            } catch (IOException e) {
                throw new CommandException("Cannot read " + file.getName() + ": " + e.getMessage());
            }
            List<double[][]> ready = new ArrayList<>();
            int turnouts = 0;
            for (Map.Entry<String, List<double[][]>> e : given.entrySet()) {
                boolean isSwitch = e.getKey().toLowerCase(java.util.Locale.ROOT).contains("switch");
                for (double[][] four : e.getValue()) {
                    if (four.length != 4) throw new CommandException(e.getKey() + ": a piece is four points, this one has " + four.length);
                    double[][] w = RailPaths.toWorld(four, in);
                    ready.add(new double[][] { w[0], w[3], { RailPaths.yaw(w[0], w[1]), RailPaths.yaw(w[2], w[3]), isSwitch ? 1 : 0 } });
                    if (isSwitch) turnouts++;
                }
            }
            if (ready.isEmpty()) throw new CommandException("No pieces in " + file.getName());
            MeshServer.msg(sender, TextFormatting.GRAY + "Blueprint: " + MeshRailLayer.describe(blueprint));
            MeshServer.msg(sender, TextFormatting.GRAY + "" + ready.size() + " pieces as drawn, " + turnouts + " of them turnouts");
            m.layRails(player, blueprint, ready, 0, laidOver, false, 2, "layout " + file.getName());
            return;
        }
        // how long a piece may grow, and how far it may ever stray from the drawn line
        double longest = 200, tolerance = 0.01;
        for (int i = 4; i < args.length; i++) {
            if (args[i].matches("[0-9]+")) longest = parseDouble(args[i], 8, 400);
            else if (args[i].matches("0[.][0-9]+")) tolerance = parseDouble(args[i], 0.001, 1);
        }
        boolean clear = false, over = false, clearOnly = false, turnout = false;
        for (String arg : args) {
            if ("clear".equalsIgnoreCase(arg)) clear = true;
            // take the old track out and stop there. Laying a line in two commands needs this: clearing reaches a
            // little past the stretch it is given, and would eat into track the other command has just laid
            if ("clearonly".equalsIgnoreCase(arg)) clear = clearOnly = true;
            // for a crossover: let a piece be laid through track that is already there, the way a turnout shares
            // ground with the line it leaves
            if ("over".equalsIgnoreCase(arg)) over = true;
            // build the first piece of the line as a switch: the line it leaves goes straight through it
            if ("turnout".equalsIgnoreCase(arg)) turnout = true;
        }

        Map<String, List<double[][]>> lines;
        try {
            lines = RailPaths.read(file);
        } catch (IOException e) {
            throw new CommandException("Cannot read " + file.getName() + ": " + e.getMessage());
        }
        // an optional stretch along the line, in blocks: "0-300" lays the first 300 blocks, for a look before the rest
        double from = 0, to = Double.MAX_VALUE;
        String range = null;
        for (int i = 4; i < args.length; i++) {
            if (!args[i].matches("[0-9]+-[0-9]+")) continue;
            range = args[i];
            String[] parts = range.split("-");
            from = Double.parseDouble(parts[0]);
            to = Double.parseDouble(parts[1]);
        }

        List<double[][]> pieces = new ArrayList<>();
        List<double[]> along = new ArrayList<>();
        int used = 0;
        double total = 0, longestPiece = 0, shortestPiece = Double.MAX_VALUE;
        for (Map.Entry<String, List<double[][]>> e : lines.entrySet()) {
            if (!"all".equalsIgnoreCase(args[2]) && !e.getKey().equalsIgnoreCase(args[2])) continue;
            used++;
            for (double[][] line : e.getValue())
                for (double[][] run : RailPaths.split(RailPaths.toWorld(line, in), 1000)) {
                    double[][] wanted = run;
                    if (range != null) {
                        List<double[]> cut = new ArrayList<>();
                        double walked = 0;
                        for (int i = 0; i < run.length; i++) {
                            if (i > 0) walked += RailPaths.distance(run[i - 1], run[i]);
                            if (walked >= from && walked <= to) cut.add(run[i]);
                        }
                        if (cut.size() < 2) continue;
                        wanted = cut.toArray(new double[0][]);
                    }
                    // every block of the line, so clearing reaches the whole of it and not just the piece ends
                    along.addAll(RailPaths.resample(wanted, 1));
                    for (double[][] piece : RailPaths.pieces(wanted, tolerance, longest)) {
                        pieces.add(piece);
                        double length = RailPaths.distance(piece[0], piece[1]);
                        total += length;
                        longestPiece = Math.max(longestPiece, length);
                        shortestPiece = Math.min(shortestPiece, length);

                    }
                }
        }
        if (used == 0) throw new CommandException("No line called " + args[2] + " in " + file.getName() + " (it holds: " + String.join(", ", lines.keySet()) + ")");
        if (pieces.isEmpty()) throw new CommandException("Nothing of that line to lay");

        // the line is tens of kilometres: the clearing goes a few thousand blocks a tick, and the laying waits for it
        if (clear) {
            final List<double[][]> ready = pieces;
            final boolean over2 = over, turnout2 = turnout;
            final String what = args[2] + " of " + file.getName();
            final ItemStack held = blueprint;
            m.clearRails(player, MeshRailLayer.around(along, 3), what,
                clearOnly ? null : who -> m.layRails(who, held, ready, 0, over2, turnout2, 2, what));
            return;
        }
        MeshServer.msg(sender, TextFormatting.GRAY + "Blueprint: " + MeshRailLayer.describe(blueprint));
        MeshServer.msg(sender, TextFormatting.GRAY + String.format("%.0f blocks of line in %d pieces of %.0f to %.0f blocks, never over %.0f cm off the line",
            total, pieces.size(), shortestPiece, longestPiece, tolerance * 100));
        m.layRails(player, blueprint, pieces, 0, over, turnout, 2, args[2] + " of " + file.getName());
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

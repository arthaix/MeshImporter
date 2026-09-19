package ru.arthaix.meshimporter.server;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import net.minecraft.block.state.IBlockState;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumActionResult;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.DimensionManager;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.world.BlockEvent;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import ru.arthaix.meshimporter.MeshImporter;
import ru.arthaix.meshimporter.MeshImporterConfig;
import ru.arthaix.meshimporter.block.BlockMeshAnchor;
import ru.arthaix.meshimporter.block.TileEntityMeshAnchor;
import ru.arthaix.meshimporter.collision.TriangleGrid;
import ru.arthaix.meshimporter.common.ImportSettings;
import ru.arthaix.meshimporter.common.Transform;
import ru.arthaix.meshimporter.instance.LoadedInstance;
import ru.arthaix.meshimporter.instance.MeshInstance;
import ru.arthaix.meshimporter.compat.MeshRailLayer;
import ru.arthaix.meshimporter.compat.RailSupport;
import ru.arthaix.meshimporter.instance.MeshWorld;
import ru.arthaix.meshimporter.model.MeshModel;
import ru.arthaix.meshimporter.model.MeshModelCodec;
import ru.arthaix.meshimporter.network.MsgInstanceRemove;
import ru.arthaix.meshimporter.network.MsgInstances;
import ru.arthaix.meshimporter.network.MsgModelPart;
import ru.arthaix.meshimporter.network.MsgUploadAck;
import ru.arthaix.meshimporter.network.Net;

/**
 * Server side: keeps the registry of placed models, loads them into {@link MeshWorld#SERVER} for collision,
 * receives model uploads, serves model downloads, and applies place/remove requests.
 */
public final class MeshServer {

    public static final MeshServer INSTANCE = new MeshServer();

    private static final class Upload {
        String hash;
        String name;
        int totalBytes, parts, received;
        byte[][] data;
        long started = System.currentTimeMillis();
    }

    private static final class Download {
        String hash;
        byte[] bytes;
        int next, total;
    }

    private MeshRegistry registry;
    /** Ticks since the remembered mesh-carried rails were last checked. */
    private int railTicks;
    /** Track being laid along a drawn line, a few pieces per tick so the server keeps running. */
    private RailJob railJob;
    /** When each player was last told why a click on a model did nothing. */
    private final Map<UUID, Long> told = new HashMap<>();
    private final Map<UUID, Upload> uploads = new HashMap<>();
    private final Map<UUID, List<Download>> downloads = new HashMap<>();
    private ExecutorService io;

    private MeshServer() {}

    public static boolean isAllowed(EntityPlayerMP player) {
        if (player.isCreative()) return true;
        return player.getServer() != null && player.getServer().getPlayerList().canSendCommands(player.getGameProfile());
    }

    private ExecutorService io() {
        if (io == null || io.isShutdown()) io = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "meshimporter-io");
            t.setDaemon(true);
            return t;
        });
        return io;
    }

    private static MinecraftServer server() {
        return FMLCommonHandler.instance().getMinecraftServerInstance();
    }

    public MeshRegistry registry() {
        return registry;
    }

    // ---- lifecycle ----

    @SubscribeEvent
    public void onWorldLoad(WorldEvent.Load event) {
        World world = event.getWorld();
        if (world.isRemote || !(world instanceof WorldServer) || world.provider.getDimension() != 0) return;
        registry = MeshRegistry.get((WorldServer) world);
        RailSupport.SERVER.onChange = () -> {
            MeshRegistry r = registry;
            if (r != null) r.markDirty();
        };
        MeshWorld.SERVER.clear();
        for (MeshInstance in : registry.all()) loadAsync(in.copy(), null);
        MeshImporter.logger.info("MeshImporter: " + registry.all().size() + " placed models in this save");
        requestWebExport();
    }

    public void onServerStopping() {
        uploads.clear();
        downloads.clear();
    }

    public void onServerStopped() {
        MeshWorld.SERVER.clear();
        RailSupport.SERVER.clear();
        RailSupport.SERVER.onChange = null;
        ModelStore.clearCache();
        WebExport.shutdown();
        registry = null;
        if (io != null) io.shutdownNow();
        io = null;
    }

    @SubscribeEvent
    public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.player instanceof EntityPlayerMP) || registry == null) return;
        List<MeshInstance> list = new ArrayList<>();
        for (MeshInstance in : registry.all()) list.add(in.copy());
        Net.CH.sendTo(new MsgInstances(true, list), (EntityPlayerMP) event.player);
    }

    @SubscribeEvent
    public void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        uploads.remove(event.player.getUniqueID());
        downloads.remove(event.player.getUniqueID());
    }

    /** Decodes the model file and builds the collision grid off the server thread, then registers the instance. */
    private void loadAsync(MeshInstance in, Runnable then) {
        io().execute(() -> {
            try {
                MeshModel model = ModelStore.load(in.hash);
                LoadedInstance li = LoadedInstance.load(in, model);
                MinecraftServer s = server();
                if (s == null) return;
                s.addScheduledTask(() -> {
                    if (registry == null) return;
                    MeshWorld.SERVER.add(li);
                    if (then != null) then.run();
                });
            } catch (Exception e) {
                MeshImporter.logger.error("Cannot load model " + in.hash + " of #" + in.id + ": " + e);
            }
        });
    }

    // ---- placing / removing ----

    /** Puts a model into the world at one slot of the anchor; a model already in that slot is replaced. */
    public void place(EntityPlayerMP player, BlockPos anchor, String hash, double[] matrix, String name, NBTTagCompound settings, int slot) {
        if (!isAllowed(player)) {
            chat(player, TextFormatting.RED + "You need creative mode or op to place models.");
            return;
        }
        if (registry == null) return;
        if (!ModelStore.exists(hash)) {
            chat(player, TextFormatting.RED + "The server does not have this model file; upload it first.");
            return;
        }
        WorldServer world = player.getServerWorld();
        int dim = world.provider.getDimension();
        int index = Math.max(0, slot);
        TileEntity anchorTile = world.getTileEntity(anchor);
        TileEntityMeshAnchor te = anchorTile instanceof TileEntityMeshAnchor ? (TileEntityMeshAnchor) anchorTile : null;
        MeshInstance old = null;
        if (te != null) {
            MeshInstance inSlot = registry.find(te.instanceId(index));
            if (inSlot != null && inSlot.dim == dim && inSlot.anchorX == anchor.getX() && inSlot.anchorY == anchor.getY() && inSlot.anchorZ == anchor.getZ()) old = inSlot;
        }
        MeshModel model;
        try {
            model = ModelStore.load(hash);
        } catch (IOException e) {
            chat(player, TextFormatting.RED + "Model file unreadable: " + e.getMessage());
            return;
        }
        MeshInstance in = new MeshInstance();
        in.id = registry.nextId();
        in.dim = dim;
        in.hash = hash;
        in.anchorX = anchor.getX();
        in.anchorY = anchor.getY();
        in.anchorZ = anchor.getZ();
        in.matrix = matrix.clone();
        in.bounds = Transform.bounds(matrix, model.bounds);
        in.name = name;
        in.owner = player.getName();
        in.time = System.currentTimeMillis();
        in.triangles = model.triangleCount;
        registry.add(in);
        if (te != null) {
            te.setInstanceId(index, in.id);
            te.setSettings(index, ImportSettings.read(settings));
        }
        // the old model goes only after the new one is registered, so a model file both share stays referenced
        if (old != null) removeInstance(old, player);
        requestWebExport();
        chat(player, TextFormatting.GREEN + "Placing model #" + in.id + " (" + String.format("%,d", model.triangleCount) + " triangles)...");
        MeshImporter.logger.info("[meshimporter] " + player.getName() + " placed #" + in.id + " " + name + " at " + anchor + " (" + hash + ")");
        loadAsync(in.copy(), () -> {
            Net.CH.sendToAll(new MsgInstances(false, Collections.singletonList(in.copy())));
            chat(player, TextFormatting.GREEN + "Model #" + in.id + " placed. Remove it with the anchor's GUI or /meshimporter remove " + in.id);
        });
    }

    /** Takes the model of one anchor slot out of the world; dropSlot also drops the slot itself. */
    public void removeByAnchor(EntityPlayerMP player, BlockPos anchor, int slot, boolean dropSlot) {
        if (!isAllowed(player)) {
            chat(player, TextFormatting.RED + "You need creative mode or op to remove models.");
            return;
        }
        if (registry == null) return;
        TileEntity anchorTile = player.getServerWorld().getTileEntity(anchor);
        TileEntityMeshAnchor te = anchorTile instanceof TileEntityMeshAnchor ? (TileEntityMeshAnchor) anchorTile : null;
        int index = Math.max(0, slot);
        MeshInstance in = te == null ? null : registry.find(te.instanceId(index));
        if (in != null) removeInstance(in, player);
        if (dropSlot && te != null) te.removeSlot(index);
        if (in == null && !dropSlot) chat(player, TextFormatting.GRAY + "Nothing is placed in this slot.");
        else chat(player, TextFormatting.YELLOW + (in != null ? "Model #" + in.id + " removed." : "Slot removed."));
    }

    public boolean remove(int id, ICommandSender sender) {
        if (registry == null) return false;
        MeshInstance in = registry.find(id);
        if (in == null) {
            msg(sender, TextFormatting.GRAY + "No model #" + id + ".");
            return false;
        }
        removeInstance(in, sender);
        msg(sender, TextFormatting.YELLOW + "Model #" + id + " removed.");
        return true;
    }

    public void onAnchorBroken(World world, BlockPos pos) {
        if (registry == null) return;
        for (MeshInstance in : registry.allByAnchor(world.provider.getDimension(), pos.getX(), pos.getY(), pos.getZ())) {
            removeInstance(in, null);
            MeshImporter.logger.info("[meshimporter] anchor at " + pos + " broken, model #" + in.id + " removed");
        }
    }

    private void removeInstance(MeshInstance in, ICommandSender by) {
        registry.remove(in);
        MeshWorld.SERVER.remove(in.dim, in.id);
        Net.CH.sendToAll(new MsgInstanceRemove(in.dim, in.id));
        WorldServer world = server().getWorld(in.dim);
        if (world != null) {
            TileEntity te = world.getTileEntity(new BlockPos(in.anchorX, in.anchorY, in.anchorZ));
            if (te instanceof TileEntityMeshAnchor) {
                TileEntityMeshAnchor a = (TileEntityMeshAnchor) te;
                int slot = a.slotOf(in.id);
                if (slot >= 0) a.setInstanceId(slot, 0);
            }
        }
        if (registry.referencesTo(in.hash) == 0) ModelStore.delete(in.hash);
        MeshImporter.logger.info("[meshimporter] removed #" + in.id + (by != null ? " by " + by.getName() : ""));
        requestWebExport();
    }

    // ---- web map ----

    private void requestWebExport() {
        if (registry == null || !WebExport.enabled()) return;
        List<MeshInstance> list = new ArrayList<>();
        for (MeshInstance in : registry.all()) list.add(in.copy());
        WebExport.request(list, DimensionManager.getCurrentSaveRootDirectory());
    }

    public void storeWebTexture(EntityPlayerMP player, String key, int tint, byte[] png) {
        if (registry == null || !WebExport.enabled() || !isAllowed(player)) return;
        if (WebExport.storeTexture(DimensionManager.getCurrentSaveRootDirectory(), key, tint, png)) requestWebExport();
    }

    public void storeSettings(EntityPlayerMP player, BlockPos anchor, int slot, NBTTagCompound settings) {
        if (!isAllowed(player)) return;
        TileEntity te = player.getServerWorld().getTileEntity(anchor);
        if (te instanceof TileEntityMeshAnchor) ((TileEntityMeshAnchor) te).setSettings(Math.max(0, slot), ImportSettings.read(settings));
    }

    public void teleport(EntityPlayerMP player, double x, double y, double z) {
        if (!isAllowed(player)) return;
        if (Math.abs(x) > 30_000_000 || Math.abs(z) > 30_000_000 || y < 0 || y > 300) return;
        player.dismountRidingEntity();
        player.connection.setPlayerLocation(x, y, z, player.rotationYaw, player.rotationPitch);
    }

    // ---- placing blocks on a mesh ----

    public void placeOnMesh(EntityPlayerMP player, BlockPos pos, EnumFacing facing, float hitX, float hitY, float hitZ, EnumHand hand) {
        if (!MeshImporterConfig.placeOnMesh) {
            tell(player, "Using items on models is switched off in the config (placeOnMesh)");
            return;
        }
        if (player.isSpectator()) return;
        ItemStack stack = player.getHeldItem(hand);
        if (stack.isEmpty()) return;
        WorldServer world = player.getServerWorld();
        // the client's claim must match a mesh hit along the player's own line of sight
        Vec3d eyes = player.getPositionEyes(1f);
        double reach = player.interactionManager.getBlockReachDistance() + 1;
        Vec3d look = player.getLookVec();
        Vec3d end = eyes.add(look.x * reach, look.y * reach, look.z * reach);
        TriangleGrid.Hit hit = MeshWorld.SERVER.raycast(world.provider.getDimension(), eyes.x, eyes.y, eyes.z, end.x, end.y, end.z);
        if (hit == null) {
            tell(player, "No placed model under your aim (a preview does not count)");
            return;
        }
        BlockPos expected = new BlockPos(hit.x + hit.nx * 0.02, hit.y + hit.ny * 0.02, hit.z + hit.nz * 0.02);
        if (expected.distanceSq(pos) > 4) {
            tell(player, "The model surface moved while you clicked - try again");
            return;
        }
        if (stack.getItem() instanceof ItemBlock && !world.getBlockState(pos).getBlock().isReplaceable(world, pos)) {
            tell(player, "There is already a block at " + pos.getX() + " " + pos.getY() + " " + pos.getZ());
            return;
        }
        EnumActionResult result = player.interactionManager.processRightClickBlock(player, world, stack, hand, pos, facing, hitX, hitY, hitZ);
        // nothing wanted the cell: use the item on its own, so eating and drawing a bow still work in front of a mesh
        if (result != EnumActionResult.SUCCESS && !player.isHandActive())
            result = player.interactionManager.processRightClick(player, world, stack, hand);
        if (result != EnumActionResult.SUCCESS) {
            MeshImporter.logger.info("[meshimporter] " + stack.getItem().getRegistryName() + " did nothing at " + pos + " on a mesh (" + result + ")");
            tell(player, stack.getDisplayName() + " did nothing at " + pos.getX() + " " + pos.getY() + " " + pos.getZ() + " (" + result + ")");
        }
    }

    // ---- uploads ----

    public void beginUpload(EntityPlayerMP player, String hash, int totalBytes, int parts, String name) {
        if (!isAllowed(player)) {
            Net.CH.sendTo(new MsgUploadAck(hash, 0, MsgUploadAck.ERROR, "creative mode or op needed"), player);
            return;
        }
        if (!MeshModelCodec.validHash(hash) || totalBytes <= 0 || parts <= 0 || parts > 1_000_000 || (long) parts * Net.UPLOAD_PART < totalBytes) {
            Net.CH.sendTo(new MsgUploadAck(hash, 0, MsgUploadAck.ERROR, "bad upload header"), player);
            return;
        }
        if (totalBytes > (long) MeshImporterConfig.maxModelMegabytes << 20) {
            Net.CH.sendTo(new MsgUploadAck(hash, 0, MsgUploadAck.ERROR, "model larger than " + MeshImporterConfig.maxModelMegabytes + " MB (maxModelMegabytes)"), player);
            return;
        }
        if (ModelStore.exists(hash)) {
            Net.CH.sendTo(new MsgUploadAck(hash, parts, MsgUploadAck.EXISTS, ""), player);
            return;
        }
        Upload u = new Upload();
        u.hash = hash;
        u.name = name;
        u.totalBytes = totalBytes;
        u.parts = parts;
        u.data = new byte[parts][];
        uploads.put(player.getUniqueID(), u);
        Net.CH.sendTo(new MsgUploadAck(hash, 0, MsgUploadAck.CONTINUE, ""), player);
    }

    public void uploadPart(EntityPlayerMP player, String hash, int part, byte[] data) {
        Upload u = uploads.get(player.getUniqueID());
        if (u == null || !u.hash.equals(hash) || part < 0 || part >= u.parts) return;
        if (u.data[part] == null) u.received++;
        u.data[part] = data;
        if (u.received < u.parts) {
            if (part % 4 == 3 || part == u.parts - 1) Net.CH.sendTo(new MsgUploadAck(hash, u.received, MsgUploadAck.CONTINUE, ""), player);
            return;
        }
        uploads.remove(player.getUniqueID());
        int size = 0;
        for (byte[] d : u.data) size += d.length;
        byte[] all = new byte[size];
        int o = 0;
        for (byte[] d : u.data) {
            System.arraycopy(d, 0, all, o, d.length);
            o += d.length;
        }
        final Upload done = u;
        io().execute(() -> {
            String error = null;
            MeshModel model = null;
            try {
                if (!MeshModelCodec.sha1(all).equals(done.hash)) throw new IOException("checksum mismatch");
                model = MeshModelCodec.decode(all);
                ModelStore.write(done.hash, all);
                ModelStore.cache(done.hash, model);
            } catch (Exception e) {
                error = e.getMessage() == null ? e.toString() : e.getMessage();
            }
            final String err = error;
            MinecraftServer s = server();
            if (s == null) return;
            s.addScheduledTask(() -> {
                if (err != null) Net.CH.sendTo(new MsgUploadAck(done.hash, done.received, MsgUploadAck.ERROR, err), player);
                else Net.CH.sendTo(new MsgUploadAck(done.hash, done.received, MsgUploadAck.DONE, ""), player);
            });
        });
    }

    // ---- downloads ----

    public void requestModel(EntityPlayerMP player, String hash) {
        if (!ModelStore.exists(hash)) return;
        List<Download> list = downloads.computeIfAbsent(player.getUniqueID(), k -> new ArrayList<>());
        for (Download d : list) if (d.hash.equals(hash)) return;
        Download d = new Download();
        d.hash = hash;
        list.add(d);
        io().execute(() -> {
            byte[] bytes;
            try {
                bytes = ModelStore.readBytes(hash);
            } catch (IOException e) {
                MeshImporter.logger.warn("Cannot read model " + hash + " for " + player.getName() + ": " + e);
                return;
            }
            MinecraftServer s = server();
            if (s == null) return;
            s.addScheduledTask(() -> {
                d.bytes = bytes;
                d.total = (bytes.length + Net.DOWNLOAD_PART - 1) / Net.DOWNLOAD_PART;
            });
        });
    }

    /** One line above the hotbar, at most one every two seconds, so a held-down click cannot flood the player. */
    private void tell(EntityPlayerMP player, String text) {
        long now = player.getServerWorld().getTotalWorldTime();
        Long last = told.get(player.getUniqueID());
        if (last != null && now - last < 40) return;
        told.put(player.getUniqueID(), now);
        player.sendStatusMessage(new TextComponentString(TextFormatting.GRAY + "MeshImporter: " + text), true);
    }

    // ---- anchor protection ----

    /** The anchor the owner is removing right now; every other way of losing the block is undone. */
    private BlockPos authorisedPos;
    private int authorisedDim;
    /** Anchors something else took out, waiting to be put back at the end of the tick. */
    private final List<Object[]> anchorRestores = new ArrayList<>();

    public boolean isAuthorisedBreak(World world, BlockPos pos) {
        return authorisedPos != null && authorisedPos.equals(pos) && authorisedDim == world.provider.getDimension();
    }

    /** The player held the attack button for five seconds: the anchor goes, and its models with it. */
    public void breakAnchor(EntityPlayerMP player, BlockPos pos) {
        if (!isAllowed(player)) {
            chat(player, TextFormatting.RED + "You need creative mode or op to remove an anchor.");
            return;
        }
        WorldServer world = player.getServerWorld();
        if (player.getDistanceSq(pos) > 100 || !(world.getBlockState(pos).getBlock() instanceof BlockMeshAnchor)) return;
        authorisedPos = pos;
        authorisedDim = world.provider.getDimension();
        try {
            world.setBlockToAir(pos);
        } finally {
            authorisedPos = null;
        }
        MeshImporter.logger.info("[meshimporter] " + player.getName() + " removed the anchor at " + pos);
    }

    public void restoreAnchor(World world, BlockPos pos, IBlockState state, NBTTagCompound saved) {
        anchorRestores.add(new Object[] { world.provider.getDimension(), pos.toImmutable(), state, saved });
    }

    private void tickAnchorRestores() {
        if (anchorRestores.isEmpty()) return;
        MinecraftServer s = server();
        if (s == null) return;
        List<Object[]> todo = new ArrayList<>(anchorRestores);
        anchorRestores.clear();
        for (Object[] r : todo) {
            WorldServer world = s.getWorld((Integer) r[0]);
            BlockPos pos = (BlockPos) r[1];
            if (world == null) continue;
            if (!world.isBlockLoaded(pos)) {
                anchorRestores.add(r);
                continue;
            }
            if (world.getBlockState(pos).getBlock() instanceof BlockMeshAnchor) continue;
            world.setBlockState(pos, (IBlockState) r[2], 3);
            TileEntity te = world.getTileEntity(pos);
            if (te != null && r[3] != null) {
                te.readFromNBT((NBTTagCompound) r[3]);
                te.markDirty();
                world.notifyBlockUpdate(pos, (IBlockState) r[2], (IBlockState) r[2], 3);
            }
            MeshImporter.logger.info("[meshimporter] the anchor at " + pos + " was taken out by something else and is back, models untouched");
        }
    }

    /** A click never starts breaking an anchor, in any game mode: removal is the five-second hold. */
    @SubscribeEvent
    public void onLeftClickAnchor(PlayerInteractEvent.LeftClickBlock event) {
        if (event.getWorld().getBlockState(event.getPos()).getBlock() instanceof BlockMeshAnchor) event.setCanceled(true);
    }

    @SubscribeEvent
    public void onBreakAnchor(BlockEvent.BreakEvent event) {
        if (event.getState().getBlock() instanceof BlockMeshAnchor && !isAuthorisedBreak(event.getWorld(), event.getPos())) event.setCanceled(true);
    }

    /** One line of track on its way into the world. */
    private static final class RailJob {
        UUID player;
        ItemStack blueprint;
        List<double[][]> pieces;
        double curvosity;
        boolean over;
        int perTick, at, laid, refused, told;
        String name;
    }

    /** Starts laying track along a line; the pieces go in over the following ticks. */
    public void layRails(EntityPlayerMP player, ItemStack blueprint, List<double[][]> pieces, double curvosity, boolean over, int perTick, String name) {
        RailJob job = new RailJob();
        job.player = player.getUniqueID();
        job.blueprint = blueprint.copy();
        job.pieces = pieces;
        job.curvosity = curvosity;
        job.over = over;
        job.perTick = Math.max(1, perTick);
        job.name = name;
        railJob = job;
        chat(player, TextFormatting.GRAY + "Laying " + pieces.size() + " pieces of track along " + name + "...");
    }

    public boolean layingRails() {
        return railJob != null;
    }

    public void stopLayingRails(ICommandSender sender) {
        RailJob job = railJob;
        railJob = null;
        if (job != null) msg(sender, TextFormatting.YELLOW + "Stopped after " + job.laid + " pieces of " + job.name);
    }

    private void tickRails() {
        RailJob job = railJob;
        if (job == null) return;
        MinecraftServer s = server();
        EntityPlayerMP player = s == null ? null : s.getPlayerList().getPlayerByUUID(job.player);
        if (player == null) {
            railJob = null;
            return;
        }
        int[] done = MeshRailLayer.lay(player, job.blueprint, job.pieces, job.curvosity, job.over, job.at, job.perTick);
        job.laid += done[0];
        job.refused += done[1];
        job.at += job.perTick;
        int total = job.pieces.size();
        if (job.at < total) {
            int percent = 100 * job.at / Math.max(1, total);
            if (percent >= job.told + 10) {
                job.told = percent;
                chat(player, TextFormatting.GRAY + job.name + ": " + percent + "%, " + job.laid + " pieces laid");
            }
            return;
        }
        railJob = null;
        chat(player, TextFormatting.GREEN + job.name + " done: " + job.laid + " pieces laid"
            + (job.refused > 0 ? TextFormatting.YELLOW + ", " + job.refused + " refused (something is in the way)" : ""));
        MeshImporter.logger.info("[meshimporter] track along " + job.name + ": " + job.laid + " laid, " + job.refused + " refused");
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        tickAnchorRestores();
        tickRails();
        // every five minutes: the rails a mesh once carried, whose rail is gone by now, are forgotten
        if (++railTicks >= 6000) {
            railTicks = 0;
            MinecraftServer ms = server();
            if (ms != null && RailSupport.SERVER.count() > 0)
                for (WorldServer w : ms.worlds) RailSupport.SERVER.prune(w);
        }
        if (downloads.isEmpty()) return;
        MinecraftServer s = server();
        Iterator<Map.Entry<UUID, List<Download>>> it = downloads.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, List<Download>> e = it.next();
            EntityPlayerMP player = s.getPlayerList().getPlayerByUUID(e.getKey());
            if (player == null) {
                it.remove();
                continue;
            }
            int budget = MeshImporterConfig.downloadPartsPerTick;
            Iterator<Download> dit = e.getValue().iterator();
            while (dit.hasNext() && budget > 0) {
                Download d = dit.next();
                if (d.bytes == null) continue;
                while (budget > 0 && d.next < d.total) {
                    int from = d.next * Net.DOWNLOAD_PART;
                    int len = Math.min(Net.DOWNLOAD_PART, d.bytes.length - from);
                    Net.CH.sendTo(new MsgModelPart(d.hash, d.next, d.total, d.bytes.length, Arrays.copyOfRange(d.bytes, from, from + len)), player);
                    d.next++;
                    budget--;
                }
                if (d.next >= d.total) dit.remove();
            }
            if (e.getValue().isEmpty()) it.remove();
        }
    }

    // ---- listing ----

    public void list(ICommandSender sender) {
        if (registry == null || registry.all().isEmpty()) {
            msg(sender, TextFormatting.GRAY + "No models placed in this save.");
            return;
        }
        msg(sender, TextFormatting.AQUA + "Placed models (/meshimporter remove <id>, /meshimporter tp <id>):");
        for (MeshInstance in : registry.all())
            msg(sender, "#" + in.id + " " + in.name + " by " + in.owner + " at " + in.anchorX + " " + in.anchorY + " " + in.anchorZ + (in.dim != 0 ? " dim " + in.dim : "")
                + ", " + String.format("%,d", in.triangles) + " tris" + (MeshWorld.SERVER.get(in.dim, in.id) == null ? TextFormatting.GRAY + " (loading)" : ""));
    }

    public static void chat(EntityPlayerMP player, String text) {
        player.sendMessage(new TextComponentString(text));
    }

    public static void msg(ICommandSender sender, String text) {
        sender.sendMessage(new TextComponentString(text));
    }
}

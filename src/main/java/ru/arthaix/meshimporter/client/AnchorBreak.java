package ru.arthaix.meshimporter.client;

import net.minecraft.client.Minecraft;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.text.TextFormatting;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import ru.arthaix.meshimporter.block.BlockMeshAnchor;
import ru.arthaix.meshimporter.network.MsgBreakAnchor;
import ru.arthaix.meshimporter.network.Net;

/**
 * An anchor carries whole models, so a stray click must not take it out: it goes only after the attack button has
 * been held on it for five seconds, with the countdown shown above the hotbar. Letting go starts over.
 */
public final class AnchorBreak {

    public static final AnchorBreak INSTANCE = new AnchorBreak();
    private static final int HOLD_TICKS = 100;

    private BlockPos target;
    private int held;

    private AnchorBreak() {}

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getMinecraft();
        BlockPos aimed = null;
        if (mc.player != null && mc.world != null && mc.currentScreen == null && mc.gameSettings.keyBindAttack.isKeyDown()) {
            RayTraceResult over = mc.objectMouseOver;
            if (over != null && over.typeOfHit == RayTraceResult.Type.BLOCK
                && mc.world.getBlockState(over.getBlockPos()).getBlock() instanceof BlockMeshAnchor) aimed = over.getBlockPos();
        }
        if (aimed == null || !aimed.equals(target)) {
            if (target != null && held > 0 && mc.ingameGUI != null) mc.ingameGUI.setOverlayMessage("", false);
            target = aimed;
            held = 0;
            if (aimed == null) return;
        }
        held++;
        if (held >= HOLD_TICKS) {
            Net.CH.sendToServer(new MsgBreakAnchor(target));
            mc.ingameGUI.setOverlayMessage(TextFormatting.RED + "Anchor removed", false);
            target = null;
            held = 0;
            return;
        }
        double left = (HOLD_TICKS - held) / 20.0;
        int bars = 20 * held / HOLD_TICKS;
        StringBuilder bar = new StringBuilder();
        for (int i = 0; i < 20; i++) bar.append(i < bars ? TextFormatting.RED + "|" : TextFormatting.DARK_GRAY + "|");
        mc.ingameGUI.setOverlayMessage(TextFormatting.YELLOW + "Keep holding to remove the anchor and its models  " + bar
            + TextFormatting.YELLOW + String.format("  %.1f s", left), false);
    }
}

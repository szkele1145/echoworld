package dev.worldreplay.nf1211.client;

import dev.worldreplay.nf1211.WorldReplayNeoForge;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import com.mojang.blaze3d.platform.InputConstants;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import org.lwjgl.glfw.GLFW;

/**
 * 客户端专属：按键绑定（仅 P 暂停）+ 回放渲染驱动。
 *
 * <p>Dist.CLIENT 保证专用服务器不加载本类（KeyMapping 是客户端类）。</p>
 *
 * <p><b>v0.1.9</b>：原 O 键开录制库已<b>移除</b>。录制库只能由服务端命令
 * {@code /wr gui} 打开（服务端发 OpenRecordLibrary + RecordList，
 * 客户端按信号开屏，见 {@link ClientPayloadHandlers}）。</p>
 */
@EventBusSubscriber(modid = WorldReplayNeoForge.MOD_ID, value = Dist.CLIENT)
public final class WorldReplayClient {

    /** P 键：暂停/继续回放。 */
    public static final KeyMapping PAUSE_REPLAY = new KeyMapping(
            "key.worldreplay.pause", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_P, "key.categories.worldreplay");

    @SubscribeEvent
    public static void onRegisterKeys(RegisterKeyMappingsEvent event) {
        event.register(PAUSE_REPLAY);
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        while (PAUSE_REPLAY.consumeClick()) {
            if (ReplayVisualizer.isPaused()) {
                ReplayVisualizer.resume();
            } else if (ReplayVisualizer.isReplaying()) {
                ReplayVisualizer.pause();
            }
        }
        ReplayVisualizer.tick();
    }

    /** 回放中滚轮调倍速：GUI 未开时生效（开了 GUI 让给界面滚动）。上=快下=慢，步进 ×1.5/÷1.5。 */
    @SubscribeEvent
    public static void onMouseScroll(InputEvent.MouseScrollingEvent event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen != null) return;                       // GUI 打开时不劫持滚轮
        if (!ReplayVisualizer.isReplaying() && !ReplayVisualizer.isPaused()) return;
        double delta = event.getScrollDeltaY();
        if (delta == 0) return;
        double cur = ReplayVisualizer.getSpeed();
        double next = delta > 0 ? cur * 1.5 : cur / 1.5;
        // 引擎内钳制 [0.25, 64]，这里额外把结果吸附到常用档位避免 0.37 之类的怪数
        double[] steps = {0.25, 0.5, 1, 2, 4, 8, 16, 32, 64};
        double best = steps[0];
        double bestDiff = Double.MAX_VALUE;
        for (double s : steps) {
            double d = Math.abs(s - next);
            if (d < bestDiff) { bestDiff = d; best = s; }
        }
        ReplayVisualizer.setSpeed(best);
        if (mc.player != null) {
            mc.player.displayClientMessage(
                    net.minecraft.network.chat.Component.literal("§e[WorldReplay] " + best + "x"), true);
        }
        event.setCanceled(true);
    }

    private WorldReplayClient() {}
}

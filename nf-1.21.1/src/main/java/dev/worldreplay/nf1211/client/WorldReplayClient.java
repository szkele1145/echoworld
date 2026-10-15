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
import org.lwjgl.glfw.GLFW;

/**
 * 客户端专属：按键绑定（O）+ 录制管理屏入口。
 *
 * <p>Dist.CLIENT 保证专用服务器不加载本类（KeyMapping 是客户端类）。</p>
 */
@EventBusSubscriber(modid = WorldReplayNeoForge.MOD_ID, value = Dist.CLIENT)
public final class WorldReplayClient {

    /** O 键：打开录制管理屏。 */
    public static final KeyMapping OPEN_GUI = new KeyMapping(
            "key.worldreplay.open", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_O, "key.categories.worldreplay");

    /** P 键：暂停/继续回放。 */
    public static final KeyMapping PAUSE_REPLAY = new KeyMapping(
            "key.worldreplay.pause", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_P, "key.categories.worldreplay");

    @SubscribeEvent
    public static void onRegisterKeys(RegisterKeyMappingsEvent event) {
        event.register(OPEN_GUI);
        event.register(PAUSE_REPLAY);
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        while (OPEN_GUI.consumeClick()) {
            // Bug 1 修复：文件在服务端目录——改为发 C2S 请求列表包，
            // 服务端回 RecordList 后由 ClientPayloadHandlers 开屏（见该类）
            net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                    new dev.worldreplay.nf1211.RecordListPackets.RequestRecordList());
        }
        while (PAUSE_REPLAY.consumeClick()) {
            if (ReplayVisualizer.isPaused()) {
                ReplayVisualizer.resume();
            } else if (ReplayVisualizer.isReplaying()) {
                ReplayVisualizer.pause();
            }
        }
        ReplayVisualizer.tick();
    }

    private WorldReplayClient() {}
}

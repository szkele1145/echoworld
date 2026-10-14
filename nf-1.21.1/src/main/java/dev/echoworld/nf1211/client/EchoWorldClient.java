package dev.echoworld.nf1211.client;

import dev.echoworld.nf1211.EchoWorldNeoForge;
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
@EventBusSubscriber(modid = EchoWorldNeoForge.MOD_ID, value = Dist.CLIENT)
public final class EchoWorldClient {

    /** O 键：打开录制管理屏。 */
    public static final KeyMapping OPEN_GUI = new KeyMapping(
            "key.echoworld.open", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_O, "category.echoworld");

    @SubscribeEvent
    public static void onRegisterKeys(RegisterKeyMappingsEvent event) {
        event.register(OPEN_GUI);
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        while (OPEN_GUI.consumeClick()) {
            mc.setScreen(new RecordingScreen());
        }
    }

    private EchoWorldClient() {}
}

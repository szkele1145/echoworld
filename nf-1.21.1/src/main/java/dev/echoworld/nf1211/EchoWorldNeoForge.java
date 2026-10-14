package dev.echoworld.nf1211;

import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import org.slf4j.Logger;

/**
 * EchoWorld NeoForge 1.21.1 主入口。
 *
 * <p>装载录制器（行为捕获→落盘）与 /echoreplay 命令。</p>
 */
@Mod(EchoWorldNeoForge.MOD_ID)
@net.neoforged.fml.common.EventBusSubscriber(modid = EchoWorldNeoForge.MOD_ID)
public class EchoWorldNeoForge {

    public static final String MOD_ID = "echoworld";
    private static final Logger LOGGER = LogUtils.getLogger();

    public EchoWorldNeoForge() {
        LOGGER.info("[EchoWorld] NeoForge 1.21.1 loaded");
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        EchoWorldCommands.register(event.getDispatcher());
        LOGGER.info("[EchoWorld] /echoreplay registered");
    }
}

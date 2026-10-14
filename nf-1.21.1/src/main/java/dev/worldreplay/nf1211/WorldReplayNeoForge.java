package dev.worldreplay.nf1211;

import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import org.slf4j.Logger;

/**
 * WorldReplay NeoForge 1.21.1 主入口。
 *
 * <p>装载录制器（行为捕获→落盘）与 /echoreplay 命令。
 * 客户端 GUI（O 键）在 {@link dev.worldreplay.nf1211.client.WorldReplayClient}（Dist.CLIENT 隔离）。</p>
 */
@Mod(WorldReplayNeoForge.MOD_ID)
public class WorldReplayNeoForge {

    public static final String MOD_ID = "worldreplay";
    private static final Logger LOGGER = LogUtils.getLogger();

    public WorldReplayNeoForge() {
        LOGGER.info("[WorldReplay] NeoForge 1.21.1 loaded");
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        WorldReplayCommands.register(event.getDispatcher());
        LOGGER.info("[WorldReplay] /echoreplay registered");
    }
}

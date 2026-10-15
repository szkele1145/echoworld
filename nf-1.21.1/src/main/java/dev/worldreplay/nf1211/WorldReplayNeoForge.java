package dev.worldreplay.nf1211;

import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModLoadingContext;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent.Loading;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import org.slf4j.Logger;

/**
 * WorldReplay NeoForge 1.21.1 主入口。
 *
 * <p>装载录制器（行为捕获→落盘）、/echoreplay 命令与服务端配置。
 * 客户端 GUI（P 键暂停）在 {@link dev.worldreplay.nf1211.client.WorldReplayClient}（Dist.CLIENT 隔离）。</p>
 */
@Mod(WorldReplayNeoForge.MOD_ID)
public class WorldReplayNeoForge {

    public static final String MOD_ID = "worldreplay";
    private static final Logger LOGGER = LogUtils.getLogger();

    public WorldReplayNeoForge() {
        // 注册服务端配置（写入 config/worldreplay.toml，首次运行生成默认值）
        ModLoadingContext.get()
                .getActiveContainer()
                .registerConfig(ModConfig.Type.SERVER, WorldReplayConfig.SPEC, "worldreplay.toml");
        // 加载时把默认值持久化到 toml
        ModLoadingContext.get().getActiveContainer().getEventBus().addListener(this::onConfigLoading);
        LOGGER.info("[WorldReplay] NeoForge 1.21.1 loaded");
    }

    private void onConfigLoading(Loading event) {
        WorldReplayConfig.SPEC.acceptConfig(event.getConfig().getLoadedConfig());
        LOGGER.info("[WorldReplay] server config registered");
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        WorldReplayCommands.register(event.getDispatcher());
        LOGGER.info("[WorldReplay] /echoreplay registered");
    }
}

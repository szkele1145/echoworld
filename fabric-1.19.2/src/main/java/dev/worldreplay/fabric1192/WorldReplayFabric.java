package dev.worldreplay.fabric1192;

import dev.worldreplay.core.event.Event;
import dev.worldreplay.core.event.EventDecoder;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;

import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * WorldReplay Fabric 1.19.4 主入口（ModInitializer）。
 *
 * <p>初始化顺序：
 * <ol>
 *   <li>{@link WorldReplayConfig#load()} —— 读取 config/worldreplay.toml</li>
 *   <li>{@link WorldReplayNetwork#register()} —— 注册自定义网络包通道</li>
 *   <li>{@link WorldReplayRecorder#init()} —— 订阅登录/退出/方块事件，捕获行为</li>
 *   <li>{@link WorldReplayServerTick#init()} —— 订阅服务端 tick，执行定时清理</li>
 *   <li>命令 {@code /wr} —— 录制库 GUI 与单文件回放入口</li>
 * </ol></p>
 */
public class WorldReplayFabric implements ModInitializer {

    public static final String MOD_ID = "worldreplay";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        WorldReplayConfig.load();
        WorldReplayNetwork.register();
        WorldReplayRecorder.init();
        WorldReplayServerTick.init();
        // 登录/退出会话已由 WorldReplayRecorder.init() 内部订阅

        // /wr 命令（服务端命令树，Fabric 1.19.4 新命令 API）
        CommandRegistrationCallback.EVENT.register((dispatcher, dedicated, environment) -> {
            WorldReplayCommands.register(dispatcher);
        });

        LOGGER.info("[WorldReplay] Fabric 1.19.4 initialized (mod id={})", MOD_ID);
    }

    /** 构造世界资源标识 {@code worldreplay:<path>}。 */
    static net.minecraft.util.Identifier id(String path) {
        return new net.minecraft.util.Identifier(MOD_ID, path);
    }
}

package dev.worldreplay.forge1201;

import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.common.MinecraftForge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * WorldReplay Forge 1.20.1 主入口。
 *
 * <p>初始化顺序：
 * <ol>
 *   <li>{@link WorldReplayConfig#load()} —— 读取 config/worldreplay.toml</li>
 *   <li>{@link WorldReplayNetwork#register()} —— 注册自定义网络包通道</li>
 *   <li>把 {@link WorldReplayRecorder} 挂到 MinecraftForge 事件总线
 *       （登录/退出/方块破坏/方块放置）</li>
 *   <li>命令 {@code /wr} —— 录制库 GUI 与单文件回放入口</li>
 * </ol></p>
 */
@Mod(WorldReplayForge.MOD_ID)
public class WorldReplayForge {

    public static final String MOD_ID = "worldreplay";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public WorldReplayForge() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        modBus.addListener(this::commonSetup);
        MinecraftForge.EVENT_BUS.register(WorldReplayRecorder.class);
        MinecraftForge.EVENT_BUS.addListener(this::registerCommands);
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            WorldReplayConfig.load();
            WorldReplayNetwork.register();
        });
        LOGGER.info("[WorldReplay] Forge 1.20.1 initialized (mod id={})", MOD_ID);
    }

    private void registerCommands(final RegisterCommandsEvent event) {
        WorldReplayCommands.register(event.getDispatcher());
    }
}

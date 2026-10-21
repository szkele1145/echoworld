package dev.worldreplay.fabric1218.client;

import dev.worldreplay.fabric1218.WorldReplayPayloads.PayloadOpenRecordLibrary;
import dev.worldreplay.fabric1218.WorldReplayPayloads.PayloadRecordList;
import dev.worldreplay.fabric1218.WorldReplayPayloads.PayloadRecordingData;
import net.fabricmc.api.ClientModInitializer;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * WorldReplay Fabric 1.21.8 客户端入口。
 *
 * <p>注册所有客户端网络包接收器（记录列表、录制数据、打开录制库信号），
 * 供 Fabric API 在玩家加入世界后分发服务端→客户端包体。</p>
 *
 * <p>1.21.x 适配：{@code registerGlobalReceiver(Identifier, ...)} 已移除，
 * 改为 {@code registerReceiver(CustomPayload.Id, handler)}；
 * {@code Identifier} 构造器私有化，改用 {@link Identifier#of}。</p>
 */
public class WorldReplayFabricClient implements ClientModInitializer {

    public static final String MOD_ID = "worldreplay";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitializeClient() {
        ClientPayloadHandlers.register();

        LOGGER.info("[WorldReplay] Fabric client 1.21.8 initialized (mod id={})", MOD_ID);
    }

    static Identifier id(String path) {
        return Identifier.of(MOD_ID, path);
    }
}

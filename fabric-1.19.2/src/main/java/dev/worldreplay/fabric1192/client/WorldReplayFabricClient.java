package dev.worldreplay.fabric1192.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.network.PacketByteBuf;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * WorldReplay Fabric 1.20.1 客户端入口。
 *
 * <p>注册所有客户端网络包接收器（记录列表、录制数据、打开录制库信号），
 * 供 Fabric API 在玩家加入世界后分发服务端→客户端包体。</p>
 */
public class WorldReplayFabricClient implements ClientModInitializer {

    public static final String MOD_ID = "worldreplay";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitializeClient() {
        ClientPlayNetworking.registerGlobalReceiver(Channels.CHANNEL_RECORD_LIST, ClientPayloadHandlers::handleRecordList);
        ClientPlayNetworking.registerGlobalReceiver(Channels.CHANNEL_RECORDING_DATA, ClientPayloadHandlers::handleRecordingData);
        ClientPlayNetworking.registerGlobalReceiver(Channels.CHANNEL_OPEN_RECORD_LIBRARY, ClientPayloadHandlers::handleOpenRecordLibrary);

        LOGGER.info("[WorldReplay] Fabric client 1.19.4 initialized (mod id={})", MOD_ID);
    }

    /** 客户端网络通道常量（与服务端 WorldReplayNetwork 同名同值）。 */
    public static final class Channels {
        public static final Identifier CHANNEL_RECORD_LIST = new Identifier(MOD_ID, "record_list");
        public static final Identifier CHANNEL_RECORDING_DATA = new Identifier(MOD_ID, "recording_data");
        public static final Identifier CHANNEL_OPEN_RECORD_LIBRARY = new Identifier(MOD_ID, "open_record_library");
        public static final Identifier CHANNEL_REQUEST_RECORD_LIST = new Identifier(MOD_ID, "request_record_list");
        public static final Identifier CHANNEL_REQUEST_RECORDING = new Identifier(MOD_ID, "request_recording");
        public static final Identifier CHANNEL_REQUEST_RENAME = new Identifier(MOD_ID, "request_rename");
        private Channels() {}
    }

    static Identifier id(String path) {
        return new Identifier(MOD_ID, path);
    }
}

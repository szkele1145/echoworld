package dev.worldreplay.nf1211;

import dev.worldreplay.core.event.RecordingSession;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 录制控制 C2S 包：GUI 的 Start/Stop 按钮把目标玩家+动作发到服务端。
 *
 * <p>start=true 开始录制指定玩家（新增/更新 SESSIONS），false 停止并落盘。
 * players 为空 + start=true = 全体在线玩家（Select All 语义）。</p>
 */
public final class RecordControlPackets {
    private RecordControlPackets() {}

    public static final ResourceLocation RECORD_CONTROL_ID =
            ResourceLocation.fromNamespaceAndPath(WorldReplayNeoForge.MOD_ID, "record_control");

    public record RecordControl(boolean start, List<UUID> players) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<RecordControl> TYPE =
                new CustomPacketPayload.Type<>(RECORD_CONTROL_ID);
        public static final StreamCodec<FriendlyByteBuf, RecordControl> CODEC = new StreamCodec<>() {
            @Override
            public RecordControl decode(FriendlyByteBuf buf) {
                boolean start = buf.readBoolean();
                int n = buf.readVarInt();
                List<UUID> list = new ArrayList<>(n);
                for (int i = 0; i < n; i++) list.add(buf.readUUID());
                return new RecordControl(start, list);
            }

            @Override
            public void encode(FriendlyByteBuf buf, RecordControl p) {
                buf.writeBoolean(p.start());
                buf.writeVarInt(p.players().size());
                for (UUID id : p.players()) buf.writeUUID(id);
            }
        };
        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
}

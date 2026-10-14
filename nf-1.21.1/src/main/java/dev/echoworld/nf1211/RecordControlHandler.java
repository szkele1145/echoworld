package dev.echoworld.nf1211;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 录制控制包处理：C2S 开/停录制指定玩家（GUI Start/Stop 按钮的服务端侧）。
 *
 * <p>注册为 optional——没装 EchoWorld 的客户端不受影响。
 * players 为空 + start=true = 全体在线玩家（Select All）。</p>
 */
@EventBusSubscriber(modid = EchoWorldNeoForge.MOD_ID)
public final class RecordControlHandler {

    @SubscribeEvent
    public static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1").optional();
        registrar.playToServer(RecordControlPackets.RecordControl.TYPE,
                RecordControlPackets.RecordControl.CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> {
                    if (ctx.player() instanceof ServerPlayer sp) handle(payload, sp);
                }));
    }

    private static void handle(RecordControlPackets.RecordControl payload, ServerPlayer sender) {
        if (sender == null || !sender.hasPermissions(2)) return;   // OP 限定
        MinecraftServer server = sender.server;
        List<UUID> targets = new ArrayList<>(payload.players());
        if (targets.isEmpty() && payload.start()) {
            for (ServerPlayer sp : server.getPlayerList().getPlayers()) targets.add(sp.getUUID());
        }
        for (UUID id : targets) {
            if (payload.start()) {
                EchoWorldRecorder.startFor(server, id);
            } else {
                EchoWorldRecorder.stopFor(server, id);
            }
        }
    }

    private RecordControlHandler() {}
}

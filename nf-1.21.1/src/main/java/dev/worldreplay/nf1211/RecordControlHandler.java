package dev.worldreplay.nf1211;

import dev.worldreplay.nf1211.client.ClientPayloadHandlers;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 录制控制包处理：C2S 开/停录制指定玩家（GUI Start/Stop 按钮的服务端侧），
 * 以及列表 / 回放 / 删除请求（Bug 1 修复：录制文件在服务端，GUI 走网络分发）。
 *
 * <p>注册为 optional——没装 WorldReplay 的客户端不受影响。
 * players 为空 + start=true = 全体在线玩家（Select All）。</p>
 *
 * <p>S2C 通道的 handler 以方法引用指向 {@link ClientPayloadHandlers}——
 * 该类只会在客户端收到包时初始化，其客户端专属引用（Minecraft 等）
 * 不会在专用服务器上触发类初始化，安全。</p>
 */
@EventBusSubscriber(modid = WorldReplayNeoForge.MOD_ID)
public final class RecordControlHandler {

    @SubscribeEvent
    public static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1").optional();
        registrar.playToServer(RecordControlPackets.RecordControl.TYPE,
                RecordControlPackets.RecordControl.CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> {
                    if (ctx.player() instanceof ServerPlayer sp) handle(payload, sp);
                }));
        // S2C：服务端录制文件列表（Bug 1）
        registrar.playToClient(RecordListPackets.RecordList.TYPE,
                RecordListPackets.RecordList.CODEC,
                (payload, ctx) -> ClientPayloadHandlers.handleRecordList(payload));
        // S2C：.wrp 文件数据分片（回放请求的响应，Bug 1）
        registrar.playToClient(RecordListPackets.RecordingData.TYPE,
                RecordListPackets.RecordingData.CODEC,
                (payload, ctx) -> ClientPayloadHandlers.handleRecordingData(payload));
        // C2S：请求文件列表（O 键 / 命令打开 GUI 前，Bug 1）
        registrar.playToServer(RecordListPackets.RequestRecordList.TYPE,
                RecordListPackets.RequestRecordList.CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> {
                    if (ctx.player() instanceof ServerPlayer sp) handleListRequest(sp);
                }));
        // C2S：请求回放 / 删除某录制文件（Bug 1）
        registrar.playToServer(RecordListPackets.RequestRecording.TYPE,
                RecordListPackets.RequestRecording.CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> {
                    if (ctx.player() instanceof ServerPlayer sp) handleRecordingRequest(payload, sp);
                }));
    }

    // ---- 列表 / 文件请求（Bug 1）----

    /** 服务端录制目录。 */
    public static Path recordingsDir(MinecraftServer server) {
        return server.getServerDirectory().resolve("worldreplay").resolve("recordings");
    }

    /** 扫描服务端 recordings 目录（.wrp，按修改时间倒序）。 */
    public static List<RecordListPackets.RecordListEntry> scanRecordings(MinecraftServer server) {
        List<RecordListPackets.RecordListEntry> entries = new ArrayList<>();
        Path dir = recordingsDir(server);
        if (!Files.isDirectory(dir)) return entries;
        try (var s = Files.list(dir)) {
            for (Path f : (Iterable<Path>) s.filter(p -> {
                String n = p.getFileName().toString();
                return n.endsWith(".wrp") && Files.isRegularFile(p);
            })::iterator) {
                try {
                    entries.add(new RecordListPackets.RecordListEntry(
                            f.getFileName().toString(),
                            Files.size(f),
                            Files.getLastModifiedTime(f).toMillis()));
                } catch (IOException ignored) {}
            }
        } catch (Exception ignored) {}
        entries.sort((a, b) -> Long.compare(b.modified(), a.modified()));
        return entries;
    }

    /** 把列表发给玩家（/worldreplay gui 分支与 C2S 请求共用）。 */
    public static void sendRecordList(ServerPlayer player) {
        PacketDistributor.sendToPlayer(player,
                new RecordListPackets.RecordList(scanRecordings(player.server)));
    }

    /** C2S RequestRecordList：权限校验后回发列表。 */
    private static void handleListRequest(ServerPlayer sender) {
        if (!sender.hasPermissions(2)) return;   // OP 限定（与 GUI 控制一致）
        sendRecordList(sender);
    }

    /** C2S RequestRecording：replay=分片回传文件数据，delete=删文件后重发列表。 */
    private static void handleRecordingRequest(RecordListPackets.RequestRecording payload, ServerPlayer sender) {
        if (!sender.hasPermissions(2)) return;   // OP 限定
        String fileName = payload.fileName();
        // 防目录穿越（与 /echoreplay 校验一致）
        if (fileName.contains("/") || fileName.contains("\\") || fileName.contains("..")) return;
        Path f = recordingsDir(sender.server).resolve(fileName);
        if (!Files.isRegularFile(f)) {
            sendRecordList(sender);   // 文件已不存在：刷新列表让 GUI 同步
            return;
        }
        if (payload.action() == RecordListPackets.RequestRecording.ACTION_DELETE) {
            try {
                Files.deleteIfExists(f);
            } catch (Exception ignored) {}
            sendRecordList(sender);   // 删除后刷新
            return;
        }
        // ACTION_REPLAY：读文件、按 MAX_CHUNK 分片顺序下发（TCP 保序）
        try {
            byte[] data = Files.readAllBytes(f);
            if (data.length == 0) return;
            for (int off = 0; off < data.length; ) {
                int len = Math.min(RecordListPackets.MAX_CHUNK, data.length - off);
                boolean last = off + len >= data.length;
                PacketDistributor.sendToPlayer(sender, new RecordListPackets.RecordingData(
                        fileName, last, java.util.Arrays.copyOfRange(data, off, off + len)));
                off += len;
            }
        } catch (Exception ignored) {}
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
                WorldReplayRecorder.startFor(server, id);
            } else {
                WorldReplayRecorder.stopFor(server, id);
            }
        }
    }

    private RecordControlHandler() {}
}

package dev.worldreplay.fabric1201;

import dev.worldreplay.core.event.Event;
import dev.worldreplay.core.event.RecordingSession;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;

import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.block.BlockState;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;

import net.minecraft.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WorldReplay Fabric 1.20.1 行为捕获（服务端平台层）。
 * 把平台事件翻译为 core 统一 Event 格式，写入 {@link RecordingSession}。
 *
 * <p>每个在线玩家一个录制会话（登录开始，退出停止并落盘）。
 * 走原版方块/实体系统的事件——第三方 mod（MTR 等）的自定义方块自动被捕获。</p>
 */
public final class WorldReplayRecorder {

    private static final Logger LOGGER = LoggerFactory.getLogger(WorldReplayRecorder.class);
    /** 玩家 UUID -> 录制会话（登录开始，退出停止）。 */
    private static final Map<UUID, RecordingSession> SESSIONS = new ConcurrentHashMap<>();

    private WorldReplayRecorder() {}

    /** 初始化事件订阅（由 WorldReplayFabric 在 onInitialize 中调用）。 */
    public static void init() {
        // 登录：开始录制会话
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            startFor(handler.player);
            LOGGER.info("[WorldReplay] Recording started for {}", handler.player.getName().getString());
        });

        // 退出：停止并落盘录制
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            stopFor(handler.player);
            LOGGER.info("[WorldReplay] Recording stopped for {}", handler.player.getName().getString());
        });

        // 方块破坏
        PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, blockEntity) -> {
            if (!(player instanceof ServerPlayerEntity sp)) return;
            if (!world.isClient) {
                captureAt(sp, "block_break",
                        state.getBlock().getTranslationKey(),
                        pos.getX(), pos.getY(), pos.getZ());
            }
        });

        // 方块放置：1.20.1 无对应 Fabric 事件，放置行为通过右键事件近似捕获（见下）

        // 右键方块（主手一次）
        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (world.isClient) return ActionResult.PASS;
            if (!(player instanceof ServerPlayerEntity sp)) return ActionResult.PASS;
            if (hand != Hand.MAIN_HAND) return ActionResult.PASS;
            BlockPos pos = hitResult.getBlockPos();
            BlockState state = world.getBlockState(pos);
            captureAt(sp, "block_use",
                    state.getBlock().getTranslationKey(),
                    pos.getX(), pos.getY(), pos.getZ());
            return ActionResult.PASS;
        });
    }

    /** 获取某玩家的录制会话（null = 未在录制）。 */
    public static RecordingSession getSession(UUID playerId) {
        return SESSIONS.get(playerId);
    }

    /** 服务端控制：开始录制某玩家（在线时才有效）。 */
    public static void startFor(MinecraftServer server, UUID id) {
        if (SESSIONS.containsKey(id)) return;
        ServerPlayerEntity sp = server.getPlayerManager().getPlayer(id);
        if (sp == null) return;
        RecordingSession session = RecordingSession.start(sp.getWorld().getTime(), id.toString());
        SESSIONS.put(id, session);
        LOGGER.info("[WorldReplay] Manual recording started for {}", sp.getName().getString());
    }

    /** 服务端控制：停止并落盘某玩家。 */
    public static void stopFor(MinecraftServer server, UUID id) {
        RecordingSession session = SESSIONS.remove(id);
        if (session == null) return;
        ServerPlayerEntity sp = server.getPlayerManager().getPlayer(id);
        String name = sp != null ? sp.getName().getString() : id.toString();
        session.stopRecord(sp != null ? sp.getWorld().getTime() : server.getOverworld().getTime());
        Path f = save(session, id);
        if (f != null) {
            LOGGER.info("[WorldReplay] Manual recording saved {} ({} events)",
                    f.getFileName(), session.eventCount());
        }
    }

    /** 登录时开始（供 WorldReplayFabric 调用，传入 player）。 */
    static void startFor(ServerPlayerEntity player) {
        MinecraftServer server = player.getServer();
        if (server == null) return;
        UUID id = player.getUuid();
        if (SESSIONS.containsKey(id)) return;
        RecordingSession session = RecordingSession.start(player.getWorld().getTime(), id.toString());
        SESSIONS.put(id, session);
    }

    /** 退出时停止并落盘（供 WorldReplayFabric 调用，传入 player）。 */
    static void stopFor(ServerPlayerEntity player) {
        MinecraftServer server = player.getServer();
        if (server == null) return;
        UUID id = player.getUuid();
        RecordingSession session = SESSIONS.remove(id);
        if (session == null) return;
        session.stopRecord(player.getWorld().getTime());
        Path f = save(session, id);
        if (f != null) {
            LOGGER.info("[WorldReplay] Recording saved {} ({} events)",
                    f.getFileName(), session.eventCount());
        }
    }

    /** 导出会话并落盘，返回写入的文件路径；失败返回 {@code null}。 */
    private static Path save(RecordingSession session, UUID id) {
        try {
            byte[] data = session.export();
            java.nio.file.Files.createDirectories(RecordControlHandler.recordingsDir());
            Path f = RecordControlHandler.recordingsDir().resolve(id + "-" + session.startTick() + ".wrp");
            java.nio.file.Files.write(f, data);
            return f;
        } catch (Exception e) {
            LOGGER.error("[WorldReplay] Failed to save recording for {}", id, e);
            return null;
        }
    }

    /** 带显式坐标的事件捕获（方块事件用方块位置，非玩家位置）。 */
    private static void captureAt(ServerPlayerEntity sp, String type, String data, double x, double y, double z) {
        RecordingSession session = SESSIONS.get(sp.getUuid());
        if (session == null) return;
        Event e = new Event(
                sp.getWorld().getTime(),
                type,
                sp.getUuid().toString(),
                sp.getWorld().getRegistryKey().getValue().toString(),
                x, y, z,
                data);
        session.append(e);
    }

    /**
     * 捕获方块放置（由 {@code BlockItemPlaceMixin} 调用）。
     *
     * <p>Fabric API 没有放置事件，只能用 Mixin 钩 {@code BlockItem.place}
     * 的返回值——只有真的放下去（返回 true）才记录，因此不会出现
     * "对着箱子右键被记成放置"这类误报。</p>
     *
     * <p>public 是因为 Mixin 类在 {@code mixin} 子包下，无法访问包私有成员。</p>
     */
    public static void capturePlace(ServerPlayerEntity sp, net.minecraft.block.BlockState placed,
                                    double x, double y, double z) {
        RecordingSession session = SESSIONS.get(sp.getUuid());
        if (session == null) return;
        Event e = new Event(
                sp.getWorld().getTime(),
                "block_place",
                sp.getUuid().toString(),
                sp.getWorld().getRegistryKey().getValue().toString(),
                x, y, z,
                placed.getBlock().getTranslationKey());
        session.append(e);
    }
}

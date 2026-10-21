package dev.worldreplay.forge1194;

import dev.worldreplay.core.event.Event;
import dev.worldreplay.core.event.RecordingSession;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WorldReplay Forge 1.19.4 行为捕获（服务端平台层）。
 * 把平台事件翻译为 core 统一 Event 格式，写入 {@link RecordingSession}。
 *
 * <p>每个在线玩家一个录制会话（登录开始，退出停止并落盘）。
 * 走原版方块/实体系统的事件——第三方 mod（MTR 等）的自定义方块自动被捕获。</p>
 *
 * <p>方块放置用 Forge 的 {@code BlockEvent.EntityPlaceEvent} 直接能拿到，
 * 不需要像 Fabric 那样用 Mixin 钩 {@code BlockItem.postPlacement}。</p>
 */
public final class WorldReplayRecorder {

    private static final Logger LOGGER = LoggerFactory.getLogger(WorldReplayRecorder.class);
    /** 玩家 UUID -> 录制会话（登录开始，退出停止）。 */
    private static final Map<UUID, RecordingSession> SESSIONS = new ConcurrentHashMap<>();

    private WorldReplayRecorder() {}

    /** 登录：开始录制会话。 */
    @SubscribeEvent
    public static void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp) {
            startFor(sp);
            LOGGER.info("[WorldReplay] Recording started for {}", sp.getGameProfile().getName());
        }
    }

    /** 退出：停止并落盘录制。 */
    @SubscribeEvent
    public static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp) {
            stopFor(sp);
            LOGGER.info("[WorldReplay] Recording stopped for {}", sp.getGameProfile().getName());
        }
    }

    /** 方块破坏。 */
    @SubscribeEvent
    public static void onBlockBreak(BlockEvent.BreakEvent event) {
        if (!(event.getPlayer() instanceof ServerPlayer sp)) return;
        if (event.getLevel().isClientSide()) return;
        BlockPos pos = event.getPos();
        BlockState state = event.getState();
        captureAt(sp, "block_break",
                state.getBlock().getDescriptionId(),
                pos.getX(), pos.getY(), pos.getZ());
    }

    /** 方块放置（Forge 直接提供事件，无需 Mixin）。 */
    @SubscribeEvent
    public static void onBlockPlace(BlockEvent.EntityPlaceEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer sp)) return;
        if (event.getLevel().isClientSide()) return;
        BlockPos pos = event.getPos();
        BlockState placed = event.getPlacedBlock();
        captureAt(sp, "block_place",
                placed.getBlock().getDescriptionId(),
                pos.getX(), pos.getY(), pos.getZ());
    }

    /** 服务端 tick：用于兜底的定时清理（当前仅保留钩子）。 */
    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        // 录制会话的落盘在玩家退出时进行，这里不做周期任务，
        // 避免长连接玩家的录制无限增长。
    }

    /** 获取某玩家的录制会话（null = 未在录制）。 */
    public static RecordingSession getSession(UUID playerId) {
        return SESSIONS.get(playerId);
    }

    /** 服务端控制：开始录制某玩家（在线时才有效）。 */
    public static void startFor(MinecraftServer server, UUID id) {
        if (SESSIONS.containsKey(id)) return;
        ServerPlayer sp = server.getPlayerList().getPlayer(id);
        if (sp == null) return;
        RecordingSession session = RecordingSession.start(sp.getLevel().getGameTime(), id.toString());
        SESSIONS.put(id, session);
        LOGGER.info("[WorldReplay] Manual recording started for {}", sp.getGameProfile().getName());
    }

    /** 服务端控制：停止并落盘某玩家。 */
    public static void stopFor(MinecraftServer server, UUID id) {
        RecordingSession session = SESSIONS.remove(id);
        if (session == null) return;
        ServerPlayer sp = server.getPlayerList().getPlayer(id);
        String name = sp != null ? sp.getGameProfile().getName() : id.toString();
        // 兜底：玩家已离线时 level() 可能返回 null，
        // 这行抛异常会打断整个 stopRecord + save 流程——录制直接丢失。
        long endTick;
        try {
            endTick = (sp != null && sp.getLevel() != null)
                    ? sp.getLevel().getGameTime()
                    : server.overworld().getGameTime();
        } catch (Exception e) {
            endTick = server.overworld().getGameTime();
        }
        session.stopRecord(endTick);
        Path f = save(session, id);
        if (f != null) {
            LOGGER.info("[WorldReplay] Manual recording saved {} ({} events)",
                    f.getFileName(), session.eventCount());
        }
    }

    /** 登录时开始（供事件回调调用）。 */
    static void startFor(ServerPlayer player) {
        MinecraftServer server = player.server;
        if (server == null) return;
        UUID id = player.getUUID();
        if (SESSIONS.containsKey(id)) return;
        RecordingSession session = RecordingSession.start(player.getLevel().getGameTime(), id.toString());
        SESSIONS.put(id, session);
    }

    /** 退出时停止并落盘（供事件回调调用）。 */
    static void stopFor(ServerPlayer player) {
        MinecraftServer server = player.server;
        if (server == null) return;
        UUID id = player.getUUID();
        RecordingSession session = SESSIONS.remove(id);
        if (session == null) return;
        // 整段包 try/catch：退出回调触发时玩家可能已经离开世界，
        // level() 可能返回 null。而 session 已经被 SESSIONS.remove 拿走了
        // ——一旦抛出，后面的 stopRecord + save 都不执行，录制直接丢失。
        try {
            session.stopRecord(player.getLevel().getGameTime());
            Path f = save(session, id);
            if (f != null) {
                LOGGER.info("[WorldReplay] Recording saved {} ({} events)",
                        f.getFileName(), session.eventCount());
            }
        } catch (Exception e) {
            // 兜底：用 overworld 的 tick 再试一次落盘
            LOGGER.warn("[WorldReplay] Disconnect save failed ({}), retrying with fallback", e.getMessage());
            try {
                session.stopRecord(server.overworld().getGameTime());
                Path f = save(session, id);
                if (f != null) {
                    LOGGER.info("[WorldReplay] Recording saved (fallback) {} ({} events)",
                            f.getFileName(), session.eventCount());
                }
            } catch (Exception e2) {
                LOGGER.error("[WorldReplay] Recording lost for {}: {}", id, e2.getMessage());
            }
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
    private static void captureAt(ServerPlayer sp, String type, String data, double x, double y, double z) {
        RecordingSession session = SESSIONS.get(sp.getUUID());
        if (session == null) return;
        // Entity.getLevel() 的静态类型是 Level；服务端玩家一定在 ServerLevel 里，
        // 但需要显式转型才能拿到 dimension()。
        ServerLevel level = (ServerLevel) sp.getLevel();
        Event e = new Event(
                level.getGameTime(),
                type,
                sp.getUUID().toString(),
                level.dimensionTypeId().location().toString(),
                x, y, z,
                data);
        session.append(e);
    }
}

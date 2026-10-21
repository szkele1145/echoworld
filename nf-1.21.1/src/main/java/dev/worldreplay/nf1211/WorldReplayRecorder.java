package dev.worldreplay.nf1211;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;
import dev.worldreplay.core.event.Event;
import dev.worldreplay.core.event.RecordingSession;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WorldReplay 行为捕获（NeoForge 1.21.1 平台层）。
 * 把平台事件翻译为 core 统一 Event 格式，写入 {@link RecordingSession}。
 *
 * <p>每个在线玩家一个录制会话（进服开始，退服停止并落盘）。
 * 走原版方块/实体系统的事件——第三方 mod（MTR 等）的自定义方块自动被捕获。</p>
 *
 * <p><b>v0.2：实现完整的录制生命周期。</b></p>
 */
@EventBusSubscriber(modid = WorldReplayNeoForge.MOD_ID)
public final class WorldReplayRecorder {

    private static final Logger LOGGER = LogUtils.getLogger();
    /** 玩家 UUID -> 录制会话（进服开始，退服停止）。 */
    private static final Map<UUID, RecordingSession> SESSIONS = new ConcurrentHashMap<>();

    // ---- 生命周期 ----

    @SubscribeEvent
    public static void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer sp)) return;
        UUID id = sp.getUUID();
        RecordingSession session = RecordingSession.start(sp.serverLevel().getGameTime(), sp.getUUID().toString());
        SESSIONS.put(id, session);
        LOGGER.info("[WorldReplay] Recording started for {}", sp.getName().getString());
    }

    @SubscribeEvent
    public static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer sp)) return;
        UUID id = sp.getUUID();
        RecordingSession session = SESSIONS.remove(id);
        if (session == null) return;
        // 整段包 try/catch：退服事件触发时玩家可能已经离开世界，
        // serverLevel() 有可能抛异常。而 session 已经被 SESSIONS.remove
        // 拿走了——一旦在这里抛出，后面的 stopRecord + save 全都不会执行，
        // 玩家的录制直接丢失（且不会有任何提示）。
        try {
            // 会话时长闭环：先 stopRecord 落定 endTick（durationTicks 精确化），再导出
            session.stopRecord(sp.serverLevel().getGameTime());
            // 落盘：worldreplay/recordings/<uuid>-<startTick>.wrp（core 二进制格式，magic 0xE7C0）
            // 目录统一由 RecordControlHandler.recordingsDir 提供（此处曾自带一份字面量，
            // 与 /wr 读取路径、清理任务各自为政，改一处就漂移）
            Path f = save(session, id, RecordControlHandler.recordingsDir(sp.serverLevel().getServer()));
            if (f != null) {
                LOGGER.info("[WorldReplay] Recording saved {} ({} events)",
                        f.getFileName(), session.eventCount());
            }
        } catch (Exception e) {
            // 兜底：退服路径的任何异常都不能让录制白丢——用 overworld 的 tick
            // 再试一次落盘。
            LOGGER.warn("[WorldReplay] Logout save failed ({}), retrying with fallback", e.getMessage());
            try {
                session.stopRecord(sp.getServer().overworld().getGameTime());
                Path f = save(session, id, RecordControlHandler.recordingsDir(sp.getServer()));
                if (f != null) {
                    LOGGER.info("[WorldReplay] Recording saved (fallback) {} ({} events)",
                            f.getFileName(), session.eventCount());
                }
            } catch (Exception e2) {
                LOGGER.error("[WorldReplay] Recording lost for {}: {}", id, e2.getMessage());
            }
        }
    }

    /** 获取某玩家的录制会话（null = 未在录制）。 */
    public static RecordingSession getSession(UUID playerId) {
        return SESSIONS.get(playerId);
    }

    /** 服务端控制：开始录制某玩家（在线时才有效）。 */
    public static void startFor(MinecraftServer server, UUID id) {
        if (SESSIONS.containsKey(id)) return;   // 已在录制
        var sp = server.getPlayerList().getPlayer(id);
        if (sp == null) return;
        RecordingSession session = RecordingSession.start(sp.serverLevel().getGameTime(), id.toString());
        SESSIONS.put(id, session);
        LOGGER.info("[WorldReplay] Manual recording started for {}", sp.getName().getString());
    }

    /** 服务端控制：停止并落盘某玩家。 */
    public static void stopFor(MinecraftServer server, UUID id) {
        RecordingSession session = SESSIONS.remove(id);
        if (session == null) return;
        var sp = server.getPlayerList().getPlayer(id);
        String name = sp != null ? sp.getName().getString() : id.toString();
        // 会话时长闭环（与 logout 路径一致：同一维度、同一 tick 源；
        // 旧实现此行用 overworld().getGameTime()，与 logout 的 serverLevel() 不一致，
        // 下界/末地录制会因两个维度 tick 计数不同而多算/少算时长）
        //
        // 兜底：玩家已离线时 serverLevel() 可能返回 null/抛异常，
        // 而这行一旦抛异常就会打断整个 stopRecord + save 流程——录制直接丢失。
        long endTick;
        try {
            endTick = (sp != null && sp.serverLevel() != null)
                    ? sp.serverLevel().getGameTime()
                    : server.overworld().getGameTime();
        } catch (Exception e) {
            endTick = server.overworld().getGameTime();
        }
        session.stopRecord(endTick);
        Path f = save(session, id, RecordControlHandler.recordingsDir(server));
        if (f != null) {
            LOGGER.info("[WorldReplay] Manual recording saved {} ({} events)", f.getFileName(), session.eventCount());
        }
    }

    /**
     * 导出会话并落盘，返回写入的文件路径；失败返回 {@code null}。
     * 两条停止路径（logout / stopFor）共用，避免落盘逻辑再次分叉。
     */
    private static Path save(RecordingSession session, UUID id, Path dir) {
        try {
            byte[] data = session.export();
            java.nio.file.Files.createDirectories(dir);
            Path f = dir.resolve(id + "-" + session.startTick() + ".wrp");
            java.nio.file.Files.write(f, data);
            return f;
        } catch (Exception e) {
            LOGGER.error("[WorldReplay] Failed to save recording for {}", id, e);
            return null;
        }
    }

    // ---- 事件捕获 ----

    /** 带显式坐标的事件捕获（方块事件用方块位置，非玩家位置）。 */
    private static void captureAt(ServerPlayer sp, String type, String data, double x, double y, double z) {
        RecordingSession session = SESSIONS.get(sp.getUUID());
        if (session == null) return;
        Event e = new Event(
                sp.serverLevel().getGameTime(),
                type,
                sp.getUUID().toString(),
                sp.level().dimension().location().toString(),
                x, y, z,
                data);
        session.append(e);
    }

    @SubscribeEvent
    public static void onBreak(BlockEvent.BreakEvent event) {
        if (!(event.getPlayer() instanceof ServerPlayer sp)) return;
        captureAt(sp, "block_break",
                event.getState().getBlock().builtInRegistryHolder().getRegisteredName(),
                event.getPos().getX(), event.getPos().getY(), event.getPos().getZ());
    }

    @SubscribeEvent
    public static void onPlace(BlockEvent.EntityPlaceEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer sp)) return;
        captureAt(sp, "block_place",
                event.getPlacedBlock().getBlock().builtInRegistryHolder().getRegisteredName(),
                event.getPos().getX(), event.getPos().getY(), event.getPos().getZ());
    }

    @SubscribeEvent
    public static void onInteract(PlayerInteractEvent.RightClickBlock event) {
        if (event.getLevel().isClientSide()) return;
        if (event.getHand() != net.minecraft.world.InteractionHand.MAIN_HAND) return;   // 主手一次（副手不重复记）
        if (!(event.getEntity() instanceof ServerPlayer sp)) return;
        captureAt(sp, "block_use",
                event.getLevel().getBlockState(event.getPos()).getBlock().builtInRegistryHolder().getRegisteredName(),
                event.getPos().getX(), event.getPos().getY(), event.getPos().getZ());
    }
}

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
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WorldReplay 行为捕获（NeoForge 1.21.1 平台层）�? * 把平台事件翻译为 core 统一 Event 格式，写�?RecordingSession�? *
 * <p>每个在线玩家一个录制会话（进服开始，退服停止并落盘）�? * 走原版方�?实体系统的事件——第三方 mod（MTR 等）的自定义方块自动被捕获�?/p>
 *
 * <p><b>v0.2：实现完整的录制生命周期�?/b></p>
 */
@EventBusSubscriber(modid = WorldReplayNeoForge.MOD_ID)
public final class WorldReplayRecorder {

    private static final Logger LOGGER = LogUtils.getLogger();
    /** 玩家 UUID -> 录制会话（进服开始，退服停止）�?*/
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
        if (session != null) {
            // 落盘：.worldreplay/recordings/<uuid>-<startTick>.ecw（core 二进制格式，magic 0xE7C0）
            try {
                byte[] data = session.export();
                Path dir = sp.serverLevel().getServer().getServerDirectory()
                        .resolve("worldreplay").resolve("recordings");
                java.nio.file.Files.createDirectories(dir);
                Path f = dir.resolve(id + "-" + session.startTick() + ".ecw");
                java.nio.file.Files.write(f, data);
                LOGGER.info("[WorldReplay] Recording saved {} ({} events, {} bytes)",
                        f.getFileName(), session.eventCount(), data.length);
            } catch (Exception e) {
                LOGGER.error("[WorldReplay] Failed to save recording for {}", id, e);
            }
        }
    }

    /** 获取某玩家的录制会话（null = 未在录制）�?*/
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
        try {
            byte[] data = session.export();
            Path dir = server.getServerDirectory().resolve("worldreplay").resolve("recordings");
            java.nio.file.Files.createDirectories(dir);
            Path f = dir.resolve(id + "-" + session.startTick() + ".ecw");
            java.nio.file.Files.write(f, data);
            LOGGER.info("[WorldReplay] Manual recording saved {} ({} events)", f.getFileName(), session.eventCount());
        } catch (Exception e) {
            LOGGER.error("[WorldReplay] Failed to save manual recording for {}", name, e);
        }
    }

    // ---- 事件捕获 ----

    private static void capture(ServerPlayer sp, String type, String data) {
        RecordingSession session = SESSIONS.get(sp.getUUID());
        if (session == null) return;
        Event e = new Event(
                sp.serverLevel().getGameTime(),
                type,
                sp.getUUID().toString(),
                sp.level().dimension().location().toString(),
                sp.getX(), sp.getY(), sp.getZ(),
                data);
        session.append(e);
    }

    @SubscribeEvent
    public static void onBreak(BlockEvent.BreakEvent event) {
        if (!(event.getPlayer() instanceof ServerPlayer sp)) return;
        capture(sp, "block_break",
                event.getState().getBlock().builtInRegistryHolder().getRegisteredName());
    }

    @SubscribeEvent
    public static void onPlace(BlockEvent.EntityPlaceEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer sp)) return;
        capture(sp, "block_place",
                event.getPlacedBlock().getBlock().builtInRegistryHolder().getRegisteredName());
    }

    @SubscribeEvent
    public static void onInteract(PlayerInteractEvent.RightClickBlock event) {
        if (event.getLevel().isClientSide()) return;
        if (!(event.getEntity() instanceof ServerPlayer sp)) return;
        capture(sp, "block_use",
                event.getLevel().getBlockState(event.getPos()).getBlock().builtInRegistryHolder().getRegisteredName());
    }
}

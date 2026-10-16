package dev.worldreplay.nf1211;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 服务端定时清理：每隔 {@link #CLEANUP_INTERVAL_TICKS} tick（约 5 分钟）扫描一次
 * {@code worldreplay/recordings} 目录，删除超过 {@link WorldReplayConfig#AUTO_CLEANUP_DAYS}
 * 天的 .wrp 录制文件。days<=0 时不执行任何清理。
 */
@EventBusSubscriber(modid = WorldReplayNeoForge.MOD_ID, bus = EventBusSubscriber.Bus.GAME)
public final class WorldReplayServerTick {

    private static final Logger LOGGER = LoggerFactory.getLogger(WorldReplayServerTick.class);
    /** 清理扫描间隔：6000 tick ≈ 5 分钟（20 tick/秒）。 */
    private static final int CLEANUP_INTERVAL_TICKS = 6000;
    private static int ticksSinceCleanup = 0;

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        int days = WorldReplayConfig.AUTO_CLEANUP_DAYS.get();
        if (days <= 0) return;                          // 配置为 0：禁用自动清理
        if (++ticksSinceCleanup < CLEANUP_INTERVAL_TICKS) return;
        ticksSinceCleanup = 0;
        cleanupOldRecordings(event.getServer(), days);
    }

    private static void cleanupOldRecordings(MinecraftServer server, int days) {
        Path dir = RecordControlHandler.recordingsDir(server);
        if (!Files.isDirectory(dir)) return;
        long cutoff = System.currentTimeMillis() - (long) days * 24 * 60 * 60 * 1000L;
        try (var s = Files.list(dir)) {
            for (Path f : (Iterable<Path>) s::iterator) {
                String name = f.getFileName().toString();
                // 后缀不区分大小写，与列表扫描保持一致——否则手工改名成 .WRP 的录制
                // 永远不会被清理（在 GUI 里也看不见，等于永久占用磁盘）
                if (!RecordControlHandler.hasWrpSuffix(name) || !Files.isRegularFile(f)) continue;
                try {
                    long modified = Files.getLastModifiedTime(f).toMillis();
                    if (modified < cutoff) {
                        Files.deleteIfExists(f);
                        LOGGER.info("[WorldReplay] Auto-cleaned expired recording {}", name);
                    }
                } catch (IOException e) {
                    // 被占用/只读：记一笔，下一轮再试（以前完全静默，磁盘只增不减且无从排查）
                    LOGGER.warn("[WorldReplay] Failed to auto-clean {}: {}", name, e.getMessage());
                }
            }
        } catch (IOException e) {
            LOGGER.warn("[WorldReplay] Auto-clean scan failed on {}: {}", dir, e.getMessage());
        }
    }

    private WorldReplayServerTick() {}
}

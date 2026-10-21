package dev.worldreplay.forge1201;

import net.minecraftforge.fml.loading.FMLPaths;

import java.nio.file.Path;
import java.util.List;

/**
 * WorldReplay Forge 1.20.1 服务端配置（写入 {@code config/worldreplay.toml}）。
 *
 * <p>Forge 有内置的 {@code ModConfigSpec}，但为与 Fabric / NeoForge 侧
 * 保持完全一致的文件格式与容错行为，这里同样手写一个简单 TOML：
 * 每行独立解析，一行写坏不会影响其它配置项。</p>
 */
public final class WorldReplayConfig {

    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(WorldReplayConfig.class);

    /** 自动清理录制文件的天数（0 表示禁用，不自动删除）。 */
    public static int AUTO_CLEANUP_DAYS = 30;

    /** 是否允许客户端发起录制重命名（默认开启）。 */
    public static boolean RENAME_ENABLED = true;

    private static final String CONFIG_FILE_NAME = "worldreplay.toml";

    private WorldReplayConfig() {}

    /** 从 config/worldreplay.toml 加载（缺失则用默认值并写入示例）。 */
    public static void load() {
        try {
            var configDir = FMLPaths.CONFIGDIR.get();
            java.nio.file.Files.createDirectories(configDir);
            Path f = configDir.resolve(CONFIG_FILE_NAME);
            if (java.nio.file.Files.isRegularFile(f)) {
                List<String> lines = java.nio.file.Files.readAllLines(f);
                for (String line : lines) {
                    String t = line.trim();
                    if (t.isEmpty() || t.startsWith("#")) continue;   // 注释与空行
                    // 每行独立解析：一行写坏（旧实现会因 parseInt 抛异常
                    // 中断整个循环，**后面的配置项根本没被读到**）
                    // 不影响其它项。
                    try {
                        if (t.startsWith("autoCleanupDays")) {
                            String[] kv = t.split("=", 2);
                            if (kv.length == 2) {
                                int v = Integer.parseInt(kv[1].trim());
                                if (v >= 0 && v <= 365) {
                                    AUTO_CLEANUP_DAYS = v;
                                } else {
                                    LOGGER.warn("[WorldReplay] autoCleanupDays={} 超出范围 0-365，已忽略", v);
                                }
                            }
                        } else if (t.startsWith("renameEnabled")) {
                            String[] kv = t.split("=", 2);
                            if (kv.length == 2) {
                                RENAME_ENABLED = Boolean.parseBoolean(kv[1].trim());
                            }
                        }
                    } catch (NumberFormatException e) {
                        LOGGER.warn("[WorldReplay] 配置行无法解析，已忽略: {}", t);
                    }
                }
            } else {
                save();
            }
        } catch (Exception e) {
            // 整体读取失败：保留默认值，不影响游戏运行
            LOGGER.warn("[WorldReplay] 配置读取失败，使用默认配置", e);
        }
    }

    private static void save() throws Exception {
        var configDir = FMLPaths.CONFIGDIR.get();
        java.nio.file.Files.createDirectories(configDir);
        Path f = configDir.resolve(CONFIG_FILE_NAME);
        String content = "# WorldReplay 配置\n"
                + "autoCleanupDays=" + AUTO_CLEANUP_DAYS + "\n"
                + "# 自动清理录制文件的天数（0 表示禁用，不自动删除，范围 0-365）\n"
                + "renameEnabled=" + RENAME_ENABLED + "\n"
                + "# 是否允许客户端发起录制重命名（true/false）\n";
        // 显式 UTF-8：默认字符集在中文 Windows 上是 GBK，
        // 写出的中文注释用别的编辑器打开会乱码（解析不受影响，只是难看）
        java.nio.file.Files.writeString(f, content, java.nio.charset.StandardCharsets.UTF_8);
    }
}

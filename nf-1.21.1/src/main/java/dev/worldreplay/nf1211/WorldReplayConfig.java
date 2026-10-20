package dev.worldreplay.nf1211;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * WorldReplay 服务端配置（写入 {@code config/worldreplay.toml}）。
 *
 * <p>当前仅暴露自动清理策略；后续可在此追加更多服务端行为开关。</p>
 */
public final class WorldReplayConfig {

    public static final ModConfigSpec SPEC;
    /** 自动清理录制文件的天数（0 表示禁用，不自动删除）。 */
    public static final ModConfigSpec.IntValue AUTO_CLEANUP_DAYS;
    /**
     * 是否允许客户端发起录制重命名（默认开启）。
     *
     * <p>Fabric 侧一直有 {@code renameEnabled} 开关，NeoForge 侧此前没有——
     * 同一个配置文件在两个平台上含义不一致，服主想禁用重命名时
     * 在 NeoForge 上无处可关。现已对齐。</p>
     */
    public static final ModConfigSpec.BooleanValue RENAME_ENABLED;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        builder.push("general");
        AUTO_CLEANUP_DAYS = builder
                .comment("自动清理录制文件的天数（0 表示禁用，不自动删除）")
                .defineInRange("autoCleanupDays", 30, 0, 365);
        RENAME_ENABLED = builder
                .comment("是否允许客户端发起录制重命名（true/false）")
                .define("renameEnabled", true);
        builder.pop();
        SPEC = builder.build();
    }

    /**
     * 安全读取配置值。
     *
     * <p>{@code ModConfigSpec.ConfigValue.get()} 在配置尚未加载时抛
     * {@code IllegalStateException: Cannot get config value before config is loaded.}。
     * 网络包处理器等早期调用点可能早于配置加载，用这里兜底到默认值，
     * 避免一个配置读取把整条重命名流程炸掉。</p>
     */
    private static <T> T safe(ModConfigSpec.ConfigValue<T> value, T fallback) {
        try {
            return value.get();
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    /** 当前生效的自动清理天数（配置未加载时按 30 天算）。 */
    public static int autoCleanupDays() {
        return safe(AUTO_CLEANUP_DAYS, 30);
    }

    /** 当前是否允许重命名（配置未加载时按允许算）。 */
    public static boolean renameEnabled() {
        return safe(RENAME_ENABLED, Boolean.TRUE);
    }

    private WorldReplayConfig() {}
}

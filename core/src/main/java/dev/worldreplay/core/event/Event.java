package dev.worldreplay.core.event;

/**
 * 全量行为事件的统一格式。
 *
 * <p>所有平台（NeoForge / Fabric）捕获的行为都会归一化为该 record，
 * type 约定取值：block_place / block_break / block_use / container_open /
 * container_close / entity_move / gui_open / ...（可自由扩展）。
 * data 为 JSON 扩展字段，由各事件类型自行定义结构。</p>
 *
 * @param tick      事件发生的游戏 tick（非负）
 * @param type      事件类型，非空
 * @param playerId  玩家标识（UUID 字符串，可为空表示系统事件）
 * @param dimension 维度 id（如 minecraft:overworld）
 * @param x         事件坐标 X
 * @param y         事件坐标 Y
 * @param z         事件坐标 Z
 * @param data      JSON 扩展字段，无附加数据时为 "{}"
 */
public record Event(long tick, String type, String playerId, String dimension,
                    double x, double y, double z, String data) {

    /** data 缺省值：空 JSON 对象。 */
    public static final String EMPTY_DATA = "{}";

    public Event {
        if (type == null) {
            type = "";
        }
        if (data == null) {
            data = EMPTY_DATA;
        }
        if (dimension == null) {
            dimension = "minecraft:overworld";   // 兜底：null 维度不穿透到编码层（旧 bug 修复）
        }
    }

    /** 校验事件是否有效：tick 非负、type 非空、坐标不是 NaN/Infinity。 */
    public boolean isValid() {
        if (tick < 0 || type.isEmpty()) return false;
        // 坐标必须是有限值：NaN/Infinity 会让客户端的矩阵变换整个变成 NaN，
        // 结果是一整片区块不渲染（而且很难查）。.wrp 可以是手工构造的，
        // 不能假设坐标一定合理。
        return !Double.isNaN(x) && !Double.isNaN(y) && !Double.isNaN(z)
                && !Double.isInfinite(x) && !Double.isInfinite(y) && !Double.isInfinite(z);
    }
}

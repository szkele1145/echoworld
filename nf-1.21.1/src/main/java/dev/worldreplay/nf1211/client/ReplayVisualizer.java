package dev.worldreplay.nf1211.client;

import dev.worldreplay.core.event.Event;
import dev.worldreplay.nf1211.WorldReplayNeoForge;
import dev.worldreplay.core.event.ReplayEngine;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

import java.util.List;

/**
 * 世界内回放渲染：当前回放时刻附近的事件以彩色线框 + 浮字重现。
 *
 * <p><b>幽灵语义</b>：所有元素整体 50% 透明度上限，一眼看出是回放而非真实事件。
 * 动作标记随淡出窗口衰减（与 GlowTrack ActionRenderer 同款视觉语言）。</p>
 *
 * <p>由 GUI 的 Replay 按钮设置 {@link #begin(List)}，每客户端 tick 由
 * {@link WorldReplayClient} 推进 ReplayEngine。</p>
 */
@EventBusSubscriber(modid = WorldReplayNeoForge.MOD_ID, value = Dist.CLIENT)
public final class ReplayVisualizer {

    /** 活动回放会话（null = 未在回放）。 */
    private static ReplayEngine engine;
    private static List<Event> events;
    /** 幽灵透明度上限（50%，用户要求"看得出来是回放"）。 */
    private static final float GHOST_ALPHA = 0.5f;
    /** 事件标记显示窗口（游戏刻）。 */
    private static final int MARK_WINDOW_TICKS = 80;

    /** 开始回放（GUI Replay 按钮调用）。 */
    public static void begin(List<Event> loadedEvents) {
        events = loadedEvents;
        engine = new ReplayEngine(events);
        engine.play();
    }

    /** 停止回放。 */
    public static void end() {
        engine = null;
        events = null;
    }

    public static boolean isReplaying() { return engine != null && engine.isPlaying(); }

    /** 每客户端 tick 推进（WorldReplayClient.onClientTick 调用）。 */
    public static void tick() {
        if (engine != null && engine.isPlaying()) engine.tick();
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (engine == null || events == null || events.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        long currentTick = engine.getCurrentTick();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        Font font = mc.font;

        PoseStackAccessor.begin(event);
        Vec3 cam = event.getCamera().getPosition();
        var pose = PoseStackAccessor.pose();
        pose.translate(-cam.x, -cam.y, -cam.z);
        Matrix4f mat = pose.last().pose();

        // 事件窗口：当前 tick 前后各 40（同步节拍）
        long lo = currentTick - MARK_WINDOW_TICKS / 2;
        long hi = currentTick + MARK_WINDOW_TICKS / 2;
        // events 按 tick 有序——二分定位窗口（ReplayEngine 已排序）
        for (Event e : events) {
            if (e.tick() < lo) continue;
            if (e.tick() > hi) break;
            // 淡出系数：越旧越透明
            float age = (currentTick - e.tick()) / (float) MARK_WINDOW_TICKS;   // 0.5..1.5
            float alpha = GHOST_ALPHA * Math.max(0f, 1f - Math.abs(age - 0.5f) * 2f);
            if (alpha < 0.03f) continue;

            int color = switch (e.type()) {
                case "block_place" -> 0x00FFFF;
                case "block_break" -> 0xFF5555;
                case "block_use", "container_open", "gui_open" -> 0xFFD700;
                default -> 0xFFFFFF;
            };
            float cr = ((color >> 16) & 0xFF) / 255f;
            float cg = ((color >> 8) & 0xFF) / 255f;
            float cb = (color & 0xFF) / 255f;
            var buf = buffers.getBuffer(RenderType.debugLineStrip(2.0f));
            drawBox(buf, mat, e.x(), e.y(), e.z(), cr, cg, cb, alpha);

            // 幽灵浮字：动作简述（50% 透明）
            String label = ghostLabel(e);
            pose.pushPose();
            pose.translate(e.x(), e.y() + 2.2, e.z());
            pose.mulPose(mc.getEntityRenderDispatcher().cameraOrientation());
            float ls = 0.035f;
            pose.scale(ls, -ls, ls);
            int argb = ((int) (alpha * 255) << 24) | 0xFFFFFF;
            font.drawInBatch(label, -font.width(label) / 2f, 0f, argb, true,
                    pose.last().pose(), buffers, Font.DisplayMode.SEE_THROUGH,
                    LightTexture.FULL_BRIGHT, LightTexture.FULL_BRIGHT);
            pose.popPose();
        }

        buffers.endBatch();
        PoseStackAccessor.end();
    }

    /** 幽灵标签（回放语态）。 */
    private static String ghostLabel(Event e) {
        return switch (e.type()) {
            case "block_place" -> "ghost + " + shortId(e.data());
            case "block_break" -> "ghost - " + shortId(e.data());
            case "block_use" -> "ghost use " + shortId(e.data());
            default -> "ghost " + e.type();
        };
    }

    private static String shortId(String data) {
        if (data == null) return "?";
        int i = data.indexOf(':');
        return i >= 0 && i < data.length() - 1 ? data.substring(i + 1) : data;
    }

    private static void drawBox(com.mojang.blaze3d.vertex.VertexConsumer buf, Matrix4f mat,
                                double x, double y, double z,
                                float r, float g, float b, float a) {
        double x0 = x, x1 = x + 1, y0 = y, y1 = y + 1, z0 = z, z1 = z + 1;
        edge(buf, mat, x0,y0,z0, x1,y0,z0, r,g,b,a); edge(buf, mat, x1,y0,z0, x1,y0,z1, r,g,b,a);
        edge(buf, mat, x1,y0,z1, x0,y0,z1, r,g,b,a); edge(buf, mat, x0,y0,z1, x0,y0,z0, r,g,b,a);
        edge(buf, mat, x0,y1,z0, x1,y1,z0, r,g,b,a); edge(buf, mat, x1,y1,z0, x1,y1,z1, r,g,b,a);
        edge(buf, mat, x1,y1,z1, x0,y1,z1, r,g,b,a); edge(buf, mat, x0,y1,z1, x0,y1,z0, r,g,b,a);
        edge(buf, mat, x0,y0,z0, x0,y1,z0, r,g,b,a); edge(buf, mat, x1,y0,z0, x1,y1,z0, r,g,b,a);
        edge(buf, mat, x1,y0,z1, x1,y1,z1, r,g,b,a); edge(buf, mat, x0,y0,z1, x0,y1,z1, r,g,b,a);
    }

    private static void edge(com.mojang.blaze3d.vertex.VertexConsumer buf, Matrix4f mat,
                             double x0, double y0, double z0, double x1, double y1, double z1,
                             float r, float g, float b, float a) {
        buf.addVertex(mat, (float) x0, (float) y0, (float) z0).setColor(r, g, b, a);
        buf.addVertex(mat, (float) x1, (float) y1, (float) z1).setColor(r, g, b, a);
    }

    private ReplayVisualizer() {}

    /** PoseStack 传递辅助（避免把 RenderLevelStageEvent 泄到字段）。 */
    private static final class PoseStackAccessor {
        private static com.mojang.blaze3d.vertex.PoseStack pose;
        static void begin(RenderLevelStageEvent e) { pose = e.getPoseStack(); pose.pushPose(); }
        static com.mojang.blaze3d.vertex.PoseStack pose() { return pose; }
        static void end() { if (pose != null) pose.popPose(); pose = null; }
    }
}

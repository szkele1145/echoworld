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

    /** 回放中且引擎存在（暂停时也算——暂停/继续判定用）。 */
    public static boolean hasEngine() { return engine != null; }

    /** 暂停回放。 */
    public static void pause() {
        if (engine != null) engine.pause();
    }

    /** 继续回放。 */
    public static void resume() {
        if (engine != null) engine.play();
    }

    /** 是否处于暂停态（引擎在但没在播）。 */
    public static boolean isPaused() { return engine != null && !engine.isPlaying(); }

    /** 调倍速（0.25~64 引擎内钳制）。 */
    public static void setSpeed(double speed) {
        if (engine != null) engine.setSpeed(speed);
    }

    public static double getSpeed() { return engine != null ? engine.getSpeed() : 1.0; }

    /** 每客户端 tick 推进（WorldReplayClient.onClientTick 调用）。 */
    public static void tick() {
        if (engine != null && engine.isPlaying()) engine.tick();
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        // 只在半透明方块后渲染一次（修：全 stage 重复渲染 5~8 次）
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
        if (engine == null || !engine.isPlaying()) return;   // 播完不残留标记（角标只在播/暂停时显示——暂停态由下方 HUD 分支处理）
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.gui == null) return;

        long currentTick = engine.getCurrentTick();
        List<Event> timeline = engine.timelineView();   // 有序时间线（引擎保证，不再用原始乱序列表）
        if (timeline.isEmpty()) return;
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        Font font = mc.font;

        PoseStackAccessor.begin(event);
        try {
            Vec3 cam = event.getCamera().getPosition();
            var pose = PoseStackAccessor.pose();
            pose.translate(-cam.x, -cam.y, -cam.z);
            Matrix4f mat = pose.last().pose();

            // 事件窗口：只画已重放的部分 [currentTick-80, currentTick]
            long lo = currentTick - MARK_WINDOW_TICKS;
            long hi = currentTick;
            for (Event e : timeline) {
                if (e.tick() < lo) continue;
                if (e.tick() > hi) break;
                // 淡出：当前时刻最亮，随年龄线性衰减到 0（修：原公式方向反了）
                float age = (currentTick - e.tick()) / (float) MARK_WINDOW_TICKS;   // 0..1
                float alpha = GHOST_ALPHA * (1.0f - age);
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
                var buf = buffers.getBuffer(LINE_RENDER_TYPE);
                drawBox(buf, mat, e.x(), e.y(), e.z(), cr, cg, cb, alpha);

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
        } finally {
            PoseStackAccessor.end();
        }

        // HUD 角标：回放状态条（倍速 + 暂停态 + 进度条），画在屏幕左上（不与世界渲染互斥）
        if (mc.gui != null) {
            String state = isPaused() ? "[WorldReplay] ⏸ paused " + fmtSpeed() + " (P resume)"
                    : "[WorldReplay] ▶ " + fmtSpeed() + "x";
            var gg = new net.minecraft.client.gui.GuiGraphics(mc, mc.renderBuffers().bufferSource());
            gg.drawString(font, state, 8, 8, 0xFFFFD700, true);
            // 进度条：当前 tick / 总时长（首事件 tick 到 endTick 的位置百分比）
            long firstTick = timeline.isEmpty() ? 0 : timeline.get(0).tick();
            long endTick = engine.endTick();
            long span = Math.max(1, endTick - firstTick);
            double progress = Math.max(0, Math.min(1, (currentTick - firstTick) / (double) span));
            int barW = 120, barX = 8, barY = 20;
            gg.fill(barX, barY, barX + barW, barY + 3, 0x80000000);              // 轨道
            gg.fill(barX, barY, barX + (int) (barW * progress), barY + 3, 0xFFFFD700);   // 进度
            // 时间标签：游戏时长（分:秒）
            long playedSecs = (currentTick - firstTick) / 20;
            long totalSecs = span / 20;
            String time = String.format("%d:%02d / %d:%02d", playedSecs / 60, playedSecs % 60,
                    totalSecs / 60, totalSecs % 60);
            gg.drawString(font, time, barX + barW + 6, barY - 3, 0xFF8B949E, true);
        }
    }

    private static String fmtSpeed() {
        double s = getSpeed();
        return s < 10 ? String.format("%.1f", s) : String.format("%.0f", s);
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

    /**
     * 事件线框的粗细（世界单位）。
     *
     * <p>不用 {@code RenderType.debugLineStrip(w)}：那走的是 {@code glLineWidth}，
     * 现代 core profile 驱动把它钳到 1px，线框在世界里几乎看不见。
     * 改成自己挤出四边形（按屏幕空间偏移）才能真正画出粗线。</p>
     */
    private static final float LINE_HALF_WIDTH = 0.035f;

    /** 线框用的 RenderType（三角形几何，必须与浮字用的 buffer 分桶）。 */
    private static final RenderType LINE_RENDER_TYPE = RenderType.create(
            "worldreplay_ghost_lines",
            com.mojang.blaze3d.vertex.DefaultVertexFormat.POSITION_COLOR,
            com.mojang.blaze3d.vertex.VertexFormat.Mode.QUADS,
            1536, false, true,
            RenderType.CompositeState.builder()
                    .setShaderState(RenderType.POSITION_COLOR_SHADER)
                    .setTransparencyState(net.minecraft.client.renderer.RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                    .setCullState(net.minecraft.client.renderer.RenderStateShard.NO_CULL)
                    .setWriteMaskState(net.minecraft.client.renderer.RenderStateShard.COLOR_WRITE)
                    .createCompositeState(false));

    /** 事件标记的线框（真实宽度四边形）。 */
    private static void drawBox(com.mojang.blaze3d.vertex.VertexConsumer buf, Matrix4f mat,
                                double x, double y, double z,
                                float r, float g, float b, float a) {
        double x0 = x, x1 = x + 1, y0 = y, y1 = y + 1, z0 = z, z1 = z + 1;
        // 12 条棱：4 底 + 4 顶 + 4 竖
        seg(buf, mat, x0,y0,z0, x1,y0,z0, r,g,b,a);
        seg(buf, mat, x1,y0,z0, x1,y0,z1, r,g,b,a);
        seg(buf, mat, x1,y0,z1, x0,y0,z1, r,g,b,a);
        seg(buf, mat, x0,y0,z1, x0,y0,z0, r,g,b,a);
        seg(buf, mat, x0,y1,z0, x1,y1,z0, r,g,b,a);
        seg(buf, mat, x1,y1,z0, x1,y1,z1, r,g,b,a);
        seg(buf, mat, x1,y1,z1, x0,y1,z1, r,g,b,a);
        seg(buf, mat, x0,y1,z1, x0,y1,z0, r,g,b,a);
        seg(buf, mat, x0,y0,z0, x0,y1,z0, r,g,b,a);
        seg(buf, mat, x1,y0,z0, x1,y1,z0, r,g,b,a);
        seg(buf, mat, x1,y0,z1, x1,y1,z1, r,g,b,a);
        seg(buf, mat, x0,y0,z1, x0,y1,z1, r,g,b,a);
    }

    /**
     * 画一条有真实宽度的线段（两个三角形）。
     *
     * <p>偏移方向取线段方向的法平面——对水平线用 (0,1,0)×dir，
     * 对竖直线用 (1,0,0)×dir，这样从任何角度看都有厚度。</p>
     */
    private static void seg(com.mojang.blaze3d.vertex.VertexConsumer buf, Matrix4f mat,
                            double x0, double y0, double z0, double x1, double y1, double z1,
                            float r, float g, float b, float a) {
        double dx = x1 - x0, dy = y1 - y0, dz = z1 - z0;
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1e-9) return;
        // 找一个与方向不平行的参考向量做叉乘，得到法平面偏移方向
        double ux, uy, uz;
        if (Math.abs(dy) > 0.9 * len) {
            ux = 1; uy = 0; uz = 0;      // 竖直线：用 X 轴
        } else {
            ux = 0; uy = 1; uz = 0;      // 水平线：用 Y 轴
        }
        // n = dir × u，归一化
        double nx = dy * uz - dz * uy;
        double ny = dz * ux - dx * uz;
        double nz = dx * uy - dy * ux;
        double nl = Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (nl < 1e-9) return;
        nx = nx / nl * LINE_HALF_WIDTH;
        ny = ny / nl * LINE_HALF_WIDTH;
        nz = nz / nl * LINE_HALF_WIDTH;

        // 四个顶点：p0+n, p1+n, p1-n, p0-n
        double ax = x0 + nx, ay = y0 + ny, az = z0 + nz;
        double bx = x1 + nx, by = y1 + ny, bz = z1 + nz;
        double cx = x1 - nx, cy = y1 - ny, cz = z1 - nz;
        double dx2 = x0 - nx, dy2 = y0 - ny, dz2 = z0 - nz;

        vtx(buf, mat, ax, ay, az, r, g, b, a);
        vtx(buf, mat, bx, by, bz, r, g, b, a);
        vtx(buf, mat, cx, cy, cz, r, g, b, a);
        vtx(buf, mat, ax, ay, az, r, g, b, a);
        vtx(buf, mat, cx, cy, cz, r, g, b, a);
        vtx(buf, mat, dx2, dy2, dz2, r, g, b, a);
    }

    private static void vtx(com.mojang.blaze3d.vertex.VertexConsumer buf, Matrix4f mat,
                            double x, double y, double z, float r, float g, float b, float a) {
        buf.addVertex(mat, (float) x, (float) y, (float) z).setColor(r, g, b, a);
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

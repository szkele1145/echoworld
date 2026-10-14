package dev.worldreplay.nf1211.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * WorldReplay 录制管理屏（OP 用）：/echoreplay gui 打开。
 *
 * <p>左栏：录制文件列表（文件管理器式，点击选中，DEL 删除）。
 * 右栏：录制设置——在线玩家多选（全选/反选）+ 录制策略。</p>
 *
 * <p>视觉风格与 GlowTrack 一致：扁平深色 + 单像素边框。</p>
 */
public class RecordingScreen extends Screen {

    private static final int PANEL_W = 500, PANEL_H = 240;
    private static final int ROW_H = 20;

    private int left, top;

    // ---- 文件列表 ----
    private final List<RecFile> files = new ArrayList<>();
    private int selectedFile = -1;
    private int fileScroll = 0;

    // ---- 录制目标（在线玩家）----
    /** 选中待录制的玩家名；空集 = 不录。 */
    private final List<String> chosenPlayers = new ArrayList<>();
    private List<String> onlinePlayers = new ArrayList<>();
    private boolean recordAll = false;
    /** 策略：0=持续录（一直录），1=仅手动开始/停止。 */
    private int policy = 0;
    private Button startBtn, stopBtn;
    private static final String[] POLICY_NAMES = {"Always (while online)", "Manual start/stop"};

    /** 一条录制文件。 */
    record RecFile(String name, long size, long modified) {}

    public RecordingScreen() {
        super(Component.literal("WorldReplay Recorder"));
    }

    @Override
    protected void init() {
        left = (this.width - PANEL_W) / 2;
        top = (this.height - PANEL_H) / 2;
        // 右栏按钮
        addRenderableWidget(Button.builder(Component.literal("Select All"), b -> {
            recordAll = true; chosenPlayers.clear(); chosenPlayers.addAll(onlinePlayers);
        }).bounds(left + 280, top + 40, 100, 18).build());
        addRenderableWidget(Button.builder(Component.literal("Clear"), b -> {
            chosenPlayers.clear(); recordAll = false;
        }).bounds(left + 385, top + 40, 100, 18).build());
        addRenderableWidget(Button.builder(Component.literal("Policy: " + POLICY_NAMES[policy]), b -> {
            policy = (policy + 1) % POLICY_NAMES.length;
            b.setMessage(Component.literal("Policy: " + POLICY_NAMES[policy]));
        }).bounds(left + 280, top + 62, 205, 18).build());
        startBtn = Button.builder(Component.literal("Start Recording"), b -> sendControl(true))
                .bounds(left + 280, top + PANEL_H - 30, 205, 20).build();
        addRenderableWidget(startBtn);
        stopBtn = Button.builder(Component.literal("Stop"), b -> sendControl(false))
                .bounds(left + 280, top + PANEL_H - 56, 205, 20).build();
        addRenderableWidget(stopBtn);
        addRenderableWidget(Button.builder(Component.literal("Delete"), b -> deleteSelected())
                .bounds(left + 8, top + PANEL_H - 30, 100, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Replay"), b -> replaySelected())
                .bounds(left + 114, top + PANEL_H - 30, 100, 20).build());
        refresh();
    }

    private void refresh() {
        files.clear();
        try {
            Path dir = Path.of("worldreplay", "recordings");
            if (Files.isDirectory(dir)) {
                try (var s = Files.list(dir)) {
                    for (Path f : (Iterable<Path>) s.filter(p -> p.toString().endsWith(".ecw"))::iterator) {
                        files.add(new RecFile(f.getFileName().toString(), Files.size(f),
                                Files.getLastModifiedTime(f).toMillis()));
                    }
                }
                files.sort((a, b) -> Long.compare(b.modified(), a.modified()));
            }
        } catch (Exception ignored) {}
        // 在线玩家（客户端可见的 tab 列表）
        onlinePlayers.clear();
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.getConnection() != null) {
            mc.getConnection().getOnlinePlayers().forEach(p -> onlinePlayers.add(p.getProfile().getName()));
        }
    }

    private void deleteSelected() {
        if (selectedFile < 0 || selectedFile >= files.size()) return;
        try {
            Files.deleteIfExists(Path.of("worldreplay", "recordings", files.get(selectedFile).name()));
            selectedFile = -1;
            refresh();
        } catch (Exception ignored) {}
    }

    private void replaySelected() {
        if (selectedFile < 0 || selectedFile >= files.size()) return;
        try {
            Path f = Path.of("worldreplay", "recordings", files.get(selectedFile).name());
            byte[] data = Files.readAllBytes(f);
            java.io.DataInputStream in = new java.io.DataInputStream(new java.io.ByteArrayInputStream(data));
            dev.worldreplay.core.event.EventDecoder.readHeader(in);
            java.util.List<dev.worldreplay.core.event.Event> events = new ArrayList<>();
            while (in.available() > 0) {
                var ev = dev.worldreplay.core.event.EventDecoder.readFrom(in);
                if (ev != null) events.add(ev);
            }
            ReplayVisualizer.begin(events);
            Minecraft.getInstance().setScreen(null);   // 关 GUI 看世界
        } catch (Exception ignored) {}
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float pt) {
        super.render(g, mouseX, mouseY, pt);
        // 面板
        g.fill(left, top, left + PANEL_W, top + PANEL_H, 0xF016181D);
        g.fill(left, top, left + PANEL_W, top + 22, 0xFF21262D);
        g.drawString(font, "WorldReplay Recorder", left + 8, top + 7, 0xE6EDF3, false);

        // 左栏：文件列表
        int listW = 260, listTop = top + 28;
        g.fill(left + 6, listTop, left + 6 + listW, top + PANEL_H - 38, 0xFF1A1D23);
        int y = listTop + 3 - fileScroll;
        for (int i = 0; i < files.size(); i++) {
            if (y + ROW_H < listTop || y > top + PANEL_H - 40) { y += ROW_H; continue; }
            RecFile f = files.get(i);
            boolean hov = mouseX >= left + 6 && mouseX < left + 6 + listW && mouseY >= y && mouseY < y + ROW_H;
            int bg = i == selectedFile ? 0xFF1F6FEB : (hov ? 0xFF292E36 : 0x001A1D23);
            if (bg != 0) g.fill(left + 6, y, left + 6 + listW, y + ROW_H - 1, bg);
            String size = f.size() < 1024 ? f.size() + "B" : (f.size() / 1024) + "KB";
            g.drawString(font, f.name(), left + 12, y + 6, i == selectedFile ? 0xFFFFFF : 0xC9D1D9, false);
            g.drawString(font, size, left + 6 + listW - 42, y + 6, 0x6E7681, false);
            y += ROW_H;
        }
        if (files.isEmpty())
            g.drawString(font, "(no recordings)", left + 12, listTop + 6, 0x6E7681, false);

        // 右栏：录制设置
        int rx = left + 276;
        g.drawString(font, "Record who:", rx, top + 30, 0x8B949E, false);
        // 玩家多选 chips（两列）
        int py = top + 88;
        int col = 0;
        for (String p : onlinePlayers) {
            boolean on = recordAll || chosenPlayers.contains(p);
            int w = Math.min(100, font.width(p) + 14);
            int cx = rx + col * 105;
            int bgc = on ? 0xFF238636 : 0xFF292E36;
            g.fill(cx, py, cx + w, py + 16, bgc);
            g.drawString(font, p, cx + 5, py + 4, on ? 0xFFFFFF : 0xC9D1D9, false);
            col++;
            if (col >= 2) { col = 0; py += 19; }
        }
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        // 文件列表点击
        int listTop = top + 28;
        int y = listTop + 3 - fileScroll;
        for (int i = 0; i < files.size(); i++) {
            if (mx >= left + 6 && mx < left + 266 && my >= y && my < y + ROW_H) {
                selectedFile = i;
                return true;
            }
            y += ROW_H;
        }
        // 玩家 chip 点击
        int py = top + 88;
        int col = 0;
        for (String p : onlinePlayers) {
            int w = Math.min(100, font.width(p) + 14);
            int cx = left + 276 + col * 105;
            if (mx >= cx && mx < cx + w && my >= py && my < py + 16) {
                if (recordAll) { chosenPlayers.addAll(onlinePlayers); chosenPlayers.remove(p); recordAll = false; }
                else if (chosenPlayers.contains(p)) chosenPlayers.remove(p);
                else chosenPlayers.add(p);
                return true;
            }
            col++;
            if (col >= 2) { col = 0; py += 19; }
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        if (mx >= left + 6 && mx < left + 266 && my >= top + 28 && my < top + PANEL_H - 38) {
            int max = Math.max(0, files.size() * ROW_H - (PANEL_H - 70));
            fileScroll = Math.max(0, Math.min(fileScroll - (int) (sy * ROW_H), max));
            return true;
        }
        return super.mouseScrolled(mx, my, sx, sy);
    }

    private void sendControl(boolean start) {
        java.util.List<java.util.UUID> ids = new ArrayList<>();
        net.minecraft.client.multiplayer.ClientPacketListener conn = net.minecraft.client.Minecraft.getInstance().getConnection();
        if (conn == null) return;
        for (String name : (recordAll ? onlinePlayers : chosenPlayers)) {
            var info = conn.getPlayerInfo(name);
            if (info != null) ids.add(info.getProfile().getId());
        }
        if (!ids.isEmpty() || recordAll) {
            net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                    new dev.worldreplay.nf1211.RecordControlPackets.RecordControl(start, ids));
        }
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_DELETE) { deleteSelected(); return true; }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean isPauseScreen() { return false; }
}

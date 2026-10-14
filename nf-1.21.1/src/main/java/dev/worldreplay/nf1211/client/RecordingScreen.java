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
 * WorldReplay 录制管理屏：O 键或 /worldreplay gui 打开。
 *
 * <p>左栏：录制文件列表（点击选中，DEL 删除，滚轮翻页）。
 * 右栏：录制设置——在线玩家多选（全选/清空）+ 开始/停止。</p>
 *
 * <p><b>自适应布局</b>（v0.1.1）：面板尺寸按窗口缩放——小窗口（GUI Scale 大）不再溢出屏幕。
 * 全部文案走语言文件（跟随游戏语言，中文客户端显示中文）。</p>
 */
public class RecordingScreen extends Screen {

    private int left, top;
    private int panelW, panelH;

    // ---- 文件列表 ----
    private final List<RecFile> files = new ArrayList<>();
    private int selectedFile = -1;
    private int fileScroll = 0;

    // ---- 录制目标（在线玩家）----
    private final List<String> chosenPlayers = new ArrayList<>();
    private List<String> onlinePlayers = new ArrayList<>();
    private boolean recordAll = false;

    private Button startBtn, stopBtn, policyBtn;

    /** 一条录制文件。 */
    record RecFile(String name, long size, long modified) {}

    public RecordingScreen() {
        super(Component.translatable("worldreplay.gui.title"));
    }

    @Override
    protected void init() {
        // 自适应：面板不超过窗口的 92%，最小 260x180
        panelW = Math.max(260, Math.min(520, (int) (this.width * 0.92)));
        panelH = Math.max(180, Math.min(250, (int) (this.height * 0.92)));
        left = (this.width - panelW) / 2;
        top = (this.height - panelH) / 2;

        int rightX = left + panelW - 216;   // 右栏按钮区
        int colW = Math.min(100, (panelW - 300) / 2);
        addRenderableWidget(Button.builder(tr("worldreplay.gui.selectall"), b -> {
            recordAll = true; chosenPlayers.clear(); chosenPlayers.addAll(onlinePlayers);
        }).bounds(rightX, top + 38, colW, 18).build());
        addRenderableWidget(Button.builder(tr("worldreplay.gui.clear"), b -> {
            chosenPlayers.clear(); recordAll = false;
        }).bounds(rightX + colW + 4, top + 38, colW, 18).build());
        startBtn = Button.builder(tr("worldreplay.gui.start"), b -> sendControl(true))
                .bounds(rightX, top + panelH - 30, 212, 20).build();
        addRenderableWidget(startBtn);
        stopBtn = Button.builder(tr("worldreplay.gui.stopall"), b -> sendControl(false))
                .bounds(rightX, top + panelH - 54, 212, 20).build();
        addRenderableWidget(stopBtn);
        addRenderableWidget(Button.builder(tr("worldreplay.gui.delete"), b -> deleteSelected())
                .bounds(left + 8, top + panelH - 30, 100, 20).build());
        addRenderableWidget(Button.builder(tr("worldreplay.gui.replay"), b -> replaySelected())
                .bounds(left + 114, top + panelH - 30, 100, 20).build());
        refresh();
    }

    private static Component tr(String key) { return Component.translatable(key); }

    private void refresh() {
        files.clear();
        try {
            Path dir = Path.of("worldreplay", "recordings");
            if (Files.isDirectory(dir)) {
                try (var s = Files.list(dir)) {
                    for (Path f : (Iterable<Path>) s.filter(p -> p.toString().endsWith(".wrp"))::iterator) {
                        files.add(new RecFile(f.getFileName().toString(), Files.size(f),
                                Files.getLastModifiedTime(f).toMillis()));
                    }
                }
                files.sort((a, b) -> Long.compare(b.modified(), a.modified()));
            }
        } catch (Exception ignored) {}
        onlinePlayers.clear();
        Minecraft mc = Minecraft.getInstance();
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
        g.fill(left, top, left + panelW, top + panelH, 0xF016181D);
        g.fill(left, top, left + panelW, top + 22, 0xFF21262D);
        g.drawString(font, this.title, left + 8, top + 7, 0xE6EDF3);

        // 左栏：文件列表（占面板左侧 55%）
        int listW = (int) (panelW * 0.55) - 8;
        int listTop = top + 28;
        int listBottom = top + panelH - 38;
        g.fill(left + 6, listTop, left + 6 + listW, listBottom, 0xFF1A1D23);
        int y = listTop + 3 - fileScroll;
        for (int i = 0; i < files.size(); i++) {
            if (y + 20 < listTop || y > listBottom) { y += 20; continue; }
            RecFile f = files.get(i);
            boolean hov = mouseX >= left + 6 && mouseX < left + 6 + listW && mouseY >= y && mouseY < y + 20;
            int bg = i == selectedFile ? 0xFF1F6FEB : (hov ? 0xFF292E36 : 0);
            if (bg != 0) g.fill(left + 6, y, left + 6 + listW, y + 19, bg);
            String size = f.size() < 1024 ? f.size() + "B" : (f.size() / 1024) + "KB";
            g.drawString(font, f.name(), left + 12, y + 6, i == selectedFile ? 0xFFFFFF : 0xC9D1D9);
            g.drawString(font, size, left + 6 + listW - 40, y + 6, 0x6E7681);
            y += 20;
        }
        if (files.isEmpty())
            g.drawString(font, tr("worldreplay.gui.empty"), left + 12, listTop + 6, 0x6E7681);

        // 右栏：录制设置
        int rx = left + 6 + listW + 12;
        g.drawString(font, tr("worldreplay.gui.recordwho"), rx, top + 28, 0x8B949E);
        // 玩家多选 chips（单列，宽度自适应右栏）
        int chipW = Math.min(200, panelW - (rx - left) - 10);
        int chipX = rx;
        int cy = top + 44;
        for (String p : onlinePlayers) {
            boolean on = recordAll || chosenPlayers.contains(p);
            int bgc = on ? 0xFF238636 : 0xFF292E36;
            g.fill(chipX, cy, chipX + chipW, cy + 16, bgc);
            g.drawString(font, p, chipX + 5, cy + 4, on ? 0xFFFFFF : 0xC9D1D9);
            cy += 19;
        }
        if (onlinePlayers.isEmpty())
            g.drawString(font, tr("worldreplay.gui.noplayers"), chipX, cy, 0x6E7681);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        // 文件列表点击
        int listW = (int) (panelW * 0.55) - 8;
        int listTop = top + 28;
        int listBottom = top + panelH - 38;
        int y = listTop + 3 - fileScroll;
        for (int i = 0; i < files.size(); i++) {
            if (mx >= left + 6 && mx < left + 6 + listW && my >= y && my < y + 20) {
                if (my < listBottom) { selectedFile = i; return true; }
            }
            y += 20;
        }
        // 玩家 chip 点击
        int rx = left + 6 + listW + 12;
        int chipW = Math.min(200, panelW - (rx - left) - 10);
        int cy = top + 44;
        for (String p : onlinePlayers) {
            if (mx >= rx && mx < rx + chipW && my >= cy && my < cy + 16) {
                if (recordAll) { chosenPlayers.addAll(onlinePlayers); chosenPlayers.remove(p); recordAll = false; }
                else if (chosenPlayers.contains(p)) chosenPlayers.remove(p);
                else chosenPlayers.add(p);
                return true;
            }
            cy += 19;
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        int listW = (int) (panelW * 0.55) - 8;
        if (mx >= left + 6 && mx < left + 6 + listW && my >= top + 28 && my < top + panelH - 38) {
            int max = Math.max(0, files.size() * 20 - (top + panelH - 38 - (top + 28)));
            fileScroll = Math.max(0, Math.min(fileScroll - (int) (sy * 20), max));
            return true;
        }
        return super.mouseScrolled(mx, my, sx, sy);
    }

    private void sendControl(boolean start) {
        java.util.List<java.util.UUID> ids = new ArrayList<>();
        net.minecraft.client.multiplayer.ClientPacketListener conn = Minecraft.getInstance().getConnection();
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

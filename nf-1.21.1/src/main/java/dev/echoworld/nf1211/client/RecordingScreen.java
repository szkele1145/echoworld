package dev.echoworld.nf1211.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * EchoWorld 录制管理屏：列出 .echoworld/recordings/ 的 .ecw 文件，
 * 点击选择，底部显示回放控制（下一迭代接入 ReplayEngine）。
 *
 * <p>视觉风格与 GlowTrack 一致：扁平深色 + 单像素边框。</p>
 */
public class RecordingScreen extends Screen {

    private static final int PANEL_W = 420;
    private static final int ROW_H = 22;

    private int left, top;
    private final List<RecEntry> entries = new ArrayList<>();
    private int selected = -1;
    private int scrollPx = 0;

    /** 一条录制记录。 */
    record RecEntry(String fileName, long sizeBytes, long modified) {}

    public RecordingScreen() {
        super(Component.literal("EchoWorld Recordings"));
    }

    @Override
    protected void init() {
        left = (this.width - PANEL_W) / 2;
        top = 40;
        refresh();
    }

    private void refresh() {
        entries.clear();
        try {
            Path dir = Path.of("echoworld", "recordings");
            if (!Files.isDirectory(dir)) return;
            try (var stream = Files.list(dir)) {
                for (Path f : (Iterable<Path>) stream.filter(p -> p.toString().endsWith(".ecw"))::iterator) {
                    entries.add(new RecEntry(f.getFileName().toString(), Files.size(f), Files.getLastModifiedTime(f).toMillis()));
                }
            }
            entries.sort((a, b) -> Long.compare(b.modified(), a.modified()));
        } catch (Exception ignored) {
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float pt) {
        super.render(g, mouseX, mouseY, pt);
        int panelH = Math.min(this.height - 90, 60 + entries.size() * ROW_H);
        // 面板底
        g.fill(left, top, left + PANEL_W, top + panelH, 0xF016181D);
        // 标题行
        g.fill(left, top, left + PANEL_W, top + 20, 0xFF21262D);
        g.drawString(font, "EchoWorld Recordings", left + 8, top + 6, 0xE6EDF3, false);
        g.drawString(font, entries.size() + " files", left + PANEL_W - 60, top + 6, 0x6E7681, false);

        // 列表
        int y = top + 24;
        for (int i = 0; i < entries.size(); i++) {
            RecEntry r = entries.get(i);
            boolean hovered = mouseX >= left && mouseX < left + PANEL_W && mouseY >= y && mouseY < y + ROW_H;
            int bg = i == selected ? 0xFF1F6FEB : (hovered ? 0xFF292E36 : 0xFF1A1D23);
            g.fill(left + 2, y, left + PANEL_W - 2, y + ROW_H - 1, bg);
            String size = r.sizeBytes() < 1024 ? r.sizeBytes() + "B" : (r.sizeBytes() / 1024) + "KB";
            g.drawString(font, r.fileName(), left + 10, y + 6, i == selected ? 0xFFFFFF : 0xC9D1D9, false);
            g.drawString(font, size, left + PANEL_W - 50, y + 6, 0x6E7681, false);
            y += ROW_H;
        }
        if (entries.isEmpty()) {
            g.drawString(font, "No recordings yet", left + 10, y + 6, 0x6E7681, false);
        }
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        int y = top + 24;
        for (int i = 0; i < entries.size(); i++) {
            if (mx >= left && mx < left + PANEL_W && my >= y && my < y + ROW_H) {
                selected = i;
                return true;
            }
            y += ROW_H;
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean isPauseScreen() { return false; }
}

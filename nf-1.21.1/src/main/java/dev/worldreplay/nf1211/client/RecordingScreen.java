package dev.worldreplay.nf1211.client;

import dev.worldreplay.nf1211.RecordListPackets;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * WorldReplay 录制管理屏：O 键或 /echoreplay gui 打开。
 *
 * <p>左栏：录制文件列表（点击选中，DEL 删除，滚轮翻页）。
 * 右栏：录制设置——在线玩家多选（全选/清空）+ 开始/停止。</p>
 *
 * <p><b>v0.2.1 Bug 1 修复</b>：录制文件落盘在<b>服务端</b>目录，客户端本地没有。
 * 文件列表改由服务端扫描后经 S2C {@link RecordListPackets.RecordList} 下发，
 * 本屏从包数据初始化，不再扫描客户端工作目录；删除/回放按钮改发 C2S
 * {@link RecordListPackets.RequestRecording}，由服务端执行（回放时服务端把
 * 文件数据分片经 {@link RecordListPackets.RecordingData} 回传，客户端解码后
 * 进入 {@link ReplayVisualizer}）。</p>
 *
 * <p><b>v0.2.1 Bug 2 修复</b>：右栏按钮不再硬编码 {@code panelW-216} 偏移和
 * 212 宽度——面板最小 260px 时按钮会画出面板。现在右栏几何全部按
 * {@code rightW}（右栏实际可用宽度）推导：宽则保持原布局，窄则按钮自适应
 * 缩窄（Select All/Clear 过窄时纵向堆叠），任何面板宽度下都不溢出。
 * 全部文案走语言文件（跟随游戏语言，中文客户端显示中文）。</p>
 */
public class RecordingScreen extends Screen {

    private int left, top;
    private int panelW, panelH;
    /** Bug 2：右栏起点 X 与右栏内容可用宽度（自适应推导，不硬编码）。 */
    private int rightX, rightW;
    /** 左栏文件列表宽度。 */
    private int listW;

    // ---- 文件列表（来自服务端 RecordList 包）----
    private final List<RecordListPackets.RecordListEntry> files = new ArrayList<>();
    private int selectedFile = -1;
    private int fileScroll = 0;

    // ---- 录制目标（在线玩家）----
    private final List<String> chosenPlayers = new ArrayList<>();
    private List<String> onlinePlayers = new ArrayList<>();
    private boolean recordAll = false;

    private Button startBtn, stopBtn;

    /** 从服务端下发的列表构造（唯一入口：ClientPayloadHandlers 开屏）。 */
    public RecordingScreen(List<RecordListPackets.RecordListEntry> entries) {
        super(Component.translatable("worldreplay.gui.title"));
        if (entries != null) files.addAll(entries);
    }

    /** 服务端重发列表时刷新（删除后、gui 分支重开）。保留同名选中项。 */
    public void updateFiles(List<RecordListPackets.RecordListEntry> entries) {
        String selected = selectedFile >= 0 && selectedFile < files.size()
                ? files.get(selectedFile).fileName() : null;
        files.clear();
        if (entries != null) files.addAll(entries);
        selectedFile = -1;
        for (int i = 0; i < files.size(); i++) {
            if (files.get(i).fileName().equals(selected)) { selectedFile = i; break; }
        }
        fileScroll = 0;
    }

    @Override
    protected void init() {
        // 自适应：面板不超过窗口的 92%，最小 260x180
        panelW = Math.max(260, Math.min(520, (int) (this.width * 0.92)));
        panelH = Math.max(180, Math.min(250, (int) (this.height * 0.92)));
        left = (this.width - panelW) / 2;
        top = (this.height - panelH) / 2;

        // Bug 2 修复：右栏几何全部按实际宽度推导（原 rightX = left+panelW-216
        // 在 panelW=260 时算出 left+44，按钮宽 212 直接画出面板——已废弃）
        listW = (int) (panelW * 0.55) - 8;
        rightX = left + 6 + listW + 12;            // 右栏起点
        rightW = panelW - (rightX - left) - 8;     // 右栏可用宽度（右侧留 8 边距）
        int bottomY = top + panelH - 30;

        // Select All / Clear：够宽则并排，太窄则纵向堆叠（文字放不下时不硬塞）
        int colW = Math.min(100, (rightW - 4) / 2);
        if (colW >= 50) {
            addRenderableWidget(Button.builder(tr("worldreplay.gui.selectall"), b -> {
                recordAll = true; chosenPlayers.clear(); chosenPlayers.addAll(onlinePlayers);
            }).bounds(rightX, top + 38, colW, 18).build());
            addRenderableWidget(Button.builder(tr("worldreplay.gui.clear"), b -> {
                chosenPlayers.clear(); recordAll = false;
            }).bounds(rightX + colW + 4, top + 38, colW, 18).build());
        } else {
            addRenderableWidget(Button.builder(tr("worldreplay.gui.selectall"), b -> {
                recordAll = true; chosenPlayers.clear(); chosenPlayers.addAll(onlinePlayers);
            }).bounds(rightX, top + 38, rightW, 18).build());
            addRenderableWidget(Button.builder(tr("worldreplay.gui.clear"), b -> {
                chosenPlayers.clear(); recordAll = false;
            }).bounds(rightX, top + 60, rightW, 18).build());
        }

        // Start / Stop：宽度自适应右栏（原硬编码 212 会在窄面板上画出面板外）
        int actionW = Math.min(212, rightW);
        startBtn = Button.builder(tr("worldreplay.gui.start"), b -> sendControl(true))
                .bounds(rightX, bottomY, actionW, 20).build();
        addRenderableWidget(startBtn);
        stopBtn = Button.builder(tr("worldreplay.gui.stopall"), b -> sendControl(false))
                .bounds(rightX, bottomY - 24, actionW, 20).build();
        addRenderableWidget(stopBtn);

        // 删除 / 回放：限制在左栏宽度内自适应（原 100+100 固定宽在窄面板上
        // 会与右栏底部按钮重叠），两按钮并排恰好铺满左栏底边
        int fileBtnW = Math.max(40, (listW - 6) / 2);
        addRenderableWidget(Button.builder(tr("worldreplay.gui.delete"), b -> requestSelected(
                RecordListPackets.RequestRecording.ACTION_DELETE))
                .bounds(left + 8, bottomY, fileBtnW, 20).build());
        addRenderableWidget(Button.builder(tr("worldreplay.gui.replay"), b -> requestSelected(
                RecordListPackets.RequestRecording.ACTION_REPLAY))
                .bounds(left + 12 + fileBtnW, bottomY, fileBtnW, 20).build());

        refreshPlayers();
    }

    private static Component tr(String key) { return Component.translatable(key); }

    /** 在线玩家列表（客户端本地信息，无需网络往返）。 */
    private void refreshPlayers() {
        onlinePlayers = new ArrayList<>();
        Minecraft mc = Minecraft.getInstance();
        if (mc.getConnection() != null) {
            mc.getConnection().getOnlinePlayers().forEach(p -> onlinePlayers.add(p.getProfile().getName()));
        }
    }

    /** Bug 1 修复：删除/回放都改为 C2S 请求，由服务端在其目录里执行。 */
    private void requestSelected(int action) {
        if (selectedFile < 0 || selectedFile >= files.size()) return;
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                new RecordListPackets.RequestRecording(files.get(selectedFile).fileName(), action));
        if (action == RecordListPackets.RequestRecording.ACTION_DELETE) {
            selectedFile = -1;   // 选中项由服务端重发列表后按文件名恢复
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float pt) {
        super.render(g, mouseX, mouseY, pt);
        // 面板
        g.fill(left, top, left + panelW, top + panelH, 0xF016181D);
        g.fill(left, top, left + panelW, top + 22, 0xFF21262D);
        g.drawString(font, this.title, left + 8, top + 7, 0xE6EDF3);

        // 左栏：文件列表（占面板左侧 55%）
        int listTop = top + 28;
        int listBottom = top + panelH - 38;
        g.fill(left + 6, listTop, left + 6 + listW, listBottom, 0xFF1A1D23);
        int y = listTop + 3 - fileScroll;
        for (int i = 0; i < files.size(); i++) {
            if (y + 20 < listTop || y > listBottom) { y += 20; continue; }
            RecordListPackets.RecordListEntry f = files.get(i);
            boolean hov = mouseX >= left + 6 && mouseX < left + 6 + listW && mouseY >= y && mouseY < y + 20;
            int bg = i == selectedFile ? 0xFF1F6FEB : (hov ? 0xFF292E36 : 0);
            if (bg != 0) g.fill(left + 6, y, left + 6 + listW, y + 19, bg);
            String size = f.size() < 1024 ? f.size() + "B" : (f.size() / 1024) + "KB";
            g.drawString(font, f.fileName(), left + 12, y + 6, i == selectedFile ? 0xFFFFFF : 0xC9D1D9);
            g.drawString(font, size, left + 6 + listW - 40, y + 6, 0x6E7681);
            y += 20;
        }
        if (files.isEmpty())
            g.drawString(font, tr("worldreplay.gui.empty"), left + 12, listTop + 6, 0x6E7681);

        // 右栏：录制设置
        g.drawString(font, tr("worldreplay.gui.recordwho"), rightX, top + 28, 0x8B949E);
        // 玩家多选 chips（单列，宽度自适应右栏；底部截断避免压到 Start/Stop 按钮）
        int chipW = Math.min(200, rightW);
        int chipsBottom = top + panelH - 60;
        int cy = top + 44;
        for (String p : onlinePlayers) {
            if (cy + 16 > chipsBottom) break;   // 空间不够就截断（不画出按钮区）
            boolean on = recordAll || chosenPlayers.contains(p);
            int bgc = on ? 0xFF238636 : 0xFF292E36;
            g.fill(rightX, cy, rightX + chipW, cy + 16, bgc);
            g.drawString(font, p, rightX + 5, cy + 4, on ? 0xFFFFFF : 0xC9D1D9);
            cy += 19;
        }
        if (onlinePlayers.isEmpty())
            g.drawString(font, tr("worldreplay.gui.noplayers"), rightX, cy, 0x6E7681);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        // 文件列表点击
        int listTop = top + 28;
        int listBottom = top + panelH - 38;
        int y = listTop + 3 - fileScroll;
        for (int i = 0; i < files.size(); i++) {
            if (mx >= left + 6 && mx < left + 6 + listW && my >= y && my < y + 20) {
                if (my < listBottom) { selectedFile = i; return true; }
            }
            y += 20;
        }
        // 玩家 chip 点击（与渲染一致：同样截断，避免点到按钮区以外的 chip）
        int chipW = Math.min(200, rightW);
        int chipsBottom = top + panelH - 60;
        int cy = top + 44;
        for (String p : onlinePlayers) {
            if (cy + 16 > chipsBottom) break;
            if (mx >= rightX && mx < rightX + chipW && my >= cy && my < cy + 16) {
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
        int listTop = top + 28;
        int listBottom = top + panelH - 38;
        if (mx >= left + 6 && mx < left + 6 + listW && my >= listTop && my < listBottom) {
            int max = Math.max(0, files.size() * 20 - (listBottom - listTop));
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
        if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_DELETE) {
            requestSelected(RecordListPackets.RequestRecording.ACTION_DELETE);
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean isPauseScreen() { return false; }
}

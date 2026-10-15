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
 * WorldReplay 录制管理屏：仅由命令 {@code /echoreplay gui} 打开（v0.1.9 起无 O 键）。
 *
 * <p>左栏：录制文件列表（点击选中，Shift+DEL 删除，滚轮翻页）。
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

    // ---- v0.1.9 重命名：完全自绘输入态（不依赖 EditBox widget 的渲染顺序）----
    /** 是否处于“输入新名字”模式。 */
    private boolean renaming;
    /** 输入缓冲（提交前只存在于客户端）。 */
    private String renameText = "";
    /** 客户端输入上限（字符数）；服务端另有更宽松的 96 上限并会清洗非法字符。 */
    private static final int RENAME_MAX = 64;

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

        // 删除 / 回放 / 重命名：限制在左栏宽度内自适应（原 100+100 固定宽在窄面板上
        // 会与右栏底部按钮重叠），三按钮并排恰好铺满左栏底边（v0.1.9 加入重命名）
        int fileBtnW = Math.max(40, (listW - 8) / 3);
        addRenderableWidget(Button.builder(tr("worldreplay.gui.delete"), b -> requestSelected(
                RecordListPackets.RequestRecording.ACTION_DELETE))
                .bounds(left + 8, bottomY, fileBtnW, 20).build());
        addRenderableWidget(Button.builder(tr("worldreplay.gui.replay"), b -> requestSelected(
                RecordListPackets.RequestRecording.ACTION_REPLAY))
                .bounds(left + 10 + fileBtnW, bottomY, fileBtnW, 20).build());
        addRenderableWidget(Button.builder(tr("worldreplay.gui.rename"), b -> beginRename())
                .bounds(left + 12 + fileBtnW * 2, bottomY, fileBtnW, 20).build());

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

    /** 进入重命名输入态：预填当前文件名去掉 .wrp 后缀（便于局部修改）。 */
    private void beginRename() {
        if (selectedFile < 0 || selectedFile >= files.size()) return;
        String n = files.get(selectedFile).fileName();
        if (n.endsWith(".wrp")) n = n.substring(0, n.length() - 4);
        renameText = n;
        renaming = true;
    }

    /** 提交重命名：发 C2S，随后由服务端重发列表刷新（失败也只是名字没变）。 */
    private void submitRename() {
        if (selectedFile < 0 || selectedFile >= files.size()) { renaming = false; return; }
        String target = renameText.trim();
        String current = files.get(selectedFile).fileName();
        String currentBase = current.endsWith(".wrp") ? current.substring(0, current.length() - 4) : current;
        renaming = false;
        if (target.isEmpty() || target.equals(currentBase)) return;   // 空名/未改动：直接退出
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                new RecordListPackets.RequestRename(current, target));
        selectedFile = -1;
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
            // 右侧元信息：事件数 + 大小（事件数是回放价值的第一信号）
            String events = f.events() + "ev";
            String meta = events + " " + size;
            g.drawString(font, meta, left + 6 + listW - 4 - font.width(meta), y + 6, 0x6E7681);
            y += 20;
        }
        if (files.isEmpty())
            g.drawString(font, tr("worldreplay.gui.empty"), left + 12, listTop + 6, 0x6E7681);

        // v0.1.9：重命名输入态——在左栏底部压一条输入框（自绘，避免 widget 增删导致闪烁）
        if (renaming) {
            int boxY = listBottom - 22;
            g.fill(left + 6, boxY, left + 6 + listW, boxY + 20, 0xFF0D1117);
            g.fill(left + 6, boxY, left + 6 + listW, boxY + 1, 0xFF1F6FEB);
            g.fill(left + 6, boxY + 19, left + 6 + listW, boxY + 20, 0xFF1F6FEB);
            String shown = renameText == null ? "" : renameText;
            // 超宽时右侧截断显示（光标永远贴输入末尾）
            while (font.width(shown) > listW - 12 && shown.length() > 1) {
                shown = shown.substring(1);
            }
            g.drawString(font, shown, left + 10, boxY + 6, 0xFFFFFF);
            boolean caretOn = (System.currentTimeMillis() / 500L) % 2L == 0L;
            if (caretOn) {
                int cx = left + 10 + font.width(shown) + 1;
                g.fill(cx, boxY + 4, cx + 1, boxY + 16, 0xFFFFFFFF);
            }
            g.drawString(font, tr("worldreplay.gui.renamehint"), left + 8, boxY - 12, 0x8B949E);
        }

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
        // v0.1.9：重命名输入态下，点击输入框内=继续编辑；点击别处=提交并退出
        if (renaming) {
            int boxY = top + panelH - 38 - 22;
            boolean inBox = mx >= left + 6 && mx < left + 6 + listW && my >= boxY && my < boxY + 20;
            if (!inBox) submitRename();
            return true;
        }
        // v0.1.9 Bug 修复：以下自绘热区只响应左键。此前不判 button，
        // 右键点列表行会直接选中、右键点玩家 chip 会静默改录制对象，
        // 都是没反馈的误操作；右键继续交给 super 处理（原版无行为，安全）。
        if (button != 0) return super.mouseClicked(mx, my, button);
        // 文件列表点击（可见区裁剪：与渲染的 listTop/listBottom 保持一致，避免点中滚出视野的行）
        int listTop = top + 28;
        int listBottom = top + panelH - 38;
        int y = listTop + 3 - fileScroll;
        for (int i = 0; i < files.size(); i++) {
            // 可见区裁剪（与 render 一致）：滚出视野的行不可点，否则点面板标题会选到隐藏行
            if (y + 20 >= listTop && y < listBottom
                    && mx >= left + 6 && mx < left + 6 + listW
                    && my >= y && my < y + 20 && my >= listTop && my < listBottom) {
                selectedFile = i;
                return true;
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
    public boolean charTyped(char c, int modifiers) {
        if (renaming) {
            // 只收可打印字符，且过滤掉路径分隔符（服务端还会再清洗一次，这里先给即时反馈）
            if (c >= 0x20 && c != 0x7F && c != '/' && c != '\\' && c != ':' && c != '*'
                    && c != '?' && c != '"' && c != '<' && c != '>' && c != '|'
                    && renameText.length() < RENAME_MAX) {
                renameText += c;
            }
            return true;
        }
        return super.charTyped(c, modifiers);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // v0.1.9：重命名输入态优先吃掉所有按键（DEL 在此处是删字符，不是删文件）
        if (renaming) {
            switch (keyCode) {
                case org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE -> { renaming = false; return true; }
                case org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER, org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ENTER -> {
                    submitRename();
                    return true;
                }
                case org.lwjgl.glfw.GLFW.GLFW_KEY_BACKSPACE -> {
                    if (!renameText.isEmpty()) renameText = renameText.substring(0, renameText.length() - 1);
                    return true;
                }
                case org.lwjgl.glfw.GLFW.GLFW_KEY_DELETE -> {
                    renameText = "";
                    return true;
                }
                default -> { return true; }   // 其余按键（含 Ctrl 组合）一律吞掉，防止误触界面控件
            }
        }
        if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_DELETE) {
            if ((modifiers & org.lwjgl.glfw.GLFW.GLFW_MOD_SHIFT) != 0) {
                requestSelected(RecordListPackets.RequestRecording.ACTION_DELETE);   // Shift+DEL 才真删（防手滑）
            } else {
                Minecraft mc = Minecraft.getInstance();
                if (mc.player != null) {
                    mc.player.displayClientMessage(tr("worldreplay.gui.delhint"), true);
                }
            }
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean isPauseScreen() { return false; }
}

package dev.worldreplay.fabric1201.client;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.screen.ScreenTexts;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.List;

/**
 * 录制库 GUI（客户端 Fabric 1.20.1）。
 *
 * <p>收到服务端 {@code record_list} 后显示录制文件列表；点击条目选中，
 * 支持回放/删除选中项，滚轮滚动列表。</p>
 */
public class RecordingScreen extends Screen {

    private static List<ClientPayloadHandlers.Entry> ENTRIES = List.of();
    private static int scrollOffset = 0;
    /** 当前选中条目在完整列表中的索引，-1 表示未选中。 */
    private static int selectedIndex = -1;

    /** 每页可见条目数。 */
    private static final int VISIBLE_ROWS = 12;
    /** 列表区域起始 Y。 */
    private static final int LIST_TOP = 60;
    /** 每行高度。 */
    private static final int ROW_HEIGHT = 18;

    public RecordingScreen() {
        super(Text.literal("WorldReplay 录制库"));
    }

    /** 供 ClientPayloadHandlers 在收到列表包时刷新条目。 */
    static void setEntries(List<ClientPayloadHandlers.Entry> entries) {
        ENTRIES = entries;
        // 列表变化后修正选中索引与滚动偏移
        if (selectedIndex >= ENTRIES.size()) {
            selectedIndex = ENTRIES.size() - 1;
        }
        scrollOffset = Math.max(0, Math.min(scrollOffset, Math.max(0, ENTRIES.size() - VISIBLE_ROWS)));
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // 点击列表条目以选中（左键）
        if (button == 0 && mouseX >= 30 && mouseX <= width - 30) {
            int row = (int) ((mouseY - LIST_TOP) / ROW_HEIGHT);
            if (row >= 0 && row < VISIBLE_ROWS) {
                int idx = scrollOffset + row;
                if (idx >= 0 && idx < ENTRIES.size() && mouseY >= LIST_TOP && (mouseY - LIST_TOP) % ROW_HEIGHT < 12) {
                    selectedIndex = idx;
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    protected void init() {
        // 刷新按钮：重新请求列表
        addDrawableChild(ButtonWidget.builder(Text.literal("刷新"), btn -> {
            ClientPayloadHandlers.requestRecordList(client);
        }).dimensions(width / 2 - 100, height - 40, 200, 20).build());

        // 回放按钮：作用于当前选中条目
        addDrawableChild(ButtonWidget.builder(Text.literal("回放选中"), btn -> {
            if (selectedIndex >= 0 && selectedIndex < ENTRIES.size()) {
                ClientPayloadHandlers.requestRecording(client, ENTRIES.get(selectedIndex).fileName, RequestRecordingAction.REPLAY.value);
                client.setScreen(null);
            }
        }).dimensions(width / 2 - 150, height - 80, 90, 20).build());

        // 删除按钮：作用于当前选中条目
        addDrawableChild(ButtonWidget.builder(Text.literal("删除选中"), btn -> {
            if (selectedIndex >= 0 && selectedIndex < ENTRIES.size()) {
                ClientPayloadHandlers.requestRecording(client, ENTRIES.get(selectedIndex).fileName, RequestRecordingAction.DELETE.value);
                client.setScreen(null);
            }
        }).dimensions(width / 2 + 60, height - 80, 90, 20).build());

        addDrawableChild(ButtonWidget.builder(ScreenTexts.BACK, btn -> client.setScreen(null))
                .dimensions(width / 2 - 100, height - 28, 200, 20).build());
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackground(context);
        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, 20, 0xFFFFFF);
        int y = LIST_TOP;
        for (int i = scrollOffset; i < Math.min(ENTRIES.size(), scrollOffset + VISIBLE_ROWS); i++) {
            ClientPayloadHandlers.Entry e = ENTRIES.get(i);
            boolean selected = (i == selectedIndex);
            Text line = Text.literal((selected ? "▶ " : "  ") + e.fileName)
                    .formatted(selected ? Formatting.AQUA : Formatting.GRAY)
                    .append(Text.literal("  " + e.size + " B").formatted(Formatting.DARK_GRAY));
            context.drawTextWithShadow(textRenderer, line, 30, y, selected ? 0x55FFFF : 0xFFFFFF);
            y += ROW_HEIGHT;
        }
        if (ENTRIES.isEmpty()) {
            context.drawCenteredTextWithShadow(textRenderer,
                    Text.literal("无录制文件").formatted(Formatting.YELLOW), width / 2, height / 2, 0xFFFF00);
        }
        // 底部状态栏：当前选中文件名
        if (selectedIndex >= 0 && selectedIndex < ENTRIES.size()) {
            context.drawCenteredTextWithShadow(textRenderer,
                    Text.literal("已选: " + ENTRIES.get(selectedIndex).fileName).formatted(Formatting.AQUA),
                    width / 2, height - 55, 0x55FFFF);
        }
        super.render(context, mouseX, mouseY, delta);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        if (ENTRIES.size() > VISIBLE_ROWS) {
            // amount > 0 向上滚（内容向前），amount < 0 向下滚（内容向后）
            scrollOffset = Math.max(0, Math.min(ENTRIES.size() - VISIBLE_ROWS, scrollOffset - (int) amount));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, amount);
    }
}

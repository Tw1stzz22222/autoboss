package com.example.autoboss.gui;

import com.example.autoboss.AutoBossConfig;
import com.example.autoboss.automation.AutoBossController;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.lwjgl.glfw.GLFW;

/** B 键菜单。目前只有总开关、调试、副本组和状态展示，后续再加功能。 */
public class AutoBossMenuScreen extends Screen {

    private static final int PANEL_W = 300;
    private static final int PANEL_H = 236;

    private int panelX;
    private int panelY;

    public AutoBossMenuScreen() {
        super(Text.literal("AutoBoss 自动打头目"));
    }

    @Override
    protected void init() {
        panelX = (this.width - PANEL_W) / 2;
        panelY = (this.height - PANEL_H) / 2;

        int bw = 220;
        int bh = 18;
        int bx = (this.width - bw) / 2;
        int by = panelY + 32;

        addDrawableChild(ButtonWidget.builder(detectLabel(), b -> {
            AutoBossConfig.autoDetect = !AutoBossConfig.autoDetect;
            b.setMessage(detectLabel());
        }).dimensions(bx, by, bw, bh).build());

        addDrawableChild(ButtonWidget.builder(debugLabel(), b -> {
            AutoBossConfig.debugLog = !AutoBossConfig.debugLog;
            b.setMessage(debugLabel());
        }).dimensions(bx, by + 22, bw, bh).build());

        addDrawableChild(ButtonWidget.builder(groupLabel(), b -> {
            AutoBossConfig.copyGroup = "A组副本".equals(AutoBossConfig.copyGroup) ? "B组副本" : "A组副本";
            b.setMessage(groupLabel());
        }).dimensions(bx, by + 44, bw, bh).build());

        addDrawableChild(ButtonWidget.builder(filterLabel(), b -> {
            AutoBossConfig.filterRewardMessages = !AutoBossConfig.filterRewardMessages;
            b.setMessage(filterLabel());
        }).dimensions(bx, by + 66, bw, bh).build());

        addDrawableChild(ButtonWidget.builder(Text.literal("用最近一次识别重试"), b ->
                AutoBossController.retryLast(this.client)).dimensions(bx, by + 88, bw, bh).build());

        addDrawableChild(ButtonWidget.builder(Text.literal("§c终止当前流程"), b ->
                AutoBossController.abort(this.client, "手动终止")).dimensions(bx, by + 110, bw, bh).build());

        addDrawableChild(ButtonWidget.builder(Text.literal("关闭"), b -> this.close())
                .dimensions(bx, by + 132, bw, bh).build());
    }

    private static Text detectLabel() {
        return Text.literal("自动识别: " + onOff(AutoBossConfig.autoDetect));
    }

    private static Text debugLabel() {
        return Text.literal("调试日志: " + onOff(AutoBossConfig.debugLog));
    }

    private static Text filterLabel() {
        return Text.literal("屏蔽奖励消息: " + onOff(AutoBossConfig.filterRewardMessages));
    }

    private static Text groupLabel() {
        return Text.literal("副本组: §b" + AutoBossConfig.copyGroup);
    }

    private static String onOff(boolean value) {
        return value ? "§a开" : "§c关";
    }

    @Override
    public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
        context.fill(0, 0, this.width, this.height, 0x66000000);
        context.fill(panelX, panelY, panelX + PANEL_W, panelY + PANEL_H, 0xCC101418);
        context.drawBorder(panelX, panelY, PANEL_W, PANEL_H, 0xFF2BB0A8);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);

        context.drawCenteredTextWithShadow(this.textRenderer,
                Text.literal("AutoBoss · 自动打头目").formatted(Formatting.BOLD, Formatting.AQUA),
                this.width / 2, panelY + 12, 0xFFFFFF);

        context.drawCenteredTextWithShadow(this.textRenderer,
                Text.literal("状态: §b" + AutoBossController.statusText()),
                this.width / 2, panelY + 200, 0xFFFFFF);
        context.drawCenteredTextWithShadow(this.textRenderer,
                Text.literal("最近识别: §7" + AutoBossController.lastDetectedText()),
                this.width / 2, panelY + 214, 0xFFFFFF);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_EQUAL) {
            AutoBossController.forceStop(this.client);
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }
}

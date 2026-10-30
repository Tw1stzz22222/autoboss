package com.example.autoboss;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AutoBossMod implements ClientModInitializer {

    // --- 玩家独立聊天悬浮框数据 ---
    private static final List<Text> playerChatHistory = Collections.synchronizedList(new ArrayList<>());
    private static final int MAX_PLAYER_CHAT_LINES = 10;

    // --- 头目公告悬浮框数据 ---
    private static final List<String> bossNoticeHistory = Collections.synchronizedList(new ArrayList<>());
    private static final int MAX_BOSS_NOTICE_LINES = 16;

    private static boolean filterRewardMessages = true; // 是否开启 [仓库] 屏蔽

    @Override
    public void onInitializeClient() {
        System.out.println(">>> [AutoBoss] 独立 mod 已加载 <<<");

        ClientReceiveMessageEvents.ALLOW_GAME.register((text, overlay) -> {
            String rawMsg = text.getString();
            String cleanMsg = rawMsg.replaceAll("§[0-9a-fk-orA-FK-OR]", "");

            boolean isPlayerChat = cleanMsg.matches(".*\\[LV\\.\\d+\\].*") || cleanMsg.contains("↪");

            if (isPlayerChat) {
                playerChatHistory.add(text);
                if (playerChatHistory.size() > MAX_PLAYER_CHAT_LINES) {
                    playerChatHistory.remove(0);
                }
            }

            if (cleanMsg.contains("5分钟内无人挑战头目将隐匿虚空")) {
                bossNoticeHistory.add(extractBossSummary(cleanMsg));
                if (bossNoticeHistory.size() > MAX_BOSS_NOTICE_LINES) {
                    bossNoticeHistory.remove(0);
                }
            }

            if (cleanMsg.contains("头目镇压通知")) {
                bossNoticeHistory.add(extractBossSuppressed(cleanMsg));
                if (bossNoticeHistory.size() > MAX_BOSS_NOTICE_LINES) {
                    bossNoticeHistory.remove(0);
                }
            }

            if (filterRewardMessages && (cleanMsg.contains("[仓库]") || cleanMsg.contains("获得了") || cleanMsg.contains("给予了"))) {
                return false;
            }

            return true;
        });

        HudRenderCallback.EVENT.register((drawContext, tickDelta) -> {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player == null || client.options.hudHidden) return;

            int screenWidth = client.getWindow().getScaledWidth();

            int chatWidth = 420;
            int chatX = screenWidth - chatWidth - 5;
            int chatY = 10;
            int displayLines = playerChatHistory.isEmpty() ? 1 : playerChatHistory.size();
            int chatHeight = 14 + displayLines * 10;

            drawContext.fill(chatX - 4, chatY - 4, chatX + chatWidth, chatY + chatHeight, 160 << 24);
            drawContext.drawTextWithShadow(client.textRenderer, Text.literal("§b§l💬 玩家聊天频道"), chatX, chatY, 0xFFFFFF);

            if (playerChatHistory.isEmpty()) {
                drawContext.drawTextWithShadow(client.textRenderer, Text.literal("§7(等待玩家发言中...)"), chatX, chatY + 12, 0xFFFFFF);
            } else {
                for (int i = 0; i < playerChatHistory.size(); i++) {
                    Text msgText = playerChatHistory.get(i);
                    drawContext.drawTextWithShadow(client.textRenderer, msgText, chatX, chatY + 12 + (i * 10), 0xFFFFFF);
                }
            }

            int bossNoticeWidth = 400;
            int bossNoticeX = screenWidth - bossNoticeWidth - 5;
            int bossNoticeY = chatY + chatHeight + 6;
            int bossDisplayLines = bossNoticeHistory.isEmpty() ? 1 : bossNoticeHistory.size();
            int bossNoticeHeight = 14 + bossDisplayLines * 10;

            drawContext.fill(bossNoticeX - 4, bossNoticeY - 4, bossNoticeX + bossNoticeWidth, bossNoticeY + bossNoticeHeight, 160 << 24);
            drawContext.drawTextWithShadow(client.textRenderer, Text.literal("§c§l💀 头目公告"), bossNoticeX, bossNoticeY, 0xFFFFFF);

            if (bossNoticeHistory.isEmpty()) {
                drawContext.drawTextWithShadow(client.textRenderer, Text.literal("§7(等待头目公告中...)"), bossNoticeX, bossNoticeY + 12, 0xFFFFFF);
            } else {
                for (int i = 0; i < bossNoticeHistory.size(); i++) {
                    String msgStr = bossNoticeHistory.get(i);
                    drawContext.drawTextWithShadow(client.textRenderer, Text.literal(msgStr), bossNoticeX, bossNoticeY + 12 + (i * 10), 0xFFFFFF);
                }
            }
        });
    }

    // 从头目公告原文中提取 线路 / 层数 / 坐标，压缩成一行短摘要
    private static String extractBossSummary(String msg) {
        StringBuilder sb = new StringBuilder();

        Matcher line = Pattern.compile("(\\d+)线").matcher(msg);
        if (line.find()) sb.append(line.group(1)).append("线");

        Matcher floor = Pattern.compile("F\\s*(\\d+)").matcher(msg);
        if (floor.find()) {
            if (sb.length() > 0) sb.append(" ");
            sb.append("F").append(floor.group(1));
        }

        StringBuilder coord = new StringBuilder();
        Matcher x = Pattern.compile("(?i)x\\s*[:：]\\s*(-?\\d+)").matcher(msg);
        Matcher y = Pattern.compile("(?i)y\\s*[:：]\\s*(-?\\d+)").matcher(msg);
        Matcher z = Pattern.compile("(?i)z\\s*[:：]\\s*(-?\\d+)").matcher(msg);
        if (x.find()) coord.append("x:").append(x.group(1));
        if (y.find()) coord.append(coord.length() > 0 ? ",y:" : "y:").append(y.group(1));
        if (z.find()) coord.append(coord.length() > 0 ? ",z:" : "z:").append(z.group(1));
        if (coord.length() > 0) {
            if (sb.length() > 0) sb.append(" ");
            sb.append(coord);
        }

        if (sb.length() == 0) {
            return msg.length() > 24 ? msg.substring(0, 24) + "…" : msg;
        }
        return sb.toString();
    }

    // 从头目镇压通知中提取"已被谁镇压"
    private static String extractBossSuppressed(String msg) {
        Matcher m = Pattern.compile("已被(.{1,24}?)镇压").matcher(msg);
        if (m.find()) {
            return "已被" + m.group(1).trim() + "镇压";
        }
        return msg.length() > 24 ? msg.substring(0, 24) + "…" : msg;
    }
}

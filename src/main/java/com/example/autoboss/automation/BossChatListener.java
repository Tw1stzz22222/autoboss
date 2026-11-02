package com.example.autoboss.automation;

import com.example.autoboss.AutoBossConfig;
import com.example.autoboss.AutoBossMod;
import net.minecraft.client.MinecraftClient;

/** 把公屏消息交给解析器，解析成功后切回客户端线程启动流程。 */
public final class BossChatListener {

    private static long lastUnparsedLogAt;

    private BossChatListener() {
    }

    public static void onGameMessage(String raw, MinecraftClient client) {
        BossTarget target = BossParser.parse(raw);

        if (target == null) {
            // 刷屏时未识别日志也限流，避免日志被灌满。
            long now = System.currentTimeMillis();
            if (AutoBossConfig.debugLog && BossParser.looksBossRelated(raw) && now - lastUnparsedLogAt >= 1000L) {
                lastUnparsedLogAt = now;
                AutoBossMod.LOG.info("[autoboss] 未识别公告: {}", BossParser.clean(raw));
            }
            return;
        }

        if (AutoBossConfig.debugLog) {
            AutoBossMod.LOG.info("[autoboss] 识别公告: {} -> {}", BossParser.clean(raw), target.describe());
        }

        if (client == null) {
            return;
        }
        client.execute(() -> AutoBossController.onDetected(target));
    }
}

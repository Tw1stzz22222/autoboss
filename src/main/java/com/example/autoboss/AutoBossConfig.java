package com.example.autoboss;

/** 全局配置。全部为内存态，重启游戏后恢复默认。 */
public final class AutoBossConfig {

    private AutoBossConfig() {
    }

    /** 是否自动识别公屏头目公告并开始进线。 */
    public static volatile boolean autoDetect = true;

    /** 调试日志：把原始公告和识别结果写进 logs/latest.log。 */
    public static volatile boolean debugLog = true;

    /** 进本时选择的副本组。 */
    public static volatile String copyGroup = "A组副本";

    /** 是否继续屏蔽 [仓库]/获得了/给予了 类消息（沿用旧行为）。 */
    public static volatile boolean filterRewardMessages = true;
}

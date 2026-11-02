package com.example.autoboss;

import com.example.autoboss.automation.AutoBossController;
import com.example.autoboss.automation.BossChatListener;
import com.example.autoboss.automation.BossParser;
import com.example.autoboss.gui.AutoBossMenuScreen;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AutoBossMod implements ClientModInitializer {

    public static final String MOD_ID = "autoboss";
    public static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

    private static KeyBinding menuKey;
    private static KeyBinding stopKey;

    @Override
    public void onInitializeClient() {
        menuKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.autoboss.menu",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_B,
                "key.categories.autoboss"
        ));

        stopKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.autoboss.stop",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_EQUAL,
                "key.categories.autoboss"
        ));

        ClientReceiveMessageEvents.ALLOW_GAME.register((text, overlay) -> {
            if (AutoBossConfig.filterRewardMessages) {
                String clean = BossParser.clean(text.getString());
                if (clean.contains("[仓库]") || clean.contains("获得了") || clean.contains("给予了")) {
                    return false;
                }
            }
            BossChatListener.onGameMessage(text.getString(), MinecraftClient.getInstance());
            return true;
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null) {
                return;
            }
            client.options.pauseOnLostFocus = false;

            while (menuKey.wasPressed()) {
                if (client.currentScreen == null) {
                    client.setScreen(new AutoBossMenuScreen());
                }
            }

            while (stopKey.wasPressed()) {
                AutoBossController.forceStop(client);
            }

            AutoBossController.tick(client);
        });

        LOG.info("[autoboss] 已加载：识别公屏头目公告，B 键打开菜单");
    }
}

package com.example.autoboss.automation;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;

/**
 * 飞行与寻路的薄封装：
 *  - ensureFlying：开创造飞行 + 开 Wurst Flight；
 *  - gotoTo：Wurst 直线寻路；
 *  - stopGoto：停止寻路（飞行保持开启）；
 *  - navigateByKeys：按键飞行（yaw 对准 + 前进/跳/潜行）。
 */
public final class FlightController {

    private FlightController() {
    }

    public static void ensureFlying(MinecraftClient client) {
        ClientPlayerEntity player = client.player;
        if (player == null) {
            return;
        }
        if (!player.getAbilities().allowFlying) {
            player.getAbilities().allowFlying = true;
        }
        if (!player.getAbilities().flying) {
            player.getAbilities().flying = true;
        }
        player.sendAbilitiesUpdate();
        WurstBridge.flight(true);
        WurstBridge.setFlightHack(true);
        WurstBridge.setFlightSpeeds(4.0, 3.0);
    }

    public static void gotoTo(MinecraftClient client, double x, double y, double z) {
        WurstBridge.setFlightSpeeds(4.0, 3.0);
        WurstBridge.gotoTo(x, y, z);
    }

    /** 停寻路并关闭飞行，让玩家自然下坠。 */
    public static void disableFlight(MinecraftClient client) {
        releaseKeys(client);
        WurstBridge.stopGoto();
        WurstBridge.flight(false);
        WurstBridge.setFlightHack(false);

        ClientPlayerEntity player = client.player;
        if (player != null) {
            player.getAbilities().flying = false;
            player.getAbilities().allowFlying = false;
            player.sendAbilitiesUpdate();
        }
    }

    public static void stopGoto() {
        WurstBridge.stopGoto();
    }

    public static double distanceTo(ClientPlayerEntity player, double x, double y, double z) {
        double dx = player.getX() - x;
        double dy = player.getY() - y;
        double dz = player.getZ() - z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    public static double horizontalDistanceTo(ClientPlayerEntity player, double x, double z) {
        double dx = player.getX() - x;
        double dz = player.getZ() - z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** 按键飞向目标点：转向 + 前进/垂直按键。 */
    public static void navigateByKeys(MinecraftClient client, double tx, double ty, double tz) {
        ClientPlayerEntity player = client.player;
        if (player == null) {
            return;
        }

        double dx = tx - player.getX();
        double dy = ty - player.getY();
        double dz = tz - player.getZ();
        double horizontal = Math.sqrt(dx * dx + dz * dz);

        if (horizontal > 0.3) {
            float yaw = (float) (Math.atan2(dz, dx) * 180.0 / Math.PI) - 90.0F;
            player.setYaw(yaw);
        }

        float pitch = (float) (-(Math.atan2(dy, Math.max(horizontal, 0.001)) * 180.0 / Math.PI));
        pitch = Math.max(-60.0F, Math.min(60.0F, pitch));
        player.setPitch(pitch);

        boolean forward = horizontal > 0.4;
        boolean jump = dy > 0.6;
        boolean sneak = dy < -0.6;

        setKey(client.options.forwardKey, forward);
        setKey(client.options.jumpKey, jump);
        setKey(client.options.sneakKey, sneak);

        if (player.input != null) {
            player.input.pressingForward = forward;
            player.input.jumping = jump;
            player.input.sneaking = sneak;
        }
    }

    public static void releaseKeys(MinecraftClient client) {
        if (client == null) {
            return;
        }
        setKey(client.options.forwardKey, false);
        setKey(client.options.backKey, false);
        setKey(client.options.leftKey, false);
        setKey(client.options.rightKey, false);
        setKey(client.options.jumpKey, false);
        setKey(client.options.sneakKey, false);

        ClientPlayerEntity player = client.player;
        if (player != null && player.input != null) {
            player.input.pressingForward = false;
            player.input.pressingBack = false;
            player.input.pressingLeft = false;
            player.input.pressingRight = false;
            player.input.jumping = false;
            player.input.sneaking = false;
        }
    }

    /** 直接设置前进/跳/潜行三个键，三段飞行和手动段共用。 */
    public static void setMovement(MinecraftClient client, boolean forward, boolean jump, boolean sneak) {
        setKey(client.options.forwardKey, forward);
        setKey(client.options.jumpKey, jump);
        setKey(client.options.sneakKey, sneak);

        ClientPlayerEntity player = client.player;
        if (player != null && player.input != null) {
            player.input.pressingForward = forward;
            player.input.jumping = jump;
            player.input.sneaking = sneak;
        }
    }

    private static void setKey(net.minecraft.client.option.KeyBinding key, boolean pressed) {
        if (key != null) {
            key.setPressed(pressed);
        }
    }
}

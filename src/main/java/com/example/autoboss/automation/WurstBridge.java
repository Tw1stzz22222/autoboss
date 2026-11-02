package com.example.autoboss.automation;

import com.example.autoboss.AutoBossMod;
import net.fabricmc.loader.api.FabricLoader;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * 通过反射调用 Wurst Client 的命令处理器与 Flight hack。
 * Wurst 未安装时所有方法安全退化为 no-op，调用方可用 isAvailable() 判断。
 */
public final class WurstBridge {

    private static boolean initialized;
    private static boolean available;
    private static Object processor;
    private static Method processMethod;

    private static boolean speedResolved;
    private static Object flightHack;
    private static Method flightSetEnabled;
    private static Method flightIsEnabled;
    private static Method horizontalSetValue;
    private static Method verticalSetValue;

    private WurstBridge() {
    }

    public static synchronized boolean isAvailable() {
        init();
        return available;
    }

    /** 执行一条 Wurst 命令（不带前导点），例如 "goto 1 2 3"。 */
    public static boolean run(String command) {
        init();
        if (!available || command == null) {
            return false;
        }
        try {
            processMethod.invoke(processor, command.trim());
            return true;
        } catch (Throwable t) {
            AutoBossMod.LOG.warn("[autoboss] Wurst 命令失败: .{} ({})", command, t.toString());
            return false;
        }
    }

    public static void flight(boolean on) {
        run(on ? "t Flight on" : "t Flight off");
    }

    public static void gotoTo(double x, double y, double z) {
        run(String.format("goto %d %d %d", Math.round(x), Math.round(y), Math.round(z)));
    }

    /** Wurst 的 goto 无参调用用于停止寻路（与 new-line-helper 的 CMD_GOTO_STOP 一致）。 */
    public static void stopGoto() {
        run("goto");
    }

    /** 尽量把 Flight 速度调到指定值；失败时静默忽略。 */
    public static void setFlightSpeeds(double horizontal, double vertical) {
        if (!isAvailable()) {
            return;
        }
        resolveSpeeds();
        try {
            if (horizontalSetValue != null) {
                horizontalSetValue.invoke(horizontalSetValueTarget, horizontal);
            }
            if (verticalSetValue != null) {
                verticalSetValue.invoke(verticalSetValueTarget, vertical);
            }
        } catch (Throwable t) {
            AutoBossMod.LOG.debug("[autoboss] 设置飞行速度失败: {}", t.toString());
            horizontalSetValue = null;
            verticalSetValue = null;
        }
    }

    private static synchronized void init() {
        if (initialized) {
            return;
        }
        initialized = true;

        if (!FabricLoader.getInstance().isModLoaded("wurst")) {
            AutoBossMod.LOG.info("[autoboss] 未检测到 Wurst，飞行/寻路退回按键模式");
            return;
        }

        try {
            Class<?> wurstClass = Class.forName("net.wurstclient.WurstClient");
            Object wurst = wurstClass.getField("INSTANCE").get(null);
            Object cmdProcessor = wurstClass.getMethod("getCmdProcessor").invoke(wurst);
            processMethod = cmdProcessor.getClass().getMethod("process", String.class);
            processor = cmdProcessor;
            available = true;
            AutoBossMod.LOG.info("[autoboss] Wurst 命令桥已就绪");
        } catch (Throwable t) {
            AutoBossMod.LOG.warn("[autoboss] Wurst 桥不可用: {}", t.toString());
        }
    }

    // Wurst 的 speed 设置对象（SliderSetting）需要单独缓存，避免每次反射。
    private static Object horizontalSetValueTarget;
    private static Object verticalSetValueTarget;

    private static synchronized void resolveSpeeds() {
        if (speedResolved) {
            return;
        }
        speedResolved = true;

        try {
            Class<?> wurstClass = Class.forName("net.wurstclient.WurstClient");
            Object wurst = wurstClass.getField("INSTANCE").get(null);
            Object hax = wurstClass.getMethod("getHax").invoke(wurst);

            Object hack;
            try {
                Field field = hax.getClass().getField("flightHack");
                hack = field.get(hax);
            } catch (NoSuchFieldException e) {
                hack = hax.getClass().getMethod("getHackByName", String.class).invoke(hax, "Flight");
            }
            if (hack == null) {
                return;
            }
            flightHack = hack;
            flightIsEnabled = hack.getClass().getMethod("isEnabled");
            flightSetEnabled = hack.getClass().getMethod("setEnabled", boolean.class);

            Object h = null;
            Object v = null;
            try {
                h = hack.getClass().getField("horizontalSpeed").get(hack);
            } catch (NoSuchFieldException ignored) {
            }
            try {
                v = hack.getClass().getField("verticalSpeed").get(hack);
            } catch (NoSuchFieldException ignored) {
            }
            if (h != null) {
                horizontalSetValue = h.getClass().getMethod("setValue", double.class);
                horizontalSetValueTarget = h;
            }
            if (v != null) {
                verticalSetValue = v.getClass().getMethod("setValue", double.class);
                verticalSetValueTarget = v;
            }
            AutoBossMod.LOG.info("[autoboss] Wurst Flight 已解析 (speed settings: {}/{})",
                    horizontalSetValue != null, verticalSetValue != null);
        } catch (Throwable t) {
            AutoBossMod.LOG.warn("[autoboss] 解析 Wurst Flight 失败: {}", t.toString());
        }
    }

    /** 直接开关 Flight hack（比命令更可靠，命令不可用时兜底）。 */
    public static void setFlightHack(boolean enabled) {
        if (!isAvailable()) {
            return;
        }
        resolveSpeeds();
        try {
            if (flightHack != null && flightSetEnabled != null) {
                boolean current = flightIsEnabled != null && (boolean) flightIsEnabled.invoke(flightHack);
                if (current != enabled) {
                    flightSetEnabled.invoke(flightHack, enabled);
                }
            }
        } catch (Throwable t) {
            AutoBossMod.LOG.debug("[autoboss] 切换 Flight hack 失败: {}", t.toString());
        }
    }
}

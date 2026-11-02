package com.example.autoboss.automation;

import com.example.autoboss.AutoBossConfig;
import com.example.autoboss.AutoBossMod;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.List;

/**
 * 自动进线 + 跑图状态机。
 *
 * 流程（复用猪核的进线方式）：
 *   /menu → 「魔塔」→「魔塔镇新线路N」→ 传送到镇线 →
 *   飞到该层告示牌 → 右键 →（13/14/15c 先点特殊层槽位）→
 *   「A组副本」→「传送第X层一线」→ 进本 → 按 BossRoutes 逐点飞行。
 */
public final class AutoBossController {

    public enum State {
        IDLE,
        MENU_MAIN,
        MENU_TOWN,
        TELEPORT_WAIT,
        SIGN_GOTO,
        SIGN_CLICK,
        SPECIAL_MENU,
        GROUP_MENU,
        COPY_LINE,
        ENTER_WAIT,
        ROUTE
    }

    private static State state = State.IDLE;
    private static BossTarget target;
    private static BossTarget lastDetected;
    private static List<BossRoutes.Step> route = List.of();

    private static int stateTicks;
    private static int menuRetries;
    private static int clickRetries;
    private static int routeIndex;
    private static int stepTicks;
    private static int stepRetries;

    private static String dedupKey;
    private static long dedupAt;
    private static String completedKey;
    private static long completedAt;
    private static long lastBusyNotifyAt;

    private static final long DEDUP_MS = 10_000L;
    private static final long COMPLETED_MS = 120_000L;
    private static final long BUSY_NOTIFY_MS = 5_000L;

    private AutoBossController() {
    }

    // ------------------------------------------------------------------
    // 对外入口
    // ------------------------------------------------------------------

    public static void onDetected(BossTarget detected) {
        long now = System.currentTimeMillis();

        if (detected.key().equals(dedupKey) && now - dedupAt < DEDUP_MS) {
            return;
        }
        dedupKey = detected.key();
        dedupAt = now;
        lastDetected = detected;

        if (detected.key().equals(completedKey) && now - completedAt < COMPLETED_MS) {
            AutoBossMod.LOG.info("[autoboss] 最近已跑过同一头目，忽略重复公告: {}", detected.describe());
            return;
        }

        if (!AutoBossConfig.autoDetect) {
            if (AutoBossConfig.debugLog) {
                AutoBossMod.LOG.info("[autoboss] 自动识别已关闭，忽略: {}", detected.describe());
            }
            return;
        }

        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) {
            return;
        }

        if (state != State.IDLE) {
            // 刷屏时不要把“已忽略”也刷满聊天栏，最多 5 秒提示一次。
            if (now - lastBusyNotifyAt > BUSY_NOTIFY_MS) {
                lastBusyNotifyAt = now;
                notify(client, "§e[AutoBoss] 检测到 " + detected.describe() + "，但当前正在执行，已忽略");
            }
            return;
        }

        start(client, detected);
    }

    public static void tick(MinecraftClient client) {
        if (state == State.IDLE) {
            return;
        }
        try {
            stateTicks++;
            switch (state) {
                case MENU_MAIN -> tickMenuMain(client);
                case MENU_TOWN -> tickMenuTown(client);
                case TELEPORT_WAIT -> tickTeleportWait(client);
                case SIGN_GOTO -> tickSignGoto(client);
                case SIGN_CLICK -> tickSignClick(client);
                case SPECIAL_MENU -> tickSpecialMenu(client);
                case GROUP_MENU -> tickGroupMenu(client);
                case COPY_LINE -> tickCopyLine(client);
                case ENTER_WAIT -> tickEnterWait(client);
                case ROUTE -> tickRoute(client);
                default -> {
                }
            }
        } catch (Throwable t) {
            AutoBossMod.LOG.error("[autoboss] 状态机异常", t);
            fail(client, "内部异常: " + t.getClass().getSimpleName());
        }
    }

    public static void abort(MinecraftClient client, String reason) {
        if (client != null && client.player != null) {
            notify(client, "§c[AutoBoss] 已终止: " + reason);
        }
        cleanup(client);
    }

    /** `=`：强制终止所有流程，并关闭自动识别，防止下一条公告又自动启动。 */
    public static void forceStop(MinecraftClient client) {
        boolean wasRunning = state != State.IDLE;
        AutoBossConfig.autoDetect = false;
        cleanup(client);
        if (client != null && client.player != null) {
            notify(client, wasRunning
                    ? "§c[AutoBoss] 已强制终止所有流程，自动识别已关闭（B 菜单可重开）"
                    : "§7[AutoBoss] 当前没有流程在跑，自动识别已关闭（B 菜单可重开）");
        }
        AutoBossMod.LOG.info("[autoboss] 强制终止（原状态运行中={}），自动识别已关闭", wasRunning);
    }

    public static void retryLast(MinecraftClient client) {
        if (lastDetected == null) {
            if (client.player != null) {
                notify(client, "§e[AutoBoss] 还没有识别到任何头目公告");
            }
            return;
        }
        if (state != State.IDLE) {
            if (client.player != null) {
                notify(client, "§e[AutoBoss] 当前流程还在执行，先终止或等待完成");
            }
            return;
        }
        dedupKey = null;
        completedKey = null;
        onDetected(lastDetected);
    }

    // ------------------------------------------------------------------
    // 各状态
    // ------------------------------------------------------------------

    private static void start(MinecraftClient client, BossTarget detected) {
        target = detected;
        route = BossRoutes.build(detected);
        routeIndex = 0;
        stepTicks = 0;
        stepRetries = 0;
        menuRetries = 0;
        clickRetries = 0;

        if (client.currentScreen != null) {
            client.setScreen(null);
        }
        sendCommand(client, "/menu");

        notify(client, "§a[AutoBoss] 识别到 " + detected.describe() + "，开始进线");
        AutoBossMod.LOG.info("[autoboss] 开始执行 {}", detected.describe());
        setState(State.MENU_MAIN);
    }

    private static void tickMenuMain(MinecraftClient client) {
        if (stateTicks < 10) {
            return;
        }
        ScreenHandler handler = openedHandler(client);
        if (handler == null) {
            if (stateTicks > 120) {
                retryMenu(client, "主菜单未打开");
            }
            return;
        }
        if (stateTicks % 5 != 0) {
            return;
        }

        // 有些服 /menu 后就直接是镇线列表。
        if (click(client, handler, target.lineName())) {
            progress(client, "已选择镇线 " + target.lineName());
            setState(State.TELEPORT_WAIT);
            return;
        }
        if (clickMainMenu(client, handler)) {
            setState(State.MENU_TOWN);
            return;
        }
        if (stateTicks > 160) {
            retryMenu(client, "主菜单里没找到「魔塔」");
        }
    }

    private static void tickMenuTown(MinecraftClient client) {
        if (stateTicks < 10) {
            return;
        }
        ScreenHandler handler = openedHandler(client);
        if (handler == null) {
            if (stateTicks > 120) {
                fail(client, "镇线菜单未打开");
            }
            return;
        }
        if (stateTicks % 5 != 0) {
            return;
        }

        String numeral = target.townNumeral();
        if (click(client, handler, target.lineName())
                || click(client, handler, "新线路" + numeral)
                || click(client, handler, "线路" + numeral)) {
            progress(client, "已选择镇线 " + target.lineName() + "，等待传送");
            setState(State.TELEPORT_WAIT);
            return;
        }
        if (stateTicks > 160) {
            fail(client, "镇线菜单里没找到 " + target.lineName());
        }
    }

    private static void tickTeleportWait(MinecraftClient client) {
        if (stateTicks < 40) {
            return;
        }
        if (client.currentScreen != null) {
            client.setScreen(null);
        }
        FlightController.ensureFlying(client);
        Vec3d nav = navPos(target.floor());
        FlightController.gotoTo(client, nav.x, nav.y, nav.z);
        stepTicks = 0;
        stepRetries = 0;
        progress(client, "传送到镇线，开始飞向 " + target.floor() + "c 告示牌");
        setState(State.SIGN_GOTO);
    }

    private static void tickSignGoto(MinecraftClient client) {
        ClientPlayerEntity player = client.player;
        if (player == null) {
            return;
        }
        Vec3d nav = navPos(target.floor());

        if (stateTicks == 1) {
            FlightController.ensureFlying(client);
            FlightController.gotoTo(client, nav.x, nav.y, nav.z);
        }

        if (FlightController.distanceTo(player, nav.x, nav.y, nav.z) < 2.5) {
            FlightController.stopGoto();
            FlightController.releaseKeys(client);
            clickRetries = 0;
            setState(State.SIGN_CLICK);
            return;
        }

        if (stateTicks > 450) {
            if (stepRetries < 2) {
                stepRetries++;
                stateTicks = 0;
                FlightController.gotoTo(client, nav.x, nav.y, nav.z);
                progress(client, "没飞到告示牌，重发寻路 (" + stepRetries + "/2)");
            } else {
                fail(client, "无法到达 " + target.floor() + "c 告示牌附近");
            }
        }
    }

    private static void tickSignClick(MinecraftClient client) {
        if (stateTicks == 1) {
            BlockPos sign = signPos(target.floor());
            lookAt(client, sign);
            rightClickBlock(client, sign);
        }

        if (openedHandler(client) != null) {
            clickRetries = 0;
            setState(needsSpecialMenu(target.floor()) ? State.SPECIAL_MENU : State.GROUP_MENU);
            return;
        }

        if (stateTicks > 60) {
            if (clickRetries < 2) {
                clickRetries++;
                stateTicks = 0;
                progress(client, "右键告示牌无响应，重试 (" + clickRetries + "/2)");
            } else {
                fail(client, "右键 " + target.floor() + "c 告示牌无响应");
            }
        }
    }

    private static void tickSpecialMenu(MinecraftClient client) {
        if (stateTicks < 10) {
            return;
        }
        ScreenHandler handler = openedHandler(client);
        if (handler == null) {
            if (stateTicks > 120) {
                fail(client, "特殊层菜单未打开");
            }
            return;
        }
        if (stateTicks % 5 != 0) {
            return;
        }

        int slot = specialSlot(target.floor());
        if (slot >= 0 && slot < handler.slots.size()) {
            ItemStack stack = handler.getSlot(slot).getStack();
            if (!stack.isEmpty()) {
                clickSlot(client, handler, slot);
                progress(client, "已点选 " + target.floorCN());
                setState(State.GROUP_MENU);
                return;
            }
        }

        if (click(client, handler, target.floorCN())) {
            progress(client, "已点选 " + target.floorCN());
            setState(State.GROUP_MENU);
            return;
        }

        if (stateTicks > 140) {
            fail(client, "特殊层菜单定位失败（槽位 " + slot + "）");
        }
    }

    private static void tickGroupMenu(MinecraftClient client) {
        if (stateTicks < 10) {
            return;
        }
        ScreenHandler handler = openedHandler(client);
        if (handler == null) {
            if (stateTicks > 120) {
                fail(client, "副本组菜单未打开");
            }
            return;
        }
        if (stateTicks % 5 != 0) {
            return;
        }

        if (click(client, handler, AutoBossConfig.copyGroup) || click(client, handler, "A组")) {
            progress(client, "已选择 " + AutoBossConfig.copyGroup + "，等待分线菜单");
            setState(State.COPY_LINE);
            return;
        }
        if (stateTicks > 140) {
            fail(client, "副本组菜单里没找到 " + AutoBossConfig.copyGroup);
        }
    }

    private static void tickCopyLine(MinecraftClient client) {
        if (stateTicks < 10) {
            return;
        }
        ScreenHandler handler = openedHandler(client);
        if (handler == null) {
            if (stateTicks > 120) {
                fail(client, "分线菜单未打开");
            }
            return;
        }
        if (stateTicks % 5 != 0) {
            return;
        }

        if (clickCopyLine(client, handler)) {
            progress(client, "已选择「传送" + target.floorCN() + "一线」，等待进本");
            setState(State.ENTER_WAIT);
            return;
        }
        if (stateTicks > 160) {
            fail(client, "分线菜单里没找到「传送" + target.floorCN() + "一线」");
        }
    }

    private static void tickEnterWait(MinecraftClient client) {
        if (client.currentScreen != null) {
            if (stateTicks > 300) {
                fail(client, "点击分线后菜单没关闭，可能进本失败");
            }
            return;
        }
        if (stateTicks < 60) {
            return;
        }

        FlightController.ensureFlying(client);
        routeIndex = 0;
        stepTicks = 0;
        stepRetries = 0;
        progress(client, "已进入 " + target.floor() + "c 一线，开始跑图");
        setState(State.ROUTE);
    }

    private static void tickRoute(MinecraftClient client) {
        ClientPlayerEntity player = client.player;
        if (player == null) {
            return;
        }
        if (routeIndex >= route.size()) {
            finish(client);
            return;
        }

        BossRoutes.Step step = route.get(routeIndex);
        double dist = FlightController.distanceTo(player, step.x(), step.y(), step.z());

        switch (step.type()) {
            case GOTO -> {
                boolean wurst = WurstBridge.isAvailable();
                if (stepTicks == 0) {
                    FlightController.ensureFlying(client);
                    if (wurst) {
                        FlightController.gotoTo(client, step.x(), step.y(), step.z());
                    }
                }
                stepTicks++;
                if (!wurst) {
                    // 没有 Wurst 时退回按键直线飞行。
                    FlightController.navigateByKeys(client, step.x(), step.y(), step.z());
                }
                if (dist < 2.0) {
                    FlightController.stopGoto();
                    nextStep();
                } else if (stepTicks > (wurst ? 500 : 1200)) {
                    if (stepRetries < 2) {
                        stepRetries++;
                        stepTicks = 0;
                        if (wurst) {
                            FlightController.gotoTo(client, step.x(), step.y(), step.z());
                        }
                        progress(client, "导航超时，重发 (" + stepRetries + "/2)");
                    } else {
                        fail(client, "导航超时: " + fmt(step));
                    }
                }
            }
            case STOP_GOTO -> {
                FlightController.stopGoto();
                FlightController.releaseKeys(client);
                nextStep();
            }
            case FLY_OFF -> {
                FlightController.disableFlight(client);
                progress(client, "停寻路、关飞行，开始下坠");
                nextStep();
            }
            case WAIT -> {
                stepTicks++;
                if (stepTicks >= Math.max(1, step.ticks())) {
                    nextStep();
                }
            }
            case FLY_ON -> {
                FlightController.ensureFlying(client);
                progress(client, "14c：重新开启飞行");
                nextStep();
            }
            case KEY_MOVE_TO -> {
                if (stepTicks == 0) {
                    FlightController.stopGoto();
                    FlightController.ensureFlying(client);
                }
                stepTicks++;
                FlightController.navigateByKeys(client, step.x(), step.y(), step.z());
                if (dist < 2.0) {
                    FlightController.releaseKeys(client);
                    nextStep();
                } else if (stepTicks > 600) {
                    FlightController.releaseKeys(client);
                    fail(client, "按键飞行超时: " + fmt(step));
                }
            }
        }
    }

    private static void nextStep() {
        routeIndex++;
        stepTicks = 0;
        stepRetries = 0;
    }

    // ------------------------------------------------------------------
    // 收尾
    // ------------------------------------------------------------------

    private static void finish(MinecraftClient client) {
        FlightController.stopGoto();
        FlightController.releaseKeys(client);
        // 最后的 FLY_OFF 已经把飞行关掉了，这里不再重新开飞行。

        if (target != null) {
            completedKey = target.key();
            completedAt = System.currentTimeMillis();
            progress(client, "已到达 " + target.floor() + "c 头目坐标，飞行已停止，交给你了");
            AutoBossMod.LOG.info("[autoboss] 完成 {}", target.describe());
        }

        target = null;
        route = List.of();
        setState(State.IDLE);
    }

    private static void fail(MinecraftClient client, String reason) {
        State failed = state;
        AutoBossMod.LOG.warn("[autoboss] 中止于 {}: {}", failed, reason);
        cleanup(client);
        if (client != null && client.player != null) {
            notify(client, "§c[AutoBoss] 中止: " + reason + " §7(" + failed + ")");
        }
    }

    private static void cleanup(MinecraftClient client) {
        FlightController.stopGoto();
        if (client != null) {
            FlightController.releaseKeys(client);
        }
        target = null;
        route = List.of();
        setState(State.IDLE);
    }

    private static void retryMenu(MinecraftClient client, String reason) {
        if (menuRetries < 2) {
            menuRetries++;
            if (client.currentScreen != null) {
                client.setScreen(null);
            }
            sendCommand(client, "/menu");
            setState(State.MENU_MAIN);
            progress(client, reason + "，重试 /menu (" + menuRetries + "/2)");
        } else {
            fail(client, reason);
        }
    }

    // ------------------------------------------------------------------
    // 菜单 / 槽位工具
    // ------------------------------------------------------------------

    private static ScreenHandler openedHandler(MinecraftClient client) {
        if (client.currentScreen instanceof HandledScreen<?> screen) {
            return screen.getScreenHandler();
        }
        return null;
    }

    private static boolean click(MinecraftClient client, ScreenHandler handler, String keyword) {
        if (handler == null || keyword == null || keyword.isEmpty()) {
            return false;
        }
        String kw = keyword.toLowerCase();
        for (int i = 0; i < handler.slots.size(); i++) {
            ItemStack stack = handler.getSlot(i).getStack();
            if (stack.isEmpty()) {
                continue;
            }
            String name = BossParser.clean(stack.getName().getString()).toLowerCase();
            if (name.contains(kw)) {
                return clickSlot(client, handler, i);
            }
        }
        return false;
    }

    /** 主菜单里的「魔塔」分类，排除具体镇线，避免误点第一条线。 */
    private static boolean clickMainMenu(MinecraftClient client, ScreenHandler handler) {
        for (int i = 0; i < handler.slots.size(); i++) {
            ItemStack stack = handler.getSlot(i).getStack();
            if (stack.isEmpty()) {
                continue;
            }
            String name = BossParser.clean(stack.getName().getString());
            if (name.contains("魔塔") && !name.contains("线路")) {
                return clickSlot(client, handler, i);
            }
        }
        return false;
    }

    /** 只点「传送第X层一线」本体，跳过 A1/A2/A3 分线。 */
    private static boolean clickCopyLine(MinecraftClient client, ScreenHandler handler) {
        String exact = "传送" + target.floorCN() + "一线";
        for (int i = 0; i < handler.slots.size(); i++) {
            ItemStack stack = handler.getSlot(i).getStack();
            if (stack.isEmpty()) {
                continue;
            }
            if (BossParser.clean(stack.getName().getString()).trim().equals(exact)) {
                return clickSlot(client, handler, i);
            }
        }
        for (int i = 0; i < handler.slots.size(); i++) {
            ItemStack stack = handler.getSlot(i).getStack();
            if (stack.isEmpty()) {
                continue;
            }
            String name = BossParser.clean(stack.getName().getString());
            if (name.contains(exact)
                    && !name.contains("A1") && !name.contains("A2") && !name.contains("A3")) {
                return clickSlot(client, handler, i);
            }
        }
        return false;
    }

    private static boolean clickSlot(MinecraftClient client, ScreenHandler handler, int slot) {
        if (client.interactionManager == null || client.player == null) {
            return false;
        }
        client.interactionManager.clickSlot(handler.syncId, slot, 0, SlotActionType.PICKUP, client.player);
        return true;
    }

    private static void sendCommand(MinecraftClient client, String command) {
        if (client.player != null && client.player.networkHandler != null) {
            client.player.networkHandler.sendChatMessage(command);
        }
    }

    // ------------------------------------------------------------------
    // 坐标 / 交互
    // ------------------------------------------------------------------

    public static BlockPos signPos(int floor) {
        return switch (floor) {
            case 10 -> new BlockPos(-12, 164, -73);
            case 12 -> new BlockPos(-12, 184, -73);
            default -> new BlockPos(-12, 194, -73); // 13 / 14 / 15c 共用高台告示牌
        };
    }

    public static Vec3d navPos(int floor) {
        return switch (floor) {
            case 10 -> new Vec3d(-14.0, 163.0, -74.0);
            case 12 -> new Vec3d(-14.0, 183.0, -74.0);
            default -> new Vec3d(-13.0, 193.0, -74.0);
        };
    }

    public static boolean needsSpecialMenu(int floor) {
        return floor == 13 || floor == 14 || floor == 15;
    }

    public static int specialSlot(int floor) {
        return switch (floor) {
            case 13 -> 0;
            case 14 -> 2;
            case 15 -> 4;
            default -> -1;
        };
    }

    private static void lookAt(MinecraftClient client, BlockPos pos) {
        ClientPlayerEntity player = client.player;
        if (player == null) {
            return;
        }
        double dx = pos.getX() + 0.5 - player.getX();
        double dy = pos.getY() + 0.5 - player.getEyeY();
        double dz = pos.getZ() + 0.5 - player.getZ();
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        player.setYaw((float) (Math.atan2(dz, dx) * 180.0 / Math.PI) - 90.0F);
        player.setPitch((float) (-(Math.atan2(dy, horizontal) * 180.0 / Math.PI)));
    }

    private static void rightClickBlock(MinecraftClient client, BlockPos pos) {
        ClientPlayerEntity player = client.player;
        if (player == null || client.interactionManager == null) {
            return;
        }
        Vec3d eye = player.getEyePos();
        Vec3d center = Vec3d.ofCenter(pos);
        Vec3d dir = eye.subtract(center).normalize();
        Direction side = Direction.getFacing((float) dir.x, (float) dir.y, (float) dir.z);
        BlockHitResult hit = new BlockHitResult(center, side, pos, false);
        client.interactionManager.interactBlock(player, Hand.MAIN_HAND, hit);
        player.swingHand(Hand.MAIN_HAND);
    }

    // ------------------------------------------------------------------
    // 状态 / 展示
    // ------------------------------------------------------------------

    private static void setState(State next) {
        if (next != state) {
            AutoBossMod.LOG.info("[autoboss] {} -> {}", state, next);
        }
        state = next;
        stateTicks = 0;
    }

    private static void progress(MinecraftClient client, String message) {
        if (client != null) {
            notify(client, "§b[AutoBoss] " + message);
        }
        AutoBossMod.LOG.info("[autoboss] {}", message);
    }

    private static void notify(MinecraftClient client, String message) {
        if (client.player != null) {
            client.player.sendMessage(Text.literal(message), false);
        }
    }

    public static boolean isRunning() {
        return state != State.IDLE;
    }

    public static String statusText() {
        if (state == State.IDLE) {
            return "闲置";
        }
        StringBuilder sb = new StringBuilder();
        switch (state) {
            case MENU_MAIN -> sb.append("打开主菜单");
            case MENU_TOWN -> sb.append("选择镇线");
            case TELEPORT_WAIT -> sb.append("等待传送");
            case SIGN_GOTO -> sb.append("飞向告示牌");
            case SIGN_CLICK -> sb.append("右键告示牌");
            case SPECIAL_MENU -> sb.append("选择层数");
            case GROUP_MENU -> sb.append("选择副本组");
            case COPY_LINE -> sb.append("选择分线");
            case ENTER_WAIT -> sb.append("等待进入副本");
            case ROUTE -> sb.append("跑图 ").append(Math.min(routeIndex + 1, route.size())).append("/").append(route.size());
            default -> sb.append(state.name());
        }
        if (target != null) {
            sb.append(" §7| §b").append(target.floor()).append("c ").append(target.lineName());
        }
        return sb.toString();
    }

    public static String lastDetectedText() {
        BossTarget t = lastDetected;
        if (t == null) {
            return "无";
        }
        return t.floor() + "c " + t.lineName() + " §7(" + t.coords() + ")";
    }

    private static String fmt(BossRoutes.Step step) {
        return String.format("%.0f %.0f %.0f", step.x(), step.y(), step.z());
    }
}

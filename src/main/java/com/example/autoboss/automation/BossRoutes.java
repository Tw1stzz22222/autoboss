package com.example.autoboss.automation;

import java.util.ArrayList;
import java.util.List;

/**
 * 各层进本后的固定飞行路线。最后统一追加「头目坐标 → 停止寻路」。
 * 14c 中间那段是：停寻路+停飞行 → 下落 60 tick → 重开飞行 → 按键飞到 -70 10 0。
 */
public final class BossRoutes {

    public enum StepType { GOTO, STOP_GOTO, KEY_MOVE_TO, FLY_OFF, WAIT, FLY_ON, WALK_TO_X }

    public record Step(StepType type, double x, double y, double z, int ticks) {
        public Step(StepType type, double x, double y, double z) {
            this(type, x, y, z, 0);
        }
    }

    private BossRoutes() {
    }

    public static List<Step> build(BossTarget target) {
        List<Step> steps = new ArrayList<>();
        switch (target.floor()) {
            case 10 -> {
                steps.add(gotoStep(127, 108, -226));
                steps.add(gotoStep(-30, 132, -226));
            }
            case 12 -> {
                steps.add(gotoStep(105, 76, 92));
                steps.add(gotoStep(105, 112, 92));
                steps.add(gotoStep(143, 112, 23));
            }
            case 13 -> {
                steps.add(gotoStep(-22, 38, -141));
                steps.add(gotoStep(-32, 38, -92));
            }
            case 14 -> {
                steps.add(gotoStep(-78, 14, 0));
                steps.add(new Step(StepType.FLY_OFF, 0, 0, 0));
                steps.add(new Step(StepType.WAIT, 0, 0, 0, 60)); // 下坠 3 秒
                steps.add(new Step(StepType.WALK_TO_X, -70, 0, 0)); // 落地后笔直向前走到 x=-70
                steps.add(new Step(StepType.FLY_ON, 0, 0, 0));
                steps.add(gotoStep(-58, 38, 20));
            }
            case 15 -> {
                steps.add(gotoStep(-45, 14, -49));
                steps.add(gotoStep(7, 14, -49));
            }
            default -> {
                // 不会到达：解析阶段已经过滤。
            }
        }

        steps.add(gotoStep(target.x(), target.y(), target.z()));
        // 到点关飞行（停寻路 + 关 Wurst Flight + abilities 落地）
        steps.add(new Step(StepType.FLY_OFF, 0, 0, 0));
        return steps;
    }

    private static Step gotoStep(double x, double y, double z) {
        return new Step(StepType.GOTO, x, y, z);
    }
}

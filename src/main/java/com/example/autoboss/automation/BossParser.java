package com.example.autoboss.automation;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 解析公屏头目公告。
 *
 * 依据猪核 HUD 的收录逻辑：公告里同时包含
 *   - 线路：新三线 / 新五线
 *   - 层数：F15 / F12 之类
 *   - 坐标：x:.. y:.. z:..
 * 只有三者齐全且层数属于 10/12/13/14/15 时才认为是一条有效指令。
 */
public final class BossParser {

    private static final Pattern LINE = Pattern.compile("新([一二三四五12345])线");
    private static final Pattern FLOOR_F = Pattern.compile("(?i)F\\s*(\\d{1,2})(?!\\d)");
    private static final Pattern FLOOR_CN = Pattern.compile("第\\s*(\\d{1,2})\\s*层");

    private static final Pattern X = Pattern.compile("(?i)(?<![a-z])x\\s*[:：]\\s*(-?\\d+(?:\\.\\d+)?)");
    private static final Pattern Y = Pattern.compile("(?i)(?<![a-z])y\\s*[:：]\\s*(-?\\d+(?:\\.\\d+)?)");
    private static final Pattern Z = Pattern.compile("(?i)(?<![a-z])z\\s*[:：]\\s*(-?\\d+(?:\\.\\d+)?)");

    private BossParser() {
    }

    /** 去掉 § 格式化代码，便于正则匹配。 */
    public static String clean(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replaceAll("§[0-9a-fk-orA-FK-ORx]", "");
    }

    /** 解析一条公告；不是头目公告或字段不全会返回 null。 */
    public static BossTarget parse(String raw) {
        String msg = clean(raw);
        if (msg.isEmpty()) {
            return null;
        }

        Matcher lineMatcher = LINE.matcher(msg);
        if (!lineMatcher.find()) {
            return null;
        }
        int townIndex = numeral(lineMatcher.group(1));
        if (townIndex < 1) {
            return null;
        }

        int floor = -1;
        Matcher fm = FLOOR_F.matcher(msg);
        if (fm.find()) {
            floor = Integer.parseInt(fm.group(1));
        } else {
            Matcher fc = FLOOR_CN.matcher(msg);
            if (fc.find()) {
                floor = Integer.parseInt(fc.group(1));
            }
        }

        // 11c 明确不处理；其余非法层数也忽略。
        if (floor != 10 && floor != 12 && floor != 13 && floor != 14 && floor != 15) {
            return null;
        }

        double x = coord(X, msg);
        double y = coord(Y, msg);
        double z = coord(Z, msg);
        if (Double.isNaN(x) || Double.isNaN(y) || Double.isNaN(z)) {
            return null;
        }

        return new BossTarget(townIndex, floor, x, y, z, msg);
    }

    private static double coord(Pattern pattern, String msg) {
        Matcher m = pattern.matcher(msg);
        if (!m.find()) {
            return Double.NaN;
        }
        return Double.parseDouble(m.group(1));
    }

    private static int numeral(String s) {
        return switch (s) {
            case "一", "1" -> 1;
            case "二", "2" -> 2;
            case "三", "3" -> 3;
            case "四", "4" -> 4;
            case "五", "5" -> 5;
            default -> -1;
        };
    }

    /** 该消息看起来像头目公告但解析失败时，用于调试日志判断。 */
    public static boolean looksBossRelated(String raw) {
        String msg = clean(raw);
        return msg.contains("头目") || (msg.contains("线") && msg.contains("F"));
    }
}

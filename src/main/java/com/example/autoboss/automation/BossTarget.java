package com.example.autoboss.automation;

/**
 * 一条已解析出来的头目公告。
 *
 * @param townIndex 镇线序号 1~5（「新三线」→ 3）
 * @param floor     层数 10/12/13/14/15
 * @param x         头目 X 坐标
 * @param y         头目 Y 坐标
 * @param z         头目 Z 坐标
 * @param raw       原始公告文本（已去色）
 */
public record BossTarget(int townIndex, int floor, double x, double y, double z, String raw) {

    private static final String[] CN = {"零", "一", "二", "三", "四", "五"};

    /** 镇线菜单里的物品名，例如「魔塔镇新线路三」。 */
    public String lineName() {
        return "魔塔镇新线路" + CN[Math.max(1, Math.min(5, townIndex))];
    }

    public String townNumeral() {
        return CN[Math.max(1, Math.min(5, townIndex))];
    }

    /** 副本菜单里的层数文案，例如「第十二层」。 */
    public String floorCN() {
        return switch (floor) {
            case 10 -> "第十层";
            case 12 -> "第十二层";
            case 13 -> "第十三层";
            case 14 -> "第十四层";
            case 15 -> "第十五层";
            default -> "第" + floor + "层";
        };
    }

    public String coords() {
        return String.format("%.0f %.0f %.0f", x, y, z);
    }

    /** 去重用：同一条公告重复广播时保持不变。 */
    public String key() {
        return townIndex + "|" + floor + "|" + Math.round(x) + "|" + Math.round(y) + "|" + Math.round(z);
    }

    public String describe() {
        return floor + "c " + lineName() + " (" + coords() + ")";
    }
}

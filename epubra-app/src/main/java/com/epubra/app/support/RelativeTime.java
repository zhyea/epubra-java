package com.epubra.app.support;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * 相对时间文案格式化——把 {@link Instant} 转成「刚刚 / 5 分钟前 / 3 小时前 / 昨天 / 3月5日」。
 *
 * <h2>为什么单独抽一个类</h2>
 * <p>{@link DraftDocument} 的卡片副标题要显示相对时间，但这段逻辑有明确的边界条件
 * （跨天、跨年、未来时间、null），放进 record 会让 record 变成"半个工具类"。
 * 抽出来后可以独立单测，且将来状态栏 / 资源表复用同一套文案规则。
 *
 * <h2>阈值</h2>
 * <ul>
 *   <li>{@code < 1 分钟} → 刚刚</li>
 *   <li>{@code < 60 分钟} → N 分钟前</li>
 *   <li>同一自然日 → N 小时前</li>
 *   <li>昨天 → 昨天</li>
 *   <li>今年内 → M月D日</li>
 *   <li>跨年 → YYYY年M月D日</li>
 * </ul>
 *
 * <p>「同一自然日」按系统默认时区判断——用户感知的"今天"是本地时区的今天，
 * 不能用 UTC 日历字段。
 */
public final class RelativeTime {

    private RelativeTime() {
    }

    /** 时区：系统默认——用户看到的时间文案应与本机时钟一致。 */
    private static final ZoneId ZONE = ZoneId.systemDefault();

    /**
     * 格式化相对时间。
     *
     * @param instant 目标时刻；{@code null} 或 {@link Instant#EPOCH} 返回「未知」
     * @param now     当前时刻（注入以便测试固定时钟）
     */
    public static String format(Instant instant, Instant now) {
        if (instant == null || Instant.EPOCH.equals(instant)) {
            return "未知";
        }
        if (now == null) {
            now = Instant.now();
        }
        long seconds = Duration.between(instant, now).toSeconds();

        // 未来时间（时钟回拨 / 文件时间异常）——不显示"负 N 分钟前"这种怪文案
        if (seconds < 0) {
            return "刚刚";
        }
        if (seconds < 60) {
            return "刚刚";
        }
        long minutes = seconds / 60;
        if (minutes < 60) {
            return minutes + " 分钟前";
        }

        ZonedDateTime target = instant.atZone(ZONE);
        ZonedDateTime base = now.atZone(ZONE);
        long hours = minutes / 60;

        if (target.toLocalDate().equals(base.toLocalDate())) {
            return hours + " 小时前";
        }
        if (target.toLocalDate().equals(base.toLocalDate().minusDays(1))) {
            return "昨天";
        }
        if (target.getYear() == base.getYear()) {
            return target.getMonthValue() + "月" + target.getDayOfMonth() + "日";
        }
        return target.getYear() + "年" + target.getMonthValue() + "月" + target.getDayOfMonth() + "日";
    }

    /** 用当前时间格式化——生产代码走这个入口。 */
    public static String format(Instant instant) {
        return format(instant, Instant.now());
    }
}
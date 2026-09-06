package com.epubra.app.support;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link RelativeTime} 文案测试——宫格卡片副标题用的相对时间。
 *
 * <p>阈值边界（1 分钟 / 60 分钟 / 跨天 / 昨天 / 跨年）与未来时间都覆盖到。
 */
class RelativeTimeTest {

    private static final ZoneId ZONE = ZoneId.systemDefault();
    /** 固定基准：2026-09-06 15:00 本地时间。 */
    private static final Instant NOW =
            ZonedDateTime.of(2026, 9, 6, 15, 0, 0, 0, ZONE).toInstant();

    @Test
    void nullAndEpochRenderAsUnknown() {
        assertEquals("未知", RelativeTime.format(null, NOW));
        assertEquals("未知", RelativeTime.format(Instant.EPOCH, NOW),
                "EPOCH 表示读不到 mtime，应显示「未知」而不是 1970 年");
    }

    @Test
    void underOneMinuteIsJustNow() {
        assertEquals("刚刚", RelativeTime.format(NOW.minus(59, ChronoUnit.SECONDS), NOW));
        assertEquals("刚刚", RelativeTime.format(NOW, NOW));
    }

    @Test
    void minutesRange() {
        assertEquals("1 分钟前", RelativeTime.format(NOW.minus(60, ChronoUnit.SECONDS), NOW));
        assertEquals("59 分钟前", RelativeTime.format(NOW.minus(59, ChronoUnit.MINUTES), NOW));
    }

    @Test
    void sameDayShowsHours() {
        // 今天 09:00 → 15:00，差 6 小时，仍在同一自然日
        Instant earlier = ZonedDateTime.of(2026, 9, 6, 9, 0, 0, 0, ZONE).toInstant();
        assertEquals("6 小时前", RelativeTime.format(earlier, NOW));
    }

    @Test
    void yesterdayShowsYesterday() {
        Instant y = ZonedDateTime.of(2026, 9, 5, 23, 0, 0, 0, ZONE).toInstant();
        assertEquals("昨天", RelativeTime.format(y, NOW));
    }

    @Test
    void sameYearShowsMonthAndDay() {
        Instant older = ZonedDateTime.of(2026, 3, 5, 10, 0, 0, 0, ZONE).toInstant();
        assertEquals("3月5日", RelativeTime.format(older, NOW));
    }

    @Test
    void previousYearIncludesYear() {
        Instant lastYear = ZonedDateTime.of(2025, 12, 25, 10, 0, 0, 0, ZONE).toInstant();
        assertEquals("2025年12月25日", RelativeTime.format(lastYear, NOW));
    }

    @Test
    void futureTimeFallsBackToJustNow() {
        // 时钟回拨或文件时间戳异常时，不显示「负 N 分钟前」这种怪文案
        assertEquals("刚刚", RelativeTime.format(NOW.plus(2, ChronoUnit.HOURS), NOW));
    }

    @Test
    void formatWithoutExplicitNowUsesSystemClock() {
        // 不注入 now 的重载应走系统时钟，结果仍应是合法文案而非异常
        String text = RelativeTime.format(Instant.now().minus(30, ChronoUnit.MINUTES));
        assertEquals("30 分钟前", text, "未注入 now 时应使用系统当前时间");
    }
}
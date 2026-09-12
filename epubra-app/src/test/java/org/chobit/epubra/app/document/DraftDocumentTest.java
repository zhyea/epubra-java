package org.chobit.epubra.app.document;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DraftDocument} 单元测试——工作空间扫描产出的轻量记录，
 * 宫格的标题与时间文案都由它推导。
 */
class DraftDocumentTest {

    private static final Instant NOW = Instant.parse("2026-09-12T10:00:00Z");

    private static DraftDocument doc(String name, Instant modifiedAt) {
        return new DraftDocument(Path.of("D:/ws").resolve(name), name, modifiedAt);
    }

    @Test
    void stemStripsDraftSuffix() {
        assertEquals("三体", doc("三体.draft", NOW).stem());
    }

    @Test
    void stemKeepsOtherExtensions() {
        assertEquals("notes.txt", doc("notes.txt", NOW).stem(), "非 .draft 后缀原样保留，不做推断");
    }

    @Test
    void epochMeansUnknownModificationTime() {
        assertFalse(doc("未知.draft", Instant.EPOCH).hasKnownModifiedTime(),
                "读不到 mtime 时落到 EPOCH，UI 显示「未知」而不是 1970 年");
        assertTrue(doc("已知.draft", NOW).hasKnownModifiedTime());
    }

    @Test
    void relativeTimeTextUsesGivenClock() {
        DraftDocument recent = doc("刚改.draft", NOW.minusSeconds(30));
        assertEquals("刚刚", recent.relativeTimeText(NOW));
    }

    @Test
    void relativeTimeTextSpansHoursAndDays() {
        assertEquals("5 分钟前", doc("a.draft", NOW.minusSeconds(5 * 60)).relativeTimeText(NOW));
        assertEquals("3 小时前", doc("b.draft", NOW.minusSeconds(3 * 3600)).relativeTimeText(NOW));
        assertEquals("昨天", doc("c.draft", NOW.minusSeconds(26 * 3600)).relativeTimeText(NOW));
    }

    @Test
    void futureTimeFallsBackToJustNow() {
        assertEquals("刚刚", doc("未来.draft", NOW.plusSeconds(600)).relativeTimeText(NOW),
                "时钟回拨 / 未来时间戳不应显示负数");
    }

    @Test
    void isValueBasedRecord() {
        DraftDocument a = new DraftDocument(Path.of("D:/ws/x.draft"), "x", NOW);
        DraftDocument b = new DraftDocument(Path.of("D:/ws/x.draft"), "x", NOW);

        assertEquals(a, b, "record 按值比较，扫描结果可直接用于去重与断言");
        assertEquals(a.hashCode(), b.hashCode());
    }
}

package com.epubra.app.support;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link FindOps} 把 chapter 内的正反向查找 + 回卷逻辑抽出来后的覆盖。
 */
class FindOpsTest {

    @Test
    void forwardFindsImmediateHit() {
        int hit = FindOps.indexOfForward("hello world", "world", 0, true);
        assertEquals(6, hit);
    }

    @Test
    void forwardWrapsAroundToBeginning() {
        // keyword 出现在位置 0；from=10 → 第一次未命中 → 回卷到 0 → 命中
        int hit = FindOps.indexOfForward("foo foobar", "foo", 10, true);
        assertEquals(0, hit);
    }

    @Test
    void forwardReturnsMinusOneWhenAbsent() {
        int hit = FindOps.indexOfForward("hello", "xyz", 0, true);
        assertEquals(-1, hit);
    }

    @Test
    void forwardHonoursCaseSensitivity() {
        assertEquals(-1, FindOps.indexOfForward("FOO", "foo", 0, true));
        assertEquals(0, FindOps.indexOfForward("FOO", "foo", 0, false));
    }

    @Test
    void backwardFindsImmediateHit() {
        int hit = FindOps.indexOfBackward("foo foobar", "foo", 4, true);
        assertEquals(4, hit);
    }

    @Test
    void backwardWrapsAroundToEnd() {
        // "foo foobar": "bar" starts at index 7; from=0 → wrap to end
        int hit = FindOps.indexOfBackward("foo foobar", "bar", 0, true);
        assertEquals(7, hit);
    }

    @Test
    void backwardReturnsMinusOneWhenAbsent() {
        int hit = FindOps.indexOfBackward("hello", "xyz", 4, true);
        assertEquals(-1, hit);
    }

    @Test
    void wrapStatusReportsFindStatus() {
        assertEquals("", FindOps.wrapStatus(5, -1, true));           // 一次性命中
        assertEquals("", FindOps.wrapStatus(5, -1, false));          // 一次性命中（反向）
        assertEquals("已回到开头", FindOps.wrapStatus(-1, 0, true));   // 回卷命中
        assertEquals("已回到结尾", FindOps.wrapStatus(-1, 8, false));  // 回卷命中
        assertEquals("未找到", FindOps.wrapStatus(-1, -1, true));     // 完全未找到
        assertEquals("未找到", FindOps.wrapStatus(-1, -1, false));    // 完全未找到
    }
}

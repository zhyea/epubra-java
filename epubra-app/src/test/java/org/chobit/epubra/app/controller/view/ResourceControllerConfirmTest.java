package org.chobit.epubra.app.controller.view;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 资源删除 / 清理的确认文案契约。
 *
 * <p>守的是一类曾经的缺陷形态：<b>文案拼好了却从未被使用</b>——确认通道当时是无参的
 * {@code BooleanSupplier}，拼出来的资源名与引用警告根本传不进去，用户删除时要么被问
 * 一个与资源无关的问题，要么在书籍无改动时被静默删除。
 *
 * <p>现在文案由 {@link ResourceController#deleteConfirmMessage} /
 * {@link ResourceController#cleanupConfirmMessage} 产出，并直接作为确认回调的实参出现，
 * 本测试钉住文案必须携带的关键信息。
 */
class ResourceControllerConfirmTest {

    @Test
    void deleteMessageNamesTheResource() {
        String message = ResourceController.deleteConfirmMessage("cover.png", false);
        assertTrue(message.contains("cover.png"), "确认文案必须点明要删哪一个资源");
        assertFalse(message.contains("正文中存在对它的引用"), "未被引用时不该出现裂图警告");
    }

    @Test
    void deleteMessageWarnsWhenReferencedByChapters() {
        String message = ResourceController.deleteConfirmMessage("Tom & Jerry.png", true);
        assertTrue(message.contains("Tom & Jerry.png"), "文件名要原样展示，不做二次转义");
        assertTrue(message.contains("正文中存在对它的引用"), "被正文引用时必须给出裂图警告");
    }

    @Test
    void cleanupMessageCarriesCountAndNames() {
        String message = ResourceController.cleanupConfirmMessage(3, "a.png、b.png、c.png");
        assertTrue(message.contains("3 个"), "要说明将清理多少个资源");
        assertTrue(message.contains("a.png、b.png、c.png"), "要列出待清理的资源名，别让用户盲确认");
    }

    @Test
    void cleanupMessageCarriesTruncationHint() {
        String message = ResourceController.cleanupConfirmMessage(20, "a.png 等");
        assertTrue(message.contains("20 个"));
        assertTrue(message.contains("a.png 等"), "超过展示上限时保留省略提示");
    }
}

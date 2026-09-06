package org.chobit.epubra.app.support;

import org.chobit.epubra.app.support.CoverOps.CoverState;
import org.chobit.epubra.lib.domain.Book;
import org.chobit.epubra.lib.domain.BookFactory;
import org.chobit.epubra.lib.domain.MediaTypes;
import org.chobit.epubra.lib.domain.Resource;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CoverOps} 三态判定 + 资源筛选 + set/clear 的契约测试。
 *
 * <p>{@code describe} 与 {@code pick} 是不依赖控件的纯逻辑，覆盖：
 * <ul>
 *   <li>{@code null} 书 / null 资源防御</li>
 *   <li>三种状态过渡（EMPTY → SET → EMPTY → DANGLING）</li>
 *   <li>通过 {@code setCover(null)} 清除幂等</li>
 *   <li>删除封面资源时 {@link Book#removeResource} 自动回退</li>
 * </ul>
 */
class CoverOpsTest {

    @Test
    void describeNullBookReturnsEmpty() {
        assertEquals(CoverState.EMPTY, CoverOps.describe(null));
    }

    @Test
    void describeFreshBookReturnsEmpty() {
        Book book = BookFactory.createEmpty("空状态");
        assertNull(book.coverResourceId());
        assertEquals(CoverState.EMPTY, CoverOps.describe(book));
    }

    @Test
    void describeAfterSetCoverReturnsSet() {
        Book book = BookFactory.createEmpty("已设置");
        Resource image = new Resource("cover-img", "OEBPS/images/cover.jpg", MediaTypes.JPEG, new byte[]{1, 2, 3});
        book.resources().add(image);

        CoverOps.set(book, image);
        assertEquals(CoverState.SET, CoverOps.describe(book));
        Optional<Resource> got = book.coverResource();
        assertTrue(got.isPresent());
        assertEquals(image, got.get());
    }

    @Test
    void describeAfterClearCoverReturnsEmpty() {
        Book book = BookFactory.createEmpty("清除后");
        Resource image = new Resource("cover-img", "OEBPS/images/cover.jpg", MediaTypes.JPEG, new byte[]{1, 2, 3});
        book.resources().add(image);
        CoverOps.set(book, image);
        CoverOps.clear(book);
        assertEquals(CoverState.EMPTY, CoverOps.describe(book));
        assertNull(book.coverResourceId());
    }

    @Test
    void describeWithOrphanedCoverIdReturnsDangling() {
        // 模拟校验 D18：coverResourceId 还在但 manifest 已无该资源，
        // 且没有 cover-image 属性残留（彻底失联）
        Book book = BookFactory.createEmpty("悬空");
        book.setCoverResourceId("ghost-id");
        // 没有对应资源，没有 cover-image 属性 → coverResource() 返回 Optional.empty()
        assertEquals(Optional.empty(), book.coverResource());
        assertEquals(CoverState.DANGLING, CoverOps.describe(book));
    }

    @Test
    void describeIgnoresBlankCoverId() {
        Book book = BookFactory.createEmpty("空白 id");
        book.setCoverResourceId("   ");
        assertEquals(CoverState.EMPTY, CoverOps.describe(book));
    }

    @Test
    void describeRespectsCoverImagePropertyWithoutId() {
        // 即便 coverResourceId 为空，资源上挂 cover-image 属性也应识别为 SET
        Book book = BookFactory.createEmpty("属性指向");
        Resource image = new Resource("cover-img", "OEBPS/images/cover.jpg", MediaTypes.JPEG, new byte[]{1, 2, 3});
        image.setProperties("cover-image");
        book.resources().add(image);
        assertEquals(CoverState.SET, CoverOps.describe(book));
    }

    @Test
    void pickAcceptsAnyImageMediaType() {
        Book book = BookFactory.createEmpty("任意图");
        assertTrue(CoverOps.pick(book, png(book)));
        assertTrue(CoverOps.pick(book, jpeg(book)));
        assertTrue(CoverOps.pick(book, gif(book)));
        assertTrue(CoverOps.pick(book, webp(book)));
        assertTrue(CoverOps.pick(book, svg(book)));
    }

    @Test
    void pickRejectsNonImageAndDefendsNulls() {
        Book book = BookFactory.createEmpty("非图");
        assertFalse(CoverOps.pick(book, cssResource()));
        assertFalse(CoverOps.pick(book, xhtmlResource()));
        assertFalse(CoverOps.pick(book, null));
        assertFalse(CoverOps.pick(null, png(book)));
        assertFalse(CoverOps.pick(book, withNullMediaType()));
    }

    @Test
    void setWithNullClearsCover() {
        // 等同 clear()，避免控制器写两份
        Book book = BookFactory.createEmpty("set-null");
        Resource image = png(book);
        CoverOps.set(book, image);
        assertEquals(CoverState.SET, CoverOps.describe(book));
        CoverOps.set(book, null);
        assertEquals(CoverState.EMPTY, CoverOps.describe(book));
    }

    @Test
    void clearIsIdempotent() {
        Book book = BookFactory.createEmpty("幂等");
        CoverOps.clear(book); // 清空态清空，什么也不发生
        assertEquals(CoverState.EMPTY, CoverOps.describe(book));
        Resource image = png(book);
        CoverOps.set(book, image);
        CoverOps.clear(book);
        CoverOps.clear(book); // 已清态再清
        assertEquals(CoverState.EMPTY, CoverOps.describe(book));
    }

    @Test
    void removingCoverResourceAutoClearsCover() {
        // 行为契约：Book.removeResource 删到封面时会自动 setCover(null)。
        // 前端只需刷新 UI，不必监听资源删除事件。
        Book book = BookFactory.createEmpty("删除");
        Resource image = png(book);
        CoverOps.set(book, image);
        assertEquals(CoverState.SET, CoverOps.describe(book));
        book.removeResource(image);
        assertEquals(CoverState.EMPTY, CoverOps.describe(book));
    }

    @Test
    void isCoverImageTypeWhitelist() {
        assertTrue(CoverOps.isCoverImageType(MediaTypes.PNG));
        assertTrue(CoverOps.isCoverImageType(MediaTypes.JPEG));
        assertTrue(CoverOps.isCoverImageType(MediaTypes.GIF));
        assertTrue(CoverOps.isCoverImageType(MediaTypes.WEBP));
        assertTrue(CoverOps.isCoverImageType(MediaTypes.SVG));
        assertFalse(CoverOps.isCoverImageType(MediaTypes.CSS));
        assertFalse(CoverOps.isCoverImageType(MediaTypes.XHTML));
        assertFalse(CoverOps.isCoverImageType(null));
        assertFalse(CoverOps.isCoverImageType(""));
    }

    @Test
    void describeAfterRemoveResourceUpdatesToEmpty() {
        // 与 removingCoverResourceAutoClearsCover 等价但走 describe 全链路
        Book book = BookFactory.createEmpty("删除→describe");
        Resource image = png(book);
        CoverOps.set(book, image);
        book.removeResource(image);
        assertNotNull(book); // 防 javac 警告字段未读
        assertEquals(CoverState.EMPTY, CoverOps.describe(book));
    }

    // ---- helpers ---

    private static Resource png(Book book) {
        Resource r = new Resource("png-1", "OEBPS/images/cover.png", MediaTypes.PNG, new byte[]{1});
        book.resources().add(r);
        return r;
    }

    private static Resource jpeg(Book book) {
        Resource r = new Resource("jpg-1", "OEBPS/images/cover.jpg", MediaTypes.JPEG, new byte[]{1});
        book.resources().add(r);
        return r;
    }

    private static Resource gif(Book book) {
        Resource r = new Resource("gif-1", "OEBPS/images/cover.gif", MediaTypes.GIF, new byte[]{1});
        book.resources().add(r);
        return r;
    }

    private static Resource webp(Book book) {
        Resource r = new Resource("webp-1", "OEBPS/images/cover.webp", MediaTypes.WEBP, new byte[]{1});
        book.resources().add(r);
        return r;
    }

    private static Resource svg(Book book) {
        Resource r = new Resource("svg-1", "OEBPS/images/cover.svg", MediaTypes.SVG, new byte[]{1});
        book.resources().add(r);
        return r;
    }

    private static Resource cssResource() {
        return new Resource("css-1", "OEBPS/styles/main.css", MediaTypes.CSS, new byte[]{1});
    }

    private static Resource xhtmlResource() {
        return new Resource("xhtml-1", "OEBPS/chapter.xhtml", MediaTypes.XHTML, new byte[]{1});
    }

    private static Resource withNullMediaType() {
        Resource r = new Resource("odd-1", "OEBPS/odd", "application/octet-stream", new byte[]{1});
        r.setMediaType(null);
        return r;
    }
}

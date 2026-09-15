package org.chobit.epubra.app.editor;

import javafx.scene.control.TextArea;
import org.chobit.epubra.app.context.BookContext;
import org.chobit.epubra.app.ui.model.ChapterNode;
import org.chobit.epubra.lib.domain.MediaTypes;
import org.chobit.epubra.lib.domain.Resource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link VisualEditorSession#runWhenLoaded} 的契约（真实 WebView）。
 *
 * <p>全书查找跳章后靠它把「选中最先命中」挂到新章节的加载完成点上——{@code reload()}
 * 是异步的，跳章瞬间页面还是旧内容。这里验证两种时序：页面已就绪时立即执行；
 * 加载进行中登记则在加载完成后补执行。中途再发起加载时旧回调作废的序号对账
 * （{@code pendingAfterLoadSeq}）属竞态敏感逻辑，不在此构造（WebEngine 对加载中断的
 * SUCCEEDED 语义不可靠，测了反而假红）。
 */
class VisualEditorSessionLoadHookTest extends VisualEditorTestSupport {

    private static final String CHAPTER = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
            + "<html xmlns=\"http://www.w3.org/1999/xhtml\"><head><title>t</title></head>"
            + "<body><p>全书查找命中标记</p></body></html>";

    /** 在共享 WebView 上装一个真实 session：当前章固定指向 chapter 资源。须在 FX 线程上调用。 */
    private static VisualEditorSession newSession(Resource chapter) {
        BookContext ctx = new BookContext();
        ChapterNode node = new ChapterNode("章", chapter, null);
        TextArea contentArea = new TextArea();
        return new VisualEditorSession(ctx, webView(), contentArea,
                () -> node, n -> "file:/base/", () -> Theme.LIGHT,
                () -> { }, () -> { }, () -> { }, () -> { }, () -> { },
                s -> { }, s -> { });
    }

    private static Resource chapterResource() {
        Resource chapter = new Resource("hook-ch", "OEBPS/ch1.xhtml", MediaTypes.XHTML);
        chapter.setString(CHAPTER);
        return chapter;
    }

    /** 轮询等待 session 就绪（reload 是异步的，不能同步断言）。 */
    private static void awaitReady(VisualEditorSession session) throws Exception {
        AtomicBoolean ready = new AtomicBoolean(false);
        for (int i = 0; i < 200 && !ready.get(); i++) {
            runOnFx(() -> ready.set(session.ready()));
            if (!ready.get()) {
                Thread.sleep(50);
            }
        }
        runOnFx(() -> ready.set(session.ready()));
        assertTrue(ready.get(), "session 200 轮询内未就绪");
    }

    @Test
    @Timeout(60)
    @DisplayName("页面已就绪时 runWhenLoaded 立即执行，不挂起")
    void runsImmediatelyWhenReady() throws Exception {
        Resource chapter = chapterResource();
        VisualEditorSession[] holder = new VisualEditorSession[1];
        runOnFx(() -> holder[0] = newSession(chapter));
        VisualEditorSession session = holder[0];
        runOnFx(session::reload);
        awaitReady(session);

        AtomicInteger runs = new AtomicInteger();
        runOnFx(() -> session.runWhenLoaded(runs::incrementAndGet));
        assertEquals(1, runs.get(), "已就绪时应同步执行一次");
    }

    @Test
    @Timeout(60)
    @DisplayName("加载进行中登记的回调在加载完成后补执行，且此时页面已就绪")
    void pendsUntilLoadCompletes() throws Exception {
        Resource chapter = chapterResource();
        VisualEditorSession[] holder = new VisualEditorSession[1];
        runOnFx(() -> holder[0] = newSession(chapter));
        VisualEditorSession session = holder[0];
        AtomicInteger runs = new AtomicInteger();
        AtomicBoolean readyAtRun = new AtomicBoolean(false);
        runOnFx(() -> {
            session.reload();
            // reload 刚发起：加载必然未完成，回调只能挂起
            session.runWhenLoaded(() -> {
                runs.incrementAndGet();
                readyAtRun.set(session.ready());
            });
        });
        assertEquals(0, runs.get(), "登记当下不得立即执行");

        for (int i = 0; i < 200 && runs.get() == 0; i++) {
            Thread.sleep(50);
        }
        assertEquals(1, runs.get(), "加载完成后应补执行一次");
        assertTrue(readyAtRun.get(), "补执行时页面应已就绪");
    }
}

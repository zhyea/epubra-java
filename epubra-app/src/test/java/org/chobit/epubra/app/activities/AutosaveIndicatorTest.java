package org.chobit.epubra.app.activities;

import javafx.application.Platform;
import org.chobit.epubra.app.context.BookContext;
import org.chobit.epubra.app.document.AutosaveConfig;
import org.chobit.epubra.lib.domain.BookFactory;
import org.chobit.epubra.lib.io.EpubReader;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 关窗冲刷（{@link AutosaveIndicator#flushPending()}）的守卫。
 *
 * <p>自动暂存是「停顿 N 秒后写盘」的节流器（默认 5s）。工作空间里的 {@code .draft}
 * 就是文档本体、<b>没有第二份副本</b>，所以用户在窗口里改完就按标题栏 X / Alt+F4 时，
 * 最后一次编辑还躺在内存里没落盘——关窗前必须补一次写盘。
 *
 * <p>节流窗口是 {@code debounceSeconds} 秒，测试里故意设成 600s 保证「还没到点」，
 * 只有 {@code flushPending()} 能把内容写出去。
 *
 * <h2>⚠ 跨 class 共享 JavaFX Platform</h2>
 * <p>{@code Platform.startup} 全 JVM 只允许一次，后跑的 class 会抛
 * {@code IllegalStateException}——吞掉异常并 {@code countDown} 放行。本类
 * <b>不写 {@code @AfterAll} 调 {@code Platform.exit()}</b>：{@code setImplicitExit(false)}
 * 已保证窗口关掉也不会关掉 toolkit，后面的测试类还要用。
 */
class AutosaveIndicatorTest {

    private static final CountDownLatch FX_STARTED = new CountDownLatch(1);

    @BeforeAll
    static void bootFx() throws Exception {
        try {
            Platform.startup(FX_STARTED::countDown);
        } catch (IllegalStateException alreadyInitialized) {
            FX_STARTED.countDown();
        }
        assertTrue(FX_STARTED.await(10, TimeUnit.SECONDS), "JavaFX toolkit 启动超时");
        Platform.setImplicitExit(false);
    }

    /** 在 FX 线程上跑一段动作并等它跑完，异常回抛到测试线程。 */
    private static void runOnFx(Runnable action) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                action.run();
            } catch (Throwable t) {
                failure.set(t);
            } finally {
                done.countDown();
            }
        });
        assertTrue(done.await(10, TimeUnit.SECONDS), "FX 任务超时");
        if (failure.get() != null) {
            throw new AssertionError("FX 任务抛出异常", failure.get());
        }
    }

    @TempDir
    Path workspace;

    @TempDir
    Path autosaveDir;

    @Test
    void flushPendingWritesEditThatWouldOtherwiseBeLostOnClose() throws Exception {
        BookContext ctx = new BookContext();
        ctx.setAutosaveConfig(new AutosaveConfig(true, 600, autosaveDir.toString()));
        ctx.setBook(BookFactory.createEmpty("关窗前落盘"));
        Path draft = workspace.resolve("关窗前落盘.draft");
        ctx.setCurrentFile(draft);

        AutosaveIndicator indicator = new AutosaveIndicator(ctx, null);
        runOnFx(indicator::wire);
        runOnFx(indicator::onDirty);

        assertFalse(Files.exists(draft), "前置条件：节流未到点，此时还不该落盘");

        runOnFx(indicator::flushPending);

        assertTrue(Files.exists(draft),
                "关窗时必须补写尚未落盘的编辑，否则用户最后一段改动直接丢失");
        assertEquals("关窗前落盘", new EpubReader().read(draft).metadata().firstTitle());
    }

    @Test
    void flushPendingIsNoopWhenNothingIsPending() throws Exception {
        BookContext ctx = new BookContext();
        ctx.setAutosaveConfig(new AutosaveConfig(true, 600, autosaveDir.toString()));
        ctx.setBook(BookFactory.createEmpty("无待写"));
        Path draft = workspace.resolve("无待写.draft");
        ctx.setCurrentFile(draft);

        AutosaveIndicator indicator = new AutosaveIndicator(ctx, null);
        runOnFx(indicator::wire);

        runOnFx(indicator::flushPending);   // 从未 onDirty → 没有排定的暂存

        assertFalse(Files.exists(draft), "没有排定的暂存就不该写盘");
    }
}

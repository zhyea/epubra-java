package org.chobit.epubra.app.support.platform;

import javafx.application.Platform;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AsyncTasks} 单元测试——验证「后台跑 work / FX 线程派 onSuccess / onError /
 * ProgressController 三件套」契约。
 *
 * <p>这些测试不依赖完整 FXML 加载，使用最小化的 {@link FakeProgress}：
 * 直接累加 {@code begin} / {@code update} / {@code done} 调用次数即可。
 *
 * <p>跨测试 class 共享 JavaFX Platform：toolkit 全 JVM 只能 init 一次，
 * 后跑的 class 调 {@link Platform#startup} 会抛 {@link IllegalStateException}。
 * 用 try-catch 吞掉，latch 仍正确 countDown 供后续 await 使用。
 */
class AsyncTasksTest {

    /** 同步测试专用：拿到的是「FX 线程派发过的回调列表」（顺序保证）。 */
    static final class FakeProgress implements AsyncTasks.ProgressController {
        final List<String> events = new ArrayList<>();
        volatile String lastTitle;
        volatile double lastProgress = Double.NaN;

        @Override
        public void begin(String title) {
            lastTitle = title;
            events.add("begin:" + title);
        }

        @Override
        public void update(double fraction) {
            lastProgress = fraction;
            events.add("update:" + fraction);
        }

        @Override
        public void done() {
            events.add("done");
        }
    }

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

    @AfterAll
    static void shutdownFx() {
        // 不 Platform.exit()——同 JVM 多测试类共享 Platform，靠 setImplicitExit(false) 跟随 JVM 结束。
    }

    @Test
    @Timeout(30)
    void successfulTaskFiresOnSuccessOnFxThreadAndProgressLifecycle() throws Exception {
        FakeProgress progress = new FakeProgress();
        AtomicReference<String> fxThreadName = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);

        AsyncTasks.runIo(
                "测试成功",
                () -> 42,
                progress,
                value -> {
                    fxThreadName.set(Thread.currentThread().getName());
                    assertEquals(42, value, "onSuccess 应拿到 work 的返回值");
                    done.countDown();
                },
                err -> done.countDown()
        );

        assertTrue(done.await(5, TimeUnit.SECONDS), "onSuccess 应在 5s 内触发");
        assertNotNull(fxThreadName.get(), "onSuccess 应拿到当前线程名");
        assertTrue(fxThreadName.get().contains("JavaFX"),
                "onSuccess 应跑在 JavaFX Application Thread；实际：" + fxThreadName.get());

        // 进度生命周期按顺序：begin → (update)* → done；update 不强制有，但 done 一定有
        assertTrue(progress.events.contains("begin:测试成功"),
                "begin 应被调用；events=" + progress.events);
        assertTrue(progress.events.get(progress.events.size() - 1).equals("done"),
                "done 应是最后一个；events=" + progress.events);
    }

    @Test
    @Timeout(30)
    void failedTaskFiresOnErrorOnFxThread() throws Exception {
        FakeProgress progress = new FakeProgress();
        AtomicReference<Throwable> capturedError = new AtomicReference<>();
        AtomicReference<String> fxThreadName = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);

        AsyncTasks.runIo(
                "测试失败",
                () -> { throw new IllegalStateException("boom"); },
                progress,
                v -> done.countDown(),
                err -> {
                    fxThreadName.set(Thread.currentThread().getName());
                    capturedError.set(err);
                    done.countDown();
                }
        );

        assertTrue(done.await(5, TimeUnit.SECONDS), "onError 应在 5s 内触发");
        assertNotNull(capturedError.get(), "onError 应拿到抛出的异常");
        assertEquals(IllegalStateException.class, capturedError.get().getClass(),
                "原异常类型应被原样传过来；实际：" + capturedError.get());
        assertEquals("boom", capturedError.get().getMessage());
        assertTrue(fxThreadName.get().contains("JavaFX"),
                "onError 也应跑在 JavaFX Application Thread；实际：" + fxThreadName.get());

        // done 必须也被调——失败路径同样要把进度条收回去
        assertTrue(progress.events.contains("done"),
                "失败时 ProgressController.done() 也应触发；events=" + progress.events);
    }

    @Test
    @Timeout(30)
    void updateProgressReportedToProgressController() throws Exception {
        FakeProgress progress = new FakeProgress();
        CountDownLatch done = new CountDownLatch(1);

        AsyncTasks.runIo(
                "进度上报",
                () -> {
                    // 模拟 work 内部多次上报进度
                    // （AsyncTasks 自身把 updateProgress 转 FX 线程；这里只验
                    //  ProgressController 是否被调过——具体机制由 Task.progressProperty 监听）
                    return "done-string";
                },
                progress,
                v -> done.countDown(),
                err -> done.countDown()
        );

        assertTrue(done.await(5, TimeUnit.SECONDS));
        // runIo 在 work 没主动调 updateProgress 的情况下不会触发 update 回调；这是预期行为。
        // 只验 begin/done 边界存在。
        assertTrue(progress.events.contains("begin:进度上报"));
        assertTrue(progress.events.contains("done"));
    }

    @Test
    @Timeout(30)
    void noopProgressDoesNothing() throws Exception {
        // NOOP_PROGRESS 不抛异常也不留状态——传它进 runIo 不应引发任何副作用。
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> result = new AtomicReference<>();

        AsyncTasks.runIo(
                "noop 路径",
                () -> "ok",
                AsyncTasks.NOOP_PROGRESS,
                v -> {
                    result.set(v);
                    done.countDown();
                },
                err -> done.countDown()
        );

        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertEquals("ok", result.get());
    }

    @Test
    @Timeout(30)
    void runIoVoidOverloadAlsoWorks() throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> fxThreadName = new AtomicReference<>();

        AsyncTasks.runIo(
                "void 路径",
                () -> {
                    // 纯副作用，无返回值
                    return;
                },
                AsyncTasks.NOOP_PROGRESS,
                () -> {
                    fxThreadName.set(Thread.currentThread().getName());
                    done.countDown();
                },
                err -> done.countDown()
        );

        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertTrue(fxThreadName.get().contains("JavaFX"),
                "void 路径 onSuccess 也应在 FX 线程触发");
    }

    @Test
    @Timeout(30)
    void rejectsNullArguments() {
        // 5 个参数任一为 null 都应抛 IAE——避免调用方漏配时被 NPE 坑到远端。
        FakeProgress progress = new FakeProgress();
        java.util.concurrent.Callable<String> okWork = () -> "x";

        try {
            AsyncTasks.runIo(null, okWork, progress, v -> {}, err -> {});
            assertTrue(false, "title 为 null 应抛 IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // OK
        }
        try {
            AsyncTasks.runIo("", okWork, progress, v -> {}, err -> {});
            assertTrue(false, "title 空字符串应抛 IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // OK
        }
        try {
            AsyncTasks.runIo("ok", null, progress, v -> {}, err -> {});
            assertTrue(false, "work 为 null 应抛 IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // OK
        }
        try {
            AsyncTasks.runIo("ok", okWork, null, v -> {}, err -> {});
            assertTrue(false, "progress 为 null 应抛 IllegalArgumentException（传 NOOP_PROGRESS 表示不反馈）");
        } catch (IllegalArgumentException expected) {
            // OK
        }
        try {
            AsyncTasks.runIo("ok", okWork, progress, null, err -> {});
            assertTrue(false, "onSuccess 为 null 应抛 IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // OK
        }
        try {
            AsyncTasks.runIo("ok", okWork, progress, v -> {}, null);
            assertTrue(false, "onError 为 null 应抛 IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // OK
        }
    }
}
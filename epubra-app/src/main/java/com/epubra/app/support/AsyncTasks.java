package com.epubra.app.support;

import javafx.application.Platform;
import javafx.concurrent.Task;

import java.util.concurrent.Callable;
import java.util.function.Consumer;

/**
 * 长操作异步化的统一包装：把任意可能阻塞 FX 线程的工作（文件 IO / 全文校验 /
 * 多文件导入等）挪到后台线程跑，完成 / 失败回调在 FX 线程里更新界面。
 *
 * <h2>典型用法</h2>
 * <pre>{@code
 * AsyncTasks.runIo(
 *     "正在保存 " + target.getFileName(),
 *     () -> writer.write(ctx.book(), target),         // 后台线程跑
 *     progress,
 *     targetPath -> {                                    // FX 线程跑
 *         ctx.setCurrentFile(targetPath);
 *         ctx.setDirty(false);
 *         ctx.bus().publish(new BookSavedEvent());
 *     },
 *     err -> errorReporter.accept("保存失败：" + err.getMessage())
 * );
 * }</pre>
 *
 * <h2>线程模型</h2>
 * <ul>
 *   <li>{@code work.call()} 在新建的守护线程（命名 {@code epubra-<title>}）里跑。</li>
 *   <li>{@code onSuccess} / {@code onError} 通过 {@link Task#setOnSucceeded} /
 *       {@link Task#setOnFailed} 触发——这两个钩子都在 Task 的 {@code Thread} 上派发，
 *       本工具类再 {@link Platform#runLater} 切回 FX 线程，保证回调里所有
 *       JavaFX 控件 / {@link BookContext} 写入都是线程安全的。</li>
 *   <li>{@link ProgressController} 的 {@code begin} / {@code update} / {@code done}
 *       也都强制走 FX 线程（begin 同步切、update / done 走 Platform.runLater）。
 *       这样调用方在 {@code work.call()} 里也能直接调 {@code progress.update(0.5)}。</li>
 * </ul>
 *
 * <h2>取消支持</h2>
 * {@link Task#cancel()} 可在 FX 线程调；{@code work} 自身应定期检查
 * {@link Thread#isInterrupted()} 或抛 {@link InterruptedException} 才能真正终止。
 * 当前阶段（B1）所有 6 个长操作都是「不需要中途取消」的同步 IO，先做最小可用模式，
 * 未来要加取消按钮只需让 work 内部多走一遍中断检查即可。
 *
 * <h2>异常规约</h2>
 * work 抛出的任何 {@link Exception}（含 {@link InterruptedException}）都会进
 * onError。{@link RuntimeException} 同理。{@link Error}（OOM 之类）则按 JavaFX
 * 默认行为走——通常意味着进程已不可恢复，不在本工具的恢复范围。
 *
 * <h2>为何不用 Service</h2>
 * Service 的 cancel / restart / value 缓存适合「同一逻辑重复触发」的场景，
 * 但 6 个长操作每个都只跑一次，Task 更直接。Service 引入的状态机反而徒增测试负担。
 */
public final class AsyncTasks {

    private AsyncTasks() {}

    /** 进度反馈：begin 在启动时切 FX 线程同步触发；update / done 在 FX 线程触发。 */
    public interface ProgressController {
        /** 开始一个长操作：标题给状态栏标签，进度条进入不确定模式或显示 0。 */
        void begin(String title);

        /** 工作线程里更新进度（0.0 - 1.0）。FX 线程触发。 */
        void update(double fraction);

        /** 结束（成功 / 失败都调），状态栏恢复。FX 线程触发。 */
        void done();
    }

    /** 一个不做任何事的 ProgressController，方便调用方在测试 / 不需要 UI 时传入。 */
    public static final ProgressController NOOP_PROGRESS = new ProgressController() {
        @Override public void begin(String title) {}
        @Override public void update(double fraction) {}
        @Override public void done() {}
    };

    /**
     * 启动一个后台任务：work 在守护线程跑，onSuccess / onError 在 FX 线程触发，
     * 进度条 / 标签走 {@code progress} 接口同步。
     *
     * @param title   进度条 / 状态栏显示的标题；同一个 UI 里 title 重复易混淆，应保证唯一
     * @param work    后台线程跑的工作；抛任意 Exception 都进 onError
     * @param progress 进度回调；可传 {@link #NOOP_PROGRESS} 表示不需要 UI 反馈
     * @param onSuccess 后台工作成功后的回调；在 FX 线程跑；拿到 work 返回值
     * @param onError  后台工作抛异常时的回调；在 FX 线程跑；拿到抛出的 Throwable
     * @return 启动后的 Task 引用，调用方可继续挂监听或 cancel
     */
    public static <T> Task<T> runIo(String title,
                                    Callable<T> work,
                                    ProgressController progress,
                                    Consumer<T> onSuccess,
                                    Consumer<Throwable> onError) {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("title 不能为空");
        }
        if (work == null) {
            throw new IllegalArgumentException("work 不能为空");
        }
        if (progress == null) {
            throw new IllegalArgumentException("progress 不能为空，传 AsyncTasks.NOOP_PROGRESS 表示不反馈");
        }
        if (onSuccess == null) {
            throw new IllegalArgumentException("onSuccess 不能为空");
        }
        if (onError == null) {
            throw new IllegalArgumentException("onError 不能为空");
        }

        Task<T> task = new Task<>() {
            @Override
            protected T call() throws Exception {
                updateMessage(title);
                return work.call();
            }
        };
        // Java 不允许 lambda 捕获仍在赋值中的局部变量；先赋成 final 引用再挂回调。
        final Task<T> taskRef = task;
        taskRef.progressProperty().addListener((obs, oldVal, newVal) ->
                Platform.runLater(() -> progress.update(newVal.doubleValue())));
        taskRef.setOnRunning(event -> Platform.runLater(() -> progress.begin(title)));
        taskRef.setOnSucceeded(event -> {
            Platform.runLater(() -> progress.done());
            T value = taskRef.getValue();
            Platform.runLater(() -> {
                try {
                    onSuccess.accept(value);
                } catch (Throwable uncaught) {
                    onError.accept(uncaught);
                }
            });
        });
        taskRef.setOnFailed(event -> {
            Platform.runLater(() -> progress.done());
            Throwable ex = taskRef.getException();
            final Throwable finalEx = ex != null
                    ? ex
                    : new IllegalStateException("Task failed with no exception captured");
            Platform.runLater(() -> onError.accept(finalEx));
        });
        taskRef.setOnCancelled(event -> Platform.runLater(() -> progress.done()));

        Thread t = new Thread(taskRef, "epubra-" + title);
        t.setDaemon(true);
        t.start();
        return taskRef;
    }

    /** 启动一个不需要返回值的任务——IO + 副作用。其它语义同 {@link #runIo}。 */
    public static Task<Void> runIo(String title,
                                   ThrowingRunnable work,
                                   ProgressController progress,
                                   Runnable onSuccess,
                                   Consumer<Throwable> onError) {
        Callable<Void> wrapped = () -> {
            work.run();
            return null;
        };
        return runIo(title, wrapped, progress, v -> onSuccess.run(), onError);
    }

    /** 类似 {@link Runnable} 但允许抛 Exception。 */
    @FunctionalInterface
    public interface ThrowingRunnable {
        void run() throws Exception;
    }
}
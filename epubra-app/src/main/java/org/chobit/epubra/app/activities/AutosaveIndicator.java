package org.chobit.epubra.app.activities;

import javafx.animation.Animation;
import javafx.animation.PauseTransition;
import javafx.scene.control.Label;
import javafx.util.Duration;
import org.chobit.epubra.app.context.BookContext;
import org.chobit.epubra.app.document.Autosave;

/**
 * 自动暂存的「停顿 N 秒后写盘」节流器 + 状态栏指示灯。
 *
 * <p>从 {@code MainController} 搬出（纯搬迁，行为不变）：主控制器只保留
 * {@link #onDirty()} 一行委派，字段与 CSS 类名都收在这里。
 *
 * <h2>逻辑</h2>
 * <ul>
 *   <li>用户每次改动（{@code MainController.markDirty()}）都调 {@link #onDirty()}；
 *       {@link PauseTransition#playFromStart()} 重置计时；</li>
 *   <li>计时器到点才调 {@link Autosave#flushNow(BookContext)} 写盘。</li>
 * </ul>
 *
 * <p>{@code ctx.autosaveConfig().enabled() == false} 时完全跳过装配——Preferences 持久化的
 * 「自动暂存开关」是用户的最高优先级，此时只把状态栏标签标成「关」，不给错误预期。
 *
 * <p>状态栏标签为 null（FXML 未注入 / 单测直造）时所有 UI 操作静默跳过，不影响写盘逻辑。
 */
public final class AutosaveIndicator {

    private static final String SAVING_CLASS = "status-autosave-saving";
    private static final String OFF_CLASS = "status-autosave-off";

    private final BookContext ctx;
    private final Label label;

    /**
     * 节流计时器。配置禁用时为 null——{@link #onDirty()} 以 null 判定「未装配」。
     */
    private PauseTransition debounce;

    /**
     * @param ctx   共享状态（取 autosaveConfig）
     * @param label 状态栏「自动暂存」标签，可为 null
     */
    public AutosaveIndicator(BookContext ctx, Label label) {
        this.ctx = ctx;
        this.label = label;
    }

    /** 装配节流器并按配置初始化标签。对应原 {@code MainController.wireAutosave()}。 */
    public void wire() {
        if (!ctx.autosaveConfig().enabled()) {
            markDisabled();
            return;
        }
        debounce = new PauseTransition(Duration.seconds(ctx.autosaveConfig().debounceSeconds()));
        debounce.setOnFinished(event -> {
            Autosave.flushNow(ctx);
            markIdle();
            refreshLabel();
        });
        markIdle();
        refreshLabel();
    }

    /**
     * 内容 / 元数据刚改动 → 重启节流计时，UI 先翻到"保存中"。
     *
     * <p>loading 期间的内容回填不算真实改动，跳过（原 {@code markDirty()} 内的守卫）。
     */
    public void onDirty() {
        if (ctx.loading() || debounce == null) {
            return;
        }
        debounce.playFromStart();
        markSaving();
    }

    /**
     * 立刻冲刷「已排定但还没到点」的自动暂存；没有待写内容时什么都不做。
     *
     * <p>给窗口关闭路径用：节流窗口是 {@code debounceSeconds} 秒（默认 5s），用户在窗口里
     * 改完就按标题栏 X / Alt+F4 时，最后一次编辑还躺在内存里没落盘。工作空间里的
     * {@code .draft} 就是文档本体、没有第二份副本，所以关闭前补一次写盘。
     *
     * <p>判定依据是节流器的运行状态而不是 {@code ctx.dirty()}：只有「改动之后计时还没到点」
     * 才有未落盘的内容；已经写过盘的 dirty 标志不需要再写一遍。
     */
    public void flushPending() {
        if (debounce == null || debounce.getStatus() != Animation.Status.RUNNING) {
            return;
        }
        debounce.stop();
        Autosave.flushNow(ctx);
        markIdle();
        refreshLabel();
    }

    /** 把"自动暂存 开 / 关 + 间隔 N 秒"展示到状态栏标签上。 */
    public void refreshLabel() {
        if (label == null) {
            return;
        }
        if (!ctx.autosaveConfig().enabled()) {
            markDisabled();
            return;
        }
        label.setText("自动暂存 " + ctx.autosaveConfig().debounceSeconds() + "s");
    }

    /** 标记为"已禁用"——配置文件说不存就不存，避免给用户错误预期。 */
    private void markDisabled() {
        if (label == null) {
            return;
        }
        label.setText("自动暂存 关");
        label.getStyleClass().removeAll(SAVING_CLASS);
        if (!label.getStyleClass().contains(OFF_CLASS)) {
            label.getStyleClass().add(OFF_CLASS);
        }
    }

    private void markSaving() {
        if (label == null) {
            return;
        }
        label.getStyleClass().removeAll(OFF_CLASS);
        if (!label.getStyleClass().contains(SAVING_CLASS)) {
            label.getStyleClass().add(SAVING_CLASS);
        }
    }

    /** 节流到点 → 刚写完盘 → 落回"空闲"样式。 */
    private void markIdle() {
        if (label == null) {
            return;
        }
        label.getStyleClass().removeAll(SAVING_CLASS, OFF_CLASS);
    }
}

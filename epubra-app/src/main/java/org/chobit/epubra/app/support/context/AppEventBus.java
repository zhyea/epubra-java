package org.chobit.epubra.app.support.context;

import org.chobit.epubra.app.support.editor.Theme;
import org.chobit.epubra.lib.validation.ValidationReport;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * 应用内事件总线。
 *
 * <p>重构前 MainController 的 {@code refreshAll()} 把「换书 / 撤销 / 重做 / 主题切换 / 校验完成」
 * 这些状态变更广播给所有面板是手动调用的——任何新增子控制器都得记得在 MainController 里追加
 * 一次刷新调用，很容易漏。引入事件总线后，子控制器订阅自己关心的事件，MainController
 * 只负责「状态变了 → 广播一条事件」，新增子控制器只需订阅即可被自动通知。
 *
 * <h2>使用约束</h2>
 * <ul>
 *   <li>订阅者在回调里不应抛出异常；任何 RuntimeException 都会被吞掉，仅记录到 System.Logger。</li>
 *   <li>事件体用 Java 25 record，不可变 + 自带 equals/hashCode/toString，方便调试。</li>
 *   <li>事件按「主题」分组（如 {@code "book.changed"}），同主题的事件可以共享一个事件 record
 *       加 {@link #publish(String, Object)} 发布；也可以直接 {@link #publish(AppEvent)} 一条。</li>
 * </ul>
 */
public final class AppEventBus {

    /** 全部事件记录的 marker 接口，便于 {@link #publish(AppEvent)} 类型校验。 */
    public sealed interface AppEvent permits BookLoadedEvent, BookSavedEvent, BookRestoredEvent,
            BookDirtyChangedEvent, ValidationCompletedEvent, ThemeChangedEvent,
            ChapterChangedEvent, WordCountsInvalidatedEvent {
    }

    /** 新书加载完成（含新建 / 打开 / 撤销 / 重做后）。 */
    public record BookLoadedEvent() implements AppEvent {}

    /** 当前书已保存到磁盘（onSave / onSaveAs）。 */
    public record BookSavedEvent() implements AppEvent {}

    /** 撤销或重做回到某个快照。 */
    public record BookRestoredEvent() implements AppEvent {}

    /** 脏标记变化（编辑器输入 / 显式 beginChange / 保存后归零）。 */
    public record BookDirtyChangedEvent(boolean dirty) implements AppEvent {}

    /** 校验流程跑完，结果报告以事件载荷形式传递。 */
    public record ValidationCompletedEvent(ValidationReport report) implements AppEvent {}

    /** 主题切换完成。 */
    public record ThemeChangedEvent(Theme theme) implements AppEvent {}

    /** 当前章节切换（含目录选中 / 撤销重做后回填）。 */
    public record ChapterChangedEvent() implements AppEvent {}

    /** 字数缓存失效（换书 / 章节内容被程序化改写）。 */
    public record WordCountsInvalidatedEvent() implements AppEvent {}

    private static final System.Logger LOG = System.getLogger(AppEventBus.class.getName());

    /** topic → 订阅者列表。CopyOnWriteArrayList 保证遍历期间订阅安全。 */
    private final java.util.Map<String, CopyOnWriteArrayList<Consumer<Object>>> subscribers = new java.util.concurrent.ConcurrentHashMap<>();

    /** 不抛 checked 异常的取消订阅句柄，让 try-with-resources 直接可用。 */
    @FunctionalInterface
    public interface Unsubscriber extends AutoCloseable {
        @Override
        void close(); // 不抛 Exception
    }

    /** 简化订阅：监听指定 topic，回调拿到 {@link Object} 载荷后再自己 cast。 */
    public Unsubscriber subscribe(String topic, Consumer<Object> handler) {
        subscribers.computeIfAbsent(topic, k -> new CopyOnWriteArrayList<>()).add(handler);
        return () -> unsubscribe(topic, handler);
    }

    /** 类型安全的订阅：回调只接收指定类型。 */
    public <T> Unsubscriber subscribe(Class<T> eventType, Consumer<T> handler) {
        String topic = eventType.getName();
        Consumer<Object> bridge = payload -> {
            if (eventType.isInstance(payload)) {
                handler.accept(eventType.cast(payload));
            }
        };
        return subscribe(topic, bridge);
    }

    public void unsubscribe(String topic, Consumer<Object> handler) {
        List<Consumer<Object>> list = subscribers.get(topic);
        if (list != null) {
            list.remove(handler);
        }
    }

    /** 通用发布（带载荷）。 */
    public void publish(String topic, Object payload) {
        List<Consumer<Object>> list = subscribers.get(topic);
        if (list == null || list.isEmpty()) {
            return;
        }
        for (Consumer<Object> handler : list) {
            try {
                handler.accept(payload);
            } catch (RuntimeException e) {
                // 订阅者出错不能让总线停摆；记录即可
                LOG.log(System.Logger.Level.WARNING,
                        "EventBus subscriber for '" + topic + "' threw: " + e.getMessage(), e);
            }
        }
    }

    /** 直接发布一条事件 record。topic 用事件类型全限定名。 */
    public void publish(AppEvent event) {
        publish(event.getClass().getName(), event);
    }
}

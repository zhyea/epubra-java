package org.chobit.epubra.app.support.document;

import org.chobit.epubra.lib.domain.Book;
import org.chobit.epubra.lib.io.EpubReader;
import org.chobit.epubra.lib.io.EpubWriter;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 书籍编辑历史：撤销 / 重做。
 *
 * <p>快照采用「把 {@link Book} 写成内存 EPUB，恢复时再读回」的方式，而不是逐个字段做深拷贝。
 * 这样内容、元数据、目录层级、资源增删等任何变更都被同一个机制覆盖，无需为每类操作单独定义逆操作。
 *
 * <p>代价是每次快照要序列化整本书，因此调用方需要控制记录频率（见 MainController：
 * 连续输入合并为一个编辑步）。栈深上限 {@value #LIMIT} 用于约束内存占用。
 */
public final class BookHistory {

    /** 单侧历史栈的最大深度；超出后丢弃最早的快照。 */
    public static final int LIMIT = 30;

    /** 一次还原的结果：书籍快照与当时的「未保存」标志。 */
    public record Snapshot(Book book, boolean dirty) {
    }

    private final EpubWriter writer = new EpubWriter();
    private final EpubReader reader = new EpubReader();

    private final Deque<Entry> undoStack = new ArrayDeque<>();
    private final Deque<Entry> redoStack = new ArrayDeque<>();

    /** 丢弃全部历史（换书时使用：新建、打开）。 */
    public void reset() {
        undoStack.clear();
        redoStack.clear();
    }

    /**
     * 记录一次变更「之前」的状态。
     *
     * @param book  变更前的书籍，尚未被修改
     * @param dirty 变更前的「未保存」标志
     */
    public void record(Book book, boolean dirty) {
        byte[] data = serialize(book);
        if (data == null) {
            return;
        }
        undoStack.push(new Entry(data, dirty));
        trim(undoStack);
        // 有了新的编辑分支，重做栈不再成立
        redoStack.clear();
    }

    public boolean canUndo() {
        return !undoStack.isEmpty();
    }

    public boolean canRedo() {
        return !redoStack.isEmpty();
    }

    /**
     * 撤销一步：把当前状态压入重做栈，返回上一个状态。
     *
     * @param book  当前书籍（应已把界面上的编辑写回）
     * @param dirty 当前的「未保存」标志
     * @return 上一个状态；无可撤销时返回 null
     */
    public Snapshot undo(Book book, boolean dirty) {
        if (undoStack.isEmpty()) {
            return null;
        }
        Entry current = new Entry(serialize(book), dirty);
        Entry previous = undoStack.pop();
        if (current.data() != null) {
            redoStack.push(current);
            trim(redoStack);
        }
        return previous.restore(reader);
    }

    /**
     * 重做一步：把当前状态压入撤销栈，返回被撤销掉的那个状态。
     *
     * @return 被重做的状态；无可重做时返回 null
     */
    public Snapshot redo(Book book, boolean dirty) {
        if (redoStack.isEmpty()) {
            return null;
        }
        Entry current = new Entry(serialize(book), dirty);
        Entry next = redoStack.pop();
        if (current.data() != null) {
            undoStack.push(current);
            trim(undoStack);
        }
        return next.restore(reader);
    }

    private static void trim(Deque<Entry> stack) {
        while (stack.size() > LIMIT) {
            stack.removeLast();
        }
    }

    private byte[] serialize(Book book) {
        if (book == null) {
            return null;
        }
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            writer.write(book, out);
            return out.toByteArray();
        } catch (IOException e) {
            // 快照失败不应中断用户的正常编辑，放弃这一步历史即可
            return null;
        }
    }

    private record Entry(byte[] data, boolean dirty) {

        Snapshot restore(EpubReader reader) {
            try (InputStream in = new ByteArrayInputStream(data)) {
                return new Snapshot(reader.read(in), dirty);
            } catch (IOException e) {
                throw new UncheckedIOException("历史快照恢复失败", e);
            }
        }
    }
}

package org.chobit.epubra.app.activities;

import javafx.scene.control.IndexRange;
import javafx.scene.control.TextArea;

import java.util.function.Consumer;

/**
 * 编辑工具条的 XHTML 片段插入：段落 / 标题 / 加粗 / 斜体 / 列表。
 *
 * <p>从 {@code MainController} 拆出——这一组只操作 {@code contentArea} 与撤销入口，
 * 与窗口其他部分无耦合。{@code MainController} 只保留 {@code onInsertXxx} 一行委派
 * （FXML 的 {@code onAction} 只能绑主控制器方法）。
 *
 * <p><b>注意</b>：这里产出的必须是 XHTML 片段而非 HTML——HTMLEditor 产出的 HTML
 * 直接写进正文会让 EPUB 校验失败，所以走「光标处插入片段」而不是换编辑器。
 */
public class InsertActivity {

    private final TextArea contentArea;
    private final Consumer<String> setStatus;
    private final Runnable beginChange;

    public InsertActivity(TextArea contentArea, Consumer<String> setStatus, Runnable beginChange) {
        this.contentArea = contentArea;
        this.setStatus = setStatus;
        this.beginChange = beginChange;
    }

    /** 空选区时把光标放进 {@code <p></p>} 中间；有选区时用 {@code <p>} 包裹选中文本。 */
    public void paragraph() {
        insertFragment("<p></p>", 3);
    }

    /** 二级标题。H1 通常留作章名，H2 是小节标。 */
    public void heading() {
        insertFragment("<h2></h2>", 4);
    }

    public void bold() {
        wrapTag("strong");
    }

    public void italic() {
        wrapTag("em");
    }

    /** 无序列表骨架；多行内容由用户自行复制 {@code <li>} 增加，不预判列表项数。 */
    public void list() {
        insertFragment("<ul>\n<li></li>\n</ul>", "<ul>\n<li>".length());
    }

    /**
     * 通用片段插入助手。选区非空时把 {@code fragment} 视作「左右两侧开闭标记 + 选区内容」
     * 重写；选区为空时把 {@code fragment} 插到光标处。光标停在 {@code caretOffset}
     * 指定的相对位置，便于用户接着输入。
     *
     * <p>写入之前调 {@code beginChange()} 让撤销栈只记一次；后续文本变更由
     * {@code contentArea} 的 listener 自动触发 markDirty，本方法不重复调。
     */
    public void insertFragment(String fragment, int caretOffset) {
        if (!editable()) {
            return;
        }
        beginChange.run();
        IndexRange sel = contentArea.getSelection();
        if (sel.getLength() > 0) {
            String selected = contentArea.getSelectedText();
            String wrapped = fragment.substring(0, caretOffset)
                    + selected
                    + fragment.substring(caretOffset);
            int start = sel.getStart();
            contentArea.replaceSelection(wrapped);
            contentArea.positionCaret(start + caretOffset + selected.length());
        } else {
            int caretPos = contentArea.getCaretPosition();
            contentArea.insertText(caretPos, fragment);
            contentArea.positionCaret(caretPos + caretOffset);
        }
    }

    /**
     * 包标签助手：选中文本时用 {@code <tag>...</tag>} 包裹并把内容重新选中，
     * 选区为空时空插入一对标签并把光标落在开标签之后。
     */
    public void wrapTag(String tag) {
        if (!editable()) {
            return;
        }
        beginChange.run();
        IndexRange sel = contentArea.getSelection();
        String open = "<" + tag + ">";
        String close = "</" + tag + ">";
        if (sel.getLength() > 0) {
            String selected = contentArea.getSelectedText();
            String wrapped = open + selected + close;
            int start = sel.getStart();
            contentArea.replaceSelection(wrapped);
            // 把刚被包裹的内容再次选中，方便接着改字号 / 颜色等其他属性
            contentArea.selectRange(start + open.length(), start + wrapped.length() - close.length());
        } else {
            String fragment = open + close;
            int caretPos = contentArea.getCaretPosition();
            contentArea.insertText(caretPos, fragment);
            contentArea.positionCaret(caretPos + open.length());
        }
    }

    private boolean editable() {
        if (contentArea == null || contentArea.isDisabled()) {
            setStatus.accept("当前章节不可编辑，无法插入片段");
            return false;
        }
        return true;
    }
}

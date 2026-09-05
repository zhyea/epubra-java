package com.epubra.app.controller;

import com.epubra.app.support.BookContext;
import com.epubra.app.support.FindOps;
import com.epubra.app.support.TextSearch;
import com.epubra.epublib.domain.Resource;
import javafx.fxml.FXML;
import javafx.scene.control.CheckBox;
import javafx.scene.control.IndexRange;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.Pane;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * 查找 / 替换面板控制器。
 *
 * <p>作为 {@code find-bar.fxml} 的 {@code fx:controller} 由 FXML 实例化：面板内的输入框、
 * 按钮与状态标签经 {@code @FXML} 注入并直接绑定本类方法；正文编辑器（属主 FXML）与回调
 * 在父控制器 {@code initialize()} 阶段通过 {@link #bind} 注入。本类不得定义 {@code initialize()}。
 *
 * <p>纯逻辑（前后查找 + 回绕）已下沉到 {@link FindOps}，本类负责把它装配到当前
 * {@link TextArea} 的选区上，并把状态写到 {@code findStatusLabel}。
 */
public class FindController {

    @FXML
    private Pane findBar;
    @FXML
    private TextField findField;
    @FXML
    private TextField replaceField;
    @FXML
    private CheckBox caseSensitiveCheck;
    @FXML
    private CheckBox wholeBookCheck;
    @FXML
    private Label findStatusLabel;

    private BookContext ctx;
    private TextArea contentArea;
    private Runnable beginChange;
    private Runnable markDirty;
    private Runnable reloadEditor;
    private Runnable refreshPreview;
    private Consumer<String> setStatus;
    private BooleanSupplier confirmDiscardChanges;

    /** FXML 加载后由父控制器注入运行时依赖；必须在任何 onAction 触发前完成。 */
    public void bind(BookContext ctx, TextArea contentArea,
                     Runnable beginChange, Runnable markDirty,
                     Runnable reloadEditor, Runnable refreshPreview,
                     Consumer<String> setStatus, BooleanSupplier confirmDiscardChanges) {
        this.ctx = ctx;
        this.contentArea = contentArea;
        this.beginChange = beginChange;
        this.markDirty = markDirty;
        this.reloadEditor = reloadEditor;
        this.refreshPreview = refreshPreview;
        this.setStatus = setStatus;
        this.confirmDiscardChanges = confirmDiscardChanges;
    }

    public void showBar() {
        if (findBar == null) {
            return;
        }
        findBar.setVisible(true);
        findBar.setManaged(true);
        if (findField.getText().isEmpty()) {
            String selected = contentArea.getSelectedText();
            if (selected != null && !selected.isBlank()) {
                findField.setText(selected);
            }
        }
        findField.requestFocus();
        findField.selectAll();
    }

    public void closeBar() {
        if (findBar == null) {
            return;
        }
        findBar.setVisible(false);
        findBar.setManaged(false);
        contentArea.requestFocus();
    }

    public void findNext() {
        findInChapter(true);
    }

    public void findPrevious() {
        findInChapter(false);
    }

    public void replaceOne() {
        String keyword = findField.getText();
        if (keyword.isEmpty()) {
            findStatus("请输入查找内容");
            return;
        }
        boolean caseSensitive = caseSensitiveCheck.isSelected();
        if (TextSearch.matches(contentArea.getSelectedText(), keyword, caseSensitive)) {
            // 显式开一个编辑步：只依赖输入合并机制的话，600ms 静默窗口内的首次替换不会进历史
            beginChange.run();
            String replacement = replaceField.getText();
            int start = contentArea.getSelection().getStart();
            contentArea.replaceSelection(replacement);
            contentArea.selectRange(start, start + replacement.length());
            findStatus("已替换 1 处");
        }
        findInChapter(true);
    }

    public void replaceAll() {
        String keyword = findField.getText();
        if (keyword.isEmpty()) {
            findStatus("请输入查找内容");
            return;
        }
        boolean caseSensitive = caseSensitiveCheck.isSelected();
        String replacement = replaceField.getText();
        if (wholeBookCheck != null && wholeBookCheck.isSelected()) {
            // 先算命中再开编辑步：没命中时压一条空快照会让撤销「空转」一次
            Map<Resource, String> pending = new LinkedHashMap<>();
            int total = 0;
            for (Resource chapter : ctx.book().spineResources()) {
                TextSearch.ReplaceResult result =
                        TextSearch.replaceAll(chapter.asString(), keyword, replacement, caseSensitive);
                if (result.count() > 0) {
                    pending.put(chapter, result.text());
                    total += result.count();
                }
            }
            if (total == 0) {
                setStatus.accept("全书中未找到：" + keyword);
                findStatus("未找到");
                return;
            }
            beginChange.run();
            pending.forEach(Resource::setString);
            reloadEditor.run();
            refreshPreview.run();
            markDirty.run();
            setStatus.accept("全书共替换 " + total + " 处");
            findStatus("全书 " + total + " 处");
            return;
        }
        TextSearch.ReplaceResult result =
                TextSearch.replaceAll(contentArea.getText(), keyword, replacement, caseSensitive);
        if (result.count() == 0) {
            setStatus.accept("当前章节未找到：" + keyword);
            findStatus("未找到");
            return;
        }
        beginChange.run();
        contentArea.setText(result.text());
        markDirty.run();
        setStatus.accept("当前章节共替换 " + result.count() + " 处");
        findStatus("本章 " + result.count() + " 处");
    }

    private void findInChapter(boolean forward) {
        String keyword = findField.getText();
        if (keyword.isEmpty()) {
            findStatus("请输入查找内容");
            return;
        }
        String text = contentArea.getText();
        if (text.isEmpty()) {
            findStatus("当前章节为空");
            return;
        }
        boolean caseSensitive = caseSensitiveCheck.isSelected();
        IndexRange selection = contentArea.getSelection();
        int from = forward ? selection.getEnd() : selection.getStart() - 1;
        int wrapTarget = forward ? 0 : text.length() - 1;

        // 第一次搜索：可能未命中但回卷后命中——交由 FindOps.wrapStatus 决定提示文本
        int primaryHit = forward
                ? com.epubra.app.support.TextSearch.indexOf(text, keyword, from, caseSensitive)
                : com.epubra.app.support.TextSearch.lastIndexOf(text, keyword, from, caseSensitive);
        int wrapHit = -1;
        if (primaryHit < 0) {
            wrapHit = forward
                    ? com.epubra.app.support.TextSearch.indexOf(text, keyword, 0, caseSensitive)
                    : com.epubra.app.support.TextSearch.lastIndexOf(text, keyword, wrapTarget, caseSensitive);
        }
        int hit = primaryHit >= 0 ? primaryHit : wrapHit;
        String status = FindOps.wrapStatus(primaryHit, wrapHit, forward);
        if (hit < 0) {
            findStatus(status);
            return;
        }
        if (!status.isEmpty()) {
            findStatus(status);
        } else {
            findStatus("");
        }
        contentArea.selectRange(hit, hit + keyword.length());
        contentArea.requestFocus();
    }

    private void findStatus(String message) {
        if (findStatusLabel != null) {
            findStatusLabel.setText(message);
        }
    }
}

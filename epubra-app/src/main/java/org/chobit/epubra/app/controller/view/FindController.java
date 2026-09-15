package org.chobit.epubra.app.controller.view;

import org.chobit.epubra.app.context.BookContext;
import org.chobit.epubra.app.editor.FindOps;
import org.chobit.epubra.app.editor.TextSearch;
import org.chobit.epubra.app.editor.VisualEditorSession;
import org.chobit.epubra.lib.domain.Resource;
import javafx.fxml.FXML;
import javafx.scene.control.CheckBox;
import javafx.scene.control.IndexRange;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
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
    /** 可视化编辑会话：可视化页签激活时查找 / 替换路由进 WebView，而不是隐藏的源码区。 */
    private VisualEditorSession visualSession;
    /** 当前是否停在可视化编辑页签。 */
    private BooleanSupplier visualTabActive;
    private Runnable beginChange;
    private Runnable markDirty;
    private Runnable reloadEditor;
    private Runnable refreshPreview;
    private Consumer<String> setStatus;
    private BooleanSupplier confirmDiscardChanges;

    /** FXML 加载后由父控制器注入运行时依赖；必须在任何 onAction 触发前完成。 */
    public void bind(BookContext ctx, TextArea contentArea,
                     VisualEditorSession visualSession, BooleanSupplier visualTabActive,
                     Runnable beginChange, Runnable markDirty,
                     Runnable reloadEditor, Runnable refreshPreview,
                     Consumer<String> setStatus, BooleanSupplier confirmDiscardChanges) {
        this.ctx = ctx;
        this.contentArea = contentArea;
        this.visualSession = visualSession;
        this.visualTabActive = visualTabActive;
        this.beginChange = beginChange;
        this.markDirty = markDirty;
        this.reloadEditor = reloadEditor;
        this.refreshPreview = refreshPreview;
        this.setStatus = setStatus;
        this.confirmDiscardChanges = confirmDiscardChanges;
        // Esc 关闭查找栏：VSCode / Rider 通用约定。
        //
        // filter 必须挂在 findBar（Pane）而不是各个输入框上——JavaFX 的 event filter 走捕获
        // 阶段，父节点能拦截整棵子树的按键事件；挂在 findField 上则只有它自己是事件目标时才
        // 触发，「区分大小写 / 全书范围」复选框和 5 个按钮获得焦点时按 Esc 就不会关闭。
        // （setOnKeyPressed 是冒泡语义，子节点消费掉的事件传不到父节点，同样不适用于此。）
        if (findBar != null) {
            findBar.addEventFilter(KeyEvent.KEY_PRESSED, this::handleEscape);
        }
    }

    /** 按 Esc → 关闭查找栏并把焦点还给正文编辑器；非 Esc 直接放过。 */
    private void handleEscape(KeyEvent event) {
        if (event.getCode() == KeyCode.ESCAPE) {
            closeBar();
            event.consume();
        }
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
        // 可视化页签上把焦点塞给隐藏的源码区没有意义（用户看不见它），焦点留在原地即可
        if (!visualTabActive.getAsBoolean()) {
            contentArea.requestFocus();
        }
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
        String replacement = replaceField.getText();
        if (onVisualTab()) {
            if (!visualReady()) {
                return;
            }
            // 可视化路径：替换改动经页面 push() 走既有回写（onEdited 内记撤销步 + 脏标记），
            // 不再 beginChange——那会在替换后重复拍一张快照
            String result = visualSession.replaceOne(keyword, replacement, caseSensitive);
            if ("hit".equals(result)) {
                findStatus("已替换 1 处");
            }
            findInChapter(true);
            return;
        }
        if (TextSearch.matches(contentArea.getSelectedText(), keyword, caseSensitive)) {
            // 显式开一个编辑步：只依赖输入合并机制的话，600ms 静默窗口内的首次替换不会进历史
            beginChange.run();
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
            // 全书范围：直接改各章资源，与本页签无关（结束后 reloadEditor 会重载编辑视图）
            replaceAllInWholeBook(keyword, replacement, caseSensitive);
            return;
        }
        if (onVisualTab()) {
            if (!visualReady()) {
                return;
            }
            int count = visualSession.replaceAllInChapter(keyword, replacement, caseSensitive);
            if (count <= 0) {
                setStatus.accept("当前章节未找到：" + keyword);
                findStatus("未找到");
                return;
            }
            // 脏标记与撤销步由页面 push() 的 onEdited 链路负责，这里不重复
            setStatus.accept("当前章节共替换 " + count + " 处");
            findStatus("本章 " + count + " 处");
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

    /** 全书范围替换：改各章资源 → 重载编辑器 → 刷新预览（与页签无关）。 */
    private void replaceAllInWholeBook(String keyword, String replacement, boolean caseSensitive) {
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
    }

    /** 可视化页签激活但编辑视图尚未就绪（加载中）：给状态而不是静默无效。 */
    private boolean visualReady() {
        if (visualSession == null || !visualSession.ready()) {
            findStatus("编辑视图尚未就绪，请稍后再试");
            return false;
        }
        return true;
    }

    /** 是否停在可视化编辑页签。 */
    private boolean onVisualTab() {
        return visualTabActive != null && visualTabActive.getAsBoolean();
    }

    private void findInChapter(boolean forward) {
        String keyword = findField.getText();
        if (keyword.isEmpty()) {
            findStatus("请输入查找内容");
            return;
        }
        if (onVisualTab()) {
            if (!visualReady()) {
                return;
            }
            String result = visualSession.find(keyword, !forward, caseSensitiveCheck.isSelected());
            if (result == null || "miss".equals(result)) {
                findStatus("未找到");
                return;
            }
            // "wrap" → 已回绕提示；"hit" → 清空状态（与源码区 FindOps.wrapStatus 同口径）
            findStatus("wrap".equals(result) ? (forward ? "已回到开头" : "已回到结尾") : "");
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
                ? TextSearch.indexOf(text, keyword, from, caseSensitive)
                : TextSearch.lastIndexOf(text, keyword, from, caseSensitive);
        int wrapHit = -1;
        if (primaryHit < 0) {
            wrapHit = forward
                    ? TextSearch.indexOf(text, keyword, 0, caseSensitive)
                    : TextSearch.lastIndexOf(text, keyword, wrapTarget, caseSensitive);
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

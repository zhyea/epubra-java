package org.chobit.epubra.app.editor;

import javafx.application.Platform;
import javafx.scene.control.TextArea;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import netscape.javascript.JSObject;
import org.chobit.epubra.app.context.BookContext;
import org.chobit.epubra.app.ui.model.ChapterNode;

import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 可视化编辑会话：{@code visualEditorView}（WebView + contenteditable）与正文之间的双向同步。
 *
 * <p>搬迁自 {@code MainController}（拆分批次 B，纯搬运）。归拢的是「只有这个会话才知道」的
 * 那部分状态与协议：{@code loaded} 标记、JS 桥、序列化回写、光标处格式命令。
 * <b>不含</b>跨面板编排（切 tab 时先 flush 源码区、再决定 reload 还是刷预览）——那属于
 * {@code MainController}，会话不应反向依赖它，否则会绕成回调环。
 *
 * <p>三条不能回退的纪律：
 * <ol>
 *   <li>{@link #loaded} 必须严格维护——编辑视图在非「编辑」tab 时不会随章节切换重载，
 *       误判为「已加载」会把上一章正文写到新章节里；</li>
 *   <li>回写进书前一律过 {@link PreviewHtml#stripInjectedScript(String)}（回写净化
 *       三道防线之三，见 #51）；</li>
 *   <li>JS→Java 命令返回 {@code false} 会让调用方退回源码区插入——可视化命令即便
 *       「有意不作为」也必须返回 {@code true}（#61）。</li>
 * </ol>
 *
 * <p>副作用一律经构造期注入的钩子走出，本类不持有 {@code undoActivity} / 状态栏：
 * {@code undoStep}（一次输入编辑步，与源码区共用同一本账）、{@code undoAction} /
 * {@code redoAction}（走应用级快照）、{@code markDirty}、{@code statusRefresh}、
 * {@code toolbarState}（有无状态的格式按钮点亮）、{@code styleState}（字体 / 字号 /
 * 颜色 / 对齐四个带值控件的回显）。
 */
public final class VisualEditorSession {

    /** JS 桥挂载到的 window 成员名；页面脚本按这个名字回调。 */
    private static final String BRIDGE_MEMBER = "epubraBridge";

    /** 带参数格式化命令传值的临时成员名（URL 里可能有引号与反斜杠，不拼进脚本）。 */
    private static final String PENDING_VALUE_MEMBER = "__epubraPendingValue";

    /** 插入 HTML 片段传值的临时成员名（标签里带引号，手工转义容易出错）。 */
    private static final String PENDING_HTML_MEMBER = "__epubraPendingHtml";

    private final BookContext ctx;
    private final WebView visualEditorView;
    private final TextArea contentArea;
    private final Supplier<ChapterNode> currentChapter;
    private final Function<ChapterNode, String> baseHref;
    private final Supplier<Theme> theme;
    private final Runnable undoStep;
    private final Runnable undoAction;
    private final Runnable redoAction;
    private final Runnable markDirty;
    private final Runnable statusRefresh;
    private final Consumer<String> toolbarState;
    private final Consumer<String> styleState;

    /** 本次加载是否带实际章节内容（章节存在且有资源）；「页面内容对应当前章节」还要看加载序号。 */
    private boolean loaded;

    /**
     * 页面加载序号：{@code reload()} 每发起一次加载就 {@code ++loadSeq}；加载**完成**
     * （loadWorker SUCCEEDED）时 {@code loadedSeq} 才追上来。两者相等 ⟺ 页面里的内容
     * 就是当前章节的最新加载结果。
     *
     * <p>为什么必须有这层：{@code loadContent} 是<b>异步</b>的——撤销 / 重做回读后立刻
     * 再按一次撤销，第二次的 {@link #flush()} 会从<b>还没重载完的旧文档</b>里拉内容写回书里，
     * 等于把第一次撤销悄悄抹掉，还往撤销栈里塞一条幽灵快照；表现在用户那里就是
     * 「多次撤销后重做的内容缺失、撤销像可以无限按」。所以加载完成前一律视为
     * 「页面内容对不上当前章节」，flush / 命令全部拒绝执行。
     */
    private int loadSeq;
    private int loadedSeq = -1;

    /** 页面里的内容是否就是当前章节（本次加载带内容，且加载已完成）。 */
    private boolean pageCurrent() {
        return loaded && loadedSeq == loadSeq;
    }

    public VisualEditorSession(BookContext ctx, WebView visualEditorView, TextArea contentArea,
                               Supplier<ChapterNode> currentChapter,
                               Function<ChapterNode, String> baseHref,
                               Supplier<Theme> theme,
                               Runnable undoStep, Runnable undoAction, Runnable redoAction,
                               Runnable markDirty, Runnable statusRefresh,
                               Consumer<String> toolbarState,
                               Consumer<String> styleState) {
        this.ctx = ctx;
        this.visualEditorView = visualEditorView;
        this.contentArea = contentArea;
        this.currentChapter = currentChapter;
        this.baseHref = baseHref;
        this.theme = theme;
        this.undoStep = undoStep;
        this.undoAction = undoAction;
        this.redoAction = redoAction;
        this.markDirty = markDirty;
        this.statusRefresh = statusRefresh;
        this.toolbarState = toolbarState;
        this.styleState = styleState;
        visualEditorView.getEngine().getLoadWorker().stateProperty().addListener((obs, old, state) -> {
            if (state == javafx.concurrent.Worker.State.SUCCEEDED) {
                loadedSeq = loadSeq;
            }
        });
    }

    /**
     * 安装 JS 桥：编辑视图里的改动经 {@code window.epubraBridge.onEdited(xhtml)} 回传。
     *
     * <p>window 在每次文档加载后都是新对象，桥必须跟着重装，否则 {@code loadContent}
     * 之后旧 window 上的 {@code epubraBridge} 就没了，页面里的改动再也回不来。
     *
     * <p>回调在 FX 线程触发（WebView 的 JS 引擎就在 FX 线程上跑），可直接改 {@code Book}。
     */
    public void installBridge() {
        JSObject window = (JSObject) visualEditorView.getEngine().executeScript("window");
        window.setMember(BRIDGE_MEMBER, new VisualEditBridge());
    }

    /**
     * 把当前章节载入可视化编辑器。
     *
     * <p>只在切到「编辑」tab 或换章节时调用——不为每次击键重建文档，否则输入会被打断。
     */
    public void reload() {
        ChapterNode current = currentChapter.get();
        String xhtml = current == null || current.resource() == null
                ? ""
                : current.resource().asString();
        loaded = current != null && current.resource() != null;
        // loadContent 异步：完成前 pageCurrent() 为假，flush / 命令一律拒绝（见字段注释）
        loadSeq++;
        visualEditorView.getEngine().loadContent(
                PreviewHtml.editableDocument(xhtml, theme.get(), baseHref.apply(current)),
                "application/xhtml+xml");
    }

    /** 把编辑视图标记为「不对应当前章节」——切章节或正文被程序化改写后必须调用。 */
    public void invalidate() {
        loaded = false;
    }

    /** 编辑视图里的内容是否对应当前章节（加载已完成；加载进行中一律视为不对应）。 */
    public boolean isLoaded() {
        return pageCurrent();
    }

    /** 可视化编辑器是否可用于施加上下文命令（已加载完成且内容对应当前章节）。 */
    public boolean ready() {
        return visualEditorView != null && pageCurrent();
    }

    /**
     * 主动把编辑器里的最新内容拉回正文（不等 600ms 节流）。
     *
     * <p>切章节 / 保存前调用：用户可能刚敲完就点了别处，节流还没到点。
     *
     * @return 是否确实写回了内容；调用方据此避免再用旧的源码文本覆盖
     */
    public boolean flush() {
        // 页面还没重载完成时（撤销回读后紧接着的再撤销正落在这个窗口里），拉到的是
        // 旧文档的内容——写回书里等于把刚做的撤销抹掉，还会记下幽灵快照
        if (visualEditorView == null || !pageCurrent()) {
            return false;
        }
        try {
            Object result = visualEditorView.getEngine().executeScript("window.epubraSerialize()");
            if (result instanceof String raw && !raw.isBlank()) {
                // 回写净化三道防线之三：任何序列化层的疏漏都不能污染正文（#51 脚本曾整段混进章节）
                String xhtml = PreviewHtml.stripInjectedScript(raw);
                ChapterNode current = currentChapter.get();
                if (current != null && current.resource() != null
                        && !xhtml.equals(current.resource().asString())) {
                    // 同 onEdited：复用输入编辑步的合并逻辑，且不能调 beginChange()（递归）
                    undoStep.run();
                    current.resource().setString(xhtml);
                    // 源码区也要跟上（onEdited 路径做了、这里漏了曾导致两 tab 不同步）：
                    // 否则紧随其后的切 tab 会经 flushCurrentChapter 用旧源码文本
                    // 把刚写进资源的可视化改动冲掉
                    syncSource(xhtml);
                    ctx.invalidateWordCounts();
                    markDirty.run();
                    return true;
                }
            }
        } catch (RuntimeException ignored) {
            // 页面尚未加载完 / 脚本不可用：忽略，正文保持原样
        }
        return false;
    }

    /** 对可视化编辑器施加一次富文本操作（不带参数的重载）。 */
    public boolean format(String kind) {
        return format(kind, null);
    }

    /**
     * 对可视化编辑器施加一次富文本操作。
     *
     * <p>kind 取值：{@code paragraph} / {@code heading} / {@code quote} / {@code list} /
     * {@code ol} / {@code rule} / {@code bold} / {@code italic} / {@code underline} /
     * {@code strike} / {@code code} / {@code link} / {@code unlink} /
     * {@code font} / {@code size} / {@code size-up} / {@code size-down} / {@code color} /
     * {@code align} / {@code clear}，全部是硬编码的 ASCII 字面量，可直接拼进脚本，无需转义。
     *
     * @param value 仅 {@code link}（href）/ {@code font} / {@code size} / {@code color} /
     *              {@code align} 需要；经 {@code window} 上的临时成员传入
     * @return 是否真的改了内容；false 表示编辑视图还没就绪或命令被拒绝
     */
    public boolean format(String kind, String value) {
        if (!ready()) {
            return false;
        }
        WebEngine engine = visualEditorView.getEngine();
        try {
            if (value == null) {
                Object ok = engine.executeScript("window.epubraFormat('" + kind + "')");
                return Boolean.TRUE.equals(ok);
            }
            JSObject window = (JSObject) engine.executeScript("window");
            window.setMember(PENDING_VALUE_MEMBER, value);
            Object ok = engine.executeScript(
                    "window.epubraFormat('" + kind + "', window." + PENDING_VALUE_MEMBER + ")");
            window.setMember(PENDING_VALUE_MEMBER, null);
            return Boolean.TRUE.equals(ok);
        } catch (RuntimeException notLoadedYet) {
            return false;
        }
    }

    /**
     * 把一段 XHTML 片段插到可视化编辑器的光标处（图片等）。
     *
     * <p>片段先经 {@code window} 上的临时成员传进去，而不是拼进脚本字符串——
     * 标签里带引号，手工转义容易出错。
     */
    public boolean insertHtml(String xhtml) {
        if (!ready() || xhtml == null) {
            return false;
        }
        WebEngine engine = visualEditorView.getEngine();
        try {
            JSObject window = (JSObject) engine.executeScript("window");
            window.setMember(PENDING_HTML_MEMBER, xhtml);
            Object ok = engine.executeScript(
                    "window.epubraInsertHtml(window." + PENDING_HTML_MEMBER + ")");
            window.setMember(PENDING_HTML_MEMBER, null);
            return Boolean.TRUE.equals(ok);
        } catch (RuntimeException notLoadedYet) {
            return false;
        }
    }

    /** 读取可视化编辑器里光标所在链接的 href；编辑器未就绪或不在链接内返回空串。 */
    public String queryLinkHref() {
        try {
            Object href = visualEditorView.getEngine().executeScript("window.epubraQueryLink()");
            return href instanceof String s ? s : "";
        } catch (RuntimeException notLoadedYet) {
            return "";
        }
    }

    // ---------------------------------------------------------------- 查找 / 替换
    //
    // 查找条原来只作用在源码区 TextArea：可视化页签上点「查找」其实在隐藏的源码区里
    // 选中了，页面上毫无可见变化（用户看到「查找没有生效」）。这里把查找 / 替换路由进
    // 可视化编辑器；关键词与替换词经 window 临时成员传入，与格式命令同口径（不拼脚本）。

    /** 查找条在 window 上的临时成员：关键词 / 替换词 / 大小写开关。 */
    private static final String FIND_KEYWORD_MEMBER = "__epubraFindKeyword";
    private static final String FIND_REPLACEMENT_MEMBER = "__epubraFindReplacement";
    private static final String FIND_CASE_MEMBER = "__epubraFindCase";

    /**
     * 在可视化编辑器内查找下一处 / 上一处（含回绕），命中处选中并滚到可见。
     *
     * @param backward true = 上一个
     * @return "hit"（直接命中）/ "wrap"（回绕命中）/ "miss"（未找到）；编辑视图未就绪返回 null
     */
    public String find(String keyword, boolean backward, boolean caseSensitive) {
        String script = backward
                ? "window.epubraFindPrev(window." + FIND_KEYWORD_MEMBER
                        + ", window." + FIND_CASE_MEMBER + ")"
                : "window.epubraFindNext(window." + FIND_KEYWORD_MEMBER
                        + ", window." + FIND_CASE_MEMBER + ")";
        Object out = callWithFindMembers(script, keyword, null, caseSensitive);
        return out instanceof String s ? s : null;
    }

    /**
     * 在可视化编辑器内替换当前选中的一处（选区文本与关键词一致才动手，与源码区同口径）。
     *
     * @return "hit"（已替换）/ "nomatch"（当前选区不是命中）/ "miss"（没有选区）；
     *         编辑视图未就绪返回 null
     */
    public String replaceOne(String keyword, String replacement, boolean caseSensitive) {
        String script = "window.epubraReplaceOne(window." + FIND_KEYWORD_MEMBER
                + ", window." + FIND_REPLACEMENT_MEMBER
                + ", window." + FIND_CASE_MEMBER + ")";
        Object out = callWithFindMembers(script, keyword, replacement, caseSensitive);
        return out instanceof String s ? s : null;
    }

    /**
     * 在可视化编辑器内替换本章全部命中（命中可跨行内元素），改动经 push() 走既有回写。
     *
     * @return 命中数；编辑视图未就绪返回 -1
     */
    public int replaceAllInChapter(String keyword, String replacement, boolean caseSensitive) {
        String script = "window.epubraReplaceAll(window." + FIND_KEYWORD_MEMBER
                + ", window." + FIND_REPLACEMENT_MEMBER
                + ", window." + FIND_CASE_MEMBER + ")";
        Object out = callWithFindMembers(script, keyword, replacement, caseSensitive);
        return out instanceof Number n ? n.intValue() : -1;
    }

    /** 查找类命令的公共通道：挂临时成员 → 执行 → 摘成员。 */
    private Object callWithFindMembers(String script, String keyword,
                                       String replacement, boolean caseSensitive) {
        if (!ready()) {
            return null;
        }
        try {
            WebEngine engine = visualEditorView.getEngine();
            JSObject window = (JSObject) engine.executeScript("window");
            window.setMember(FIND_KEYWORD_MEMBER, keyword == null ? "" : keyword);
            if (replacement != null) {
                window.setMember(FIND_REPLACEMENT_MEMBER, replacement);
            }
            window.setMember(FIND_CASE_MEMBER, Boolean.valueOf(caseSensitive));
            Object out = engine.executeScript(script);
            window.setMember(FIND_KEYWORD_MEMBER, null);
            window.setMember(FIND_CASE_MEMBER, null);
            if (replacement != null) {
                window.setMember(FIND_REPLACEMENT_MEMBER, null);
            }
            return out;
        } catch (RuntimeException notLoadedYet) {
            return null;
        }
    }

    /** 把可视化编辑的结果同步到源码 tab，避免两个 tab 显示的内容不一致。 */
    private void syncSource(String xhtml) {
        if (contentArea == null || contentArea.isDisabled()) {
            return;
        }
        // 用户正停在源码 tab 打字时不要覆盖他的输入——以他为权威，等他改完自然写回正文
        if (contentArea.isFocused()) {
            return;
        }
        ctx.setLoading(true);
        try {
            int caret = contentArea.getCaretPosition();
            contentArea.setText(xhtml);
            contentArea.positionCaret(Math.min(caret, xhtml.length()));
        } finally {
            ctx.setLoading(false);
        }
    }

    /** 暴露给页面脚本的回写入口；必须是 public 类 + public 方法，桥才能反射调用。 */
    public final class VisualEditBridge {

        public void onEdited(String xhtml) {
            if (xhtml == null || xhtml.isBlank()) {
                return;
            }
            // 回写净化三道防线之三（同 flush）：回写进书前再剥一次编辑脚本
            xhtml = PreviewHtml.stripInjectedScript(xhtml);
            ChapterNode current = currentChapter.get();
            if (current == null || current.resource() == null) {
                return;
            }
            String existing = current.resource().asString();
            if (existing != null && existing.equals(xhtml)) {
                return; // 内容没变（例如只是切了焦点）——不打扰撤销栈
            }
            // 与源码编辑同样走 onTextInput：一次连续输入只记一次快照，600ms 静默合并。
            // 不能用 beginChange()——它会 commitPendingEdits() → flushCurrentChapter()
            // → flush() → 回到这里，构成递归。
            undoStep.run();
            current.resource().setString(xhtml);
            ctx.invalidateWordCounts();
            markDirty.run();
            syncSource(xhtml);
            statusRefresh.run();
        }

        /**
         * 光标 / 选区变化：把当前生效的格式名传给 Java，点亮工具条。
         *
         * <p>页面在 {@code selectionchange} / {@code keyup} / {@code mouseup} 时主动上报，
         * 不需要 Java 轮询。
         */
        public void onSelectionChanged(String activeFormats) {
            toolbarState.accept(activeFormats);
        }

        /**
         * 光标 / 选区处的内联样式（字体 / 字号 / 颜色）与块级对齐，格式
         * {@code "font-size=18px;text-align=center"}；空串表示全是默认档。
         *
         * <p>与 {@link #onSelectionChanged(String)} 同源、同时机上报，只是走的通道不同：
         * 那一条点亮「加粗 / 斜体」这类有无状态的按钮，这一条回显字体、字号、颜色、对齐
         * 四个带值控件。分开是因为消费方不同——{@code EditorToolbarController} 与
         * {@code EditorStyleControls} 各管一摊，不该为了省一次回调把两者拴在一起。
         */
        public void onStyleChanged(String state) {
            styleState.accept(state);
        }

        /**
         * Ctrl+Z / Ctrl+Y：走应用级快照撤销，与菜单、源码区共用一本账。
         *
         * <p>必须挪到下一个脉冲执行——此刻还在 JS 调用栈里，而撤销会重载整个编辑视图
         * （{@code loadContent}），在 {@code executeScript} 中途换页面是不安全的。
         */
        public void onUndo() {
            Platform.runLater(undoAction);
        }

        /** Ctrl+Y / Ctrl+Shift+Z：同 {@link #onUndo()}，只是反转方向。 */
        public void onRedo() {
            Platform.runLater(redoAction);
        }
    }
}

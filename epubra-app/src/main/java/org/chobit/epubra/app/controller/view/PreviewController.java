package org.chobit.epubra.app.controller.view;

import javafx.scene.control.MenuItem;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextArea;
import javafx.scene.web.WebView;
import org.chobit.epubra.app.context.BookContext;
import org.chobit.epubra.app.editor.PreviewHtml;
import org.chobit.epubra.app.editor.PreviewMirror;
import org.chobit.epubra.app.editor.Theme;
import org.chobit.epubra.app.resource.ResourceOps;
import org.chobit.epubra.app.ui.FxNodes;
import org.chobit.epubra.app.ui.model.ChapterNode;
import org.chobit.epubra.lib.domain.Resource;
import org.chobit.epubra.lib.util.Hrefs;
import org.chobit.epubra.lib.util.ResourceReferences;

import java.util.function.Supplier;

/**
 * 预览区归属者：渲染、相对引用基准（资源镜像）、并排预览模式。
 *
 * <p>搬迁自 {@code MainController}（拆分批次 C，纯搬运）。归属理由：这三件事共用同一份状态——
 * {@code loadContent} 的页面源是 {@code about:blank}，预览与可视化编辑器里的相对引用全靠
 * {@link PreviewMirror} 提供 {@code <base>} 才解析得出来；并排模式则是预览容器的呈现方式。
 *
 * <p>两处依赖由 {@code MainController} 显式注入，禁止自带副本：
 * <ul>
 *   <li>「当前章节」——归 {@code TocController} 持有，漏接线是静默的（曾致插图恒报「请先选择章节」）；</li>
 *   <li>tab 索引——与 {@code main-window.fxml} 的顺序一一对应，由
 *       {@code VisualEditorTabUiTest} 钉死，这里只接受参数不做假设。</li>
 * </ul>
 */
public final class PreviewController {

    private final BookContext ctx;
    private final WebView previewView;
    private final TextArea contentArea;
    private final TabPane editorTabs;
    private final SplitPane splitPreviewPane;
    private final MenuItem splitPreviewItem;
    private final Supplier<ChapterNode> currentChapter;
    private final Supplier<Theme> theme;
    private final int sourceTabIndex;
    private final int previewTabIndex;

    /**
     * 预览 / 可视化编辑器的资源镜像（{@code ~/.Epubra/preview/}）。
     *
     * <p>存在意义：{@code loadContent} 的页面源是 {@code about:blank}，不注入 {@code <base>}
     * 的话正文里的相对图片引用一个也加载不出来。构造只建对象，不碰磁盘。
     */
    private final PreviewMirror previewMirror;

    /**
     * 编辑区呈现模式：{@code false} = 内容与预览分标签，{@code true} = 左右并排对照。
     */
    private boolean splitPreview;

    public PreviewController(BookContext ctx, WebView previewView, TextArea contentArea,
                             TabPane editorTabs, SplitPane splitPreviewPane, MenuItem splitPreviewItem,
                             Supplier<ChapterNode> currentChapter, Supplier<Theme> theme,
                             int sourceTabIndex, int previewTabIndex) {
        this.ctx = ctx;
        this.previewView = previewView;
        this.contentArea = contentArea;
        this.editorTabs = editorTabs;
        this.splitPreviewPane = splitPreviewPane;
        this.splitPreviewItem = splitPreviewItem;
        this.currentChapter = currentChapter;
        this.theme = theme;
        this.sourceTabIndex = sourceTabIndex;
        this.previewTabIndex = previewTabIndex;
        this.previewMirror = PreviewMirror.forUserData();
    }

    /** 按当前章节与主题重渲染预览区；没有打开的章节时显示空白文档。 */
    public void refresh() {
        ChapterNode current = currentChapter.get();
        if (current == null || current.resource() == null) {
            previewView.getEngine().loadContent(PreviewHtml.emptyDocument(theme.get()));
            return;
        }
        // 预览区是 WebView，吃不到 -epubra-* 变量，改为往 XHTML 里注入一段内联主题样式；
        // 相对引用（图片等）则靠 <base> 指向资源镜像才解析得出来。
        previewView.getEngine().loadContent(
                PreviewHtml.withBaseHref(
                        PreviewHtml.withTheme(current.resource().asString(), theme.get()),
                        baseHref(current)),
                "application/xhtml+xml");
    }

    /**
     * 相对引用的解析基准：给定章节在资源镜像里的目录 URI。
     *
     * <p>WebView 走 {@code loadContent}，页面源是 {@code about:blank}，没有基准时正文里的
     * {@code <img src="../images/a.png"/>} 会静默加载失败。返回 {@code null} 表示镜像不可用，
     * 此时退回「不注入 base」的老行为——图片显示不出来，但正文一切照常。
     */
    public String baseHref(ChapterNode current) {
        if (previewMirror == null || current == null || current.resource() == null) {
            return null;
        }
        return previewMirror.baseHrefFor(ctx.book(), current.resource().href());
    }

    /**
     * 把片段里 {@code <img>} 引用的资源补写进预览镜像。
     *
     * <p>调用方是「插入到可视化编辑器」路径：新插的图不在镜像里（镜像只同步「章节可达」资源，
     * 而章节文本此刻尚未回写），不补写的话 img 进了 DOM 也解析不到文件——用户看到的
     * 就是「插了却不显示」（#50）。
     *
     * <p>src 是相对章节目录的引用，先还原成容器内路径再找资源；找不到（外部地址、资源缺失）
     * 就跳过——镜像只服务「能解析到的图」。写盘是几个小文件的 {@code Files.write}，与
     * {@link #baseHref} 的同步镜像同一口径，不为此起后台任务。
     */
    public void mirrorImagesReferencedBy(String xhtml) {
        if (previewMirror == null || ctx.book() == null || xhtml == null || xhtml.isEmpty()) {
            return;
        }
        ChapterNode current = currentChapter.get();
        if (current == null || current.resource() == null) {
            return;
        }
        String baseDir = Hrefs.parentDirectory(current.resource().href());
        for (String src : ResourceOps.extractImageSrcs(xhtml)) {
            String target = ResourceReferences.resolveTarget(baseDir, src);
            if (target == null) {
                continue;
            }
            Resource resource = ResourceReferences.findResource(ctx.book().resources(), target).resource();
            if (resource != null) {
                previewMirror.mirrorResource(ctx.book(), resource);
            }
        }
    }

    /**
     * 「视图 → 并排预览」开关：标签模式 ↔ 左右并排对照，返回切换后的状态。
     *
     * <p>长文档在标签模式下要反复切 Tab 才能看到改动效果，并排模式让源码与渲染结果同屏，
     * 省掉切换成本。状态提示由调用方负责——本类只认呈现，不认识状态栏。
     */
    public boolean toggleSplit() {
        splitPreview = !splitPreview;
        applyMode();
        refresh();
        return splitPreview;
    }

    /** 当前是否处于并排预览模式。 */
    public boolean splitEnabled() {
        return splitPreview;
    }

    /**
     * 把 contentArea 与 previewView 在两个父容器之间搬移。
     *
     * <p>一个 Node 只能挂在一个父容器下，所以两种模式不能各持一份，只能切一次搬一次。
     * 摘 Tab 内容时先 {@code setContent(null)}——直接把节点塞进 SplitPane 会让 JavaFX
     * 抛「节点已有父容器」异常。
     */
    private void applyMode() {
        if (splitPreviewPane == null || editorTabs == null
                || editorTabs.getTabs().size() <= previewTabIndex) {
            return;
        }
        // tab 顺序：0 编辑 / 1 源码 / 2 预览；并排模式仍只搬源码与预览两个节点，
        // 编辑 tab 留在标签页里（可视化编辑与并排预览互斥使用）。
        if (splitPreview) {
            editorTabs.getTabs().get(sourceTabIndex).setContent(null);
            editorTabs.getTabs().get(previewTabIndex).setContent(null);
            splitPreviewPane.getItems().setAll(contentArea, previewView);
        } else {
            splitPreviewPane.getItems().clear();
            editorTabs.getTabs().get(sourceTabIndex).setContent(contentArea);
            editorTabs.getTabs().get(previewTabIndex).setContent(previewView);
        }
        // 两个容器互斥显示：visible 与 managed 必须同步，否则隐藏的那个仍占 StackPane 布局
        FxNodes.setVisibleManaged(editorTabs, !splitPreview);
        FxNodes.setVisibleManaged(splitPreviewPane, splitPreview);
        if (splitPreviewItem != null) {
            splitPreviewItem.setText(splitPreview ? "标签预览" : "并排预览");
        }
    }
}

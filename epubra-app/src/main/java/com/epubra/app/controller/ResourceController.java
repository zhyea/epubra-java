package com.epubra.app.controller;

import com.epubra.app.support.BookContext;
import com.epubra.app.support.CoverOps;
import com.epubra.app.support.ResourceOps;
import com.epubra.epublib.domain.MediaTypes;
import com.epubra.epublib.domain.Resource;
import com.epubra.epublib.util.Hrefs;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.stage.FileChooser;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * 资源面板控制器——导入 / 导出 / 删除 / 设为封面 / 插入图片正文 / 清理未引用资源。
 *
 * <p>作为 {@code resource-view.fxml} 的 {@code fx:controller} 由 FXML 实例化：
 * 面板内的表格与按钮经 {@code @FXML} 注入并直接绑定本类方法；编辑区节点
 * （{@code editorTabs} / {@code contentArea} 属主 FXML）与回调在父控制器
 * {@code initialize()} 阶段通过 {@link #bind} 注入。本类不得定义 {@code initialize()}。
 *
 * <p>{@link ResourceOps} 提供纯逻辑（是否被引用、HTML 标签拼接等），本类负责把它们包装
 * 成可观察的 UI 行为。封面相关状态判定走 {@link CoverOps}，「设为封面 / 取消封面」
 * 按钮文本随选中行 {@link ResourceRow#isCover()} 自动切换。
 */
public class ResourceController {

    @FXML
    private TableView<ResourceRow> resourceTable;
    @FXML
    private Button setCoverButton;

    private BookContext ctx;
    private TabPane editorTabs;
    private TextArea contentArea;
    private Runnable beginChange;
    private Runnable markDirty;
    private Runnable refreshAll;
    private Runnable refreshResources;
    private Runnable refreshCoverCard;
    private Runnable updateStatus;
    private Consumer<String> setStatus;
    private Consumer<String> warn;
    private BooleanSupplier confirm;
    private ErrorReporter showError;

    /** FXML 加载后由父控制器注入运行时依赖；必须在任何 onAction 触发前完成。 */
    public void bind(BookContext ctx, TabPane editorTabs, TextArea contentArea,
                     Runnable beginChange, Runnable markDirty,
                     Runnable refreshAll, Runnable refreshResources,
                     Runnable refreshCoverCard,
                     Runnable updateStatus, Consumer<String> setStatus,
                     Consumer<String> warn, BooleanSupplier confirm,
                     ErrorReporter showError) {
        this.ctx = ctx;
        this.editorTabs = editorTabs;
        this.contentArea = contentArea;
        this.beginChange = beginChange;
        this.markDirty = markDirty;
        this.refreshAll = refreshAll;
        this.refreshResources = refreshResources;
        this.refreshCoverCard = refreshCoverCard;
        this.updateStatus = updateStatus;
        this.setStatus = setStatus;
        this.warn = warn;
        this.confirm = confirm;
        this.showError = showError;
        wireCoverButtonRefresh();
    }

    /**
     * 选中行变化时刷新「设为封面 / 取消封面」按钮文本：选中行已是封面 → 切到「取消封面」，
     * 否则保留「设为封面」。
     *
     * <p>不挂横向监听「书换了封面」（由 refresh() 顺带刷新）——书中任何 setCover 调用
     * 最终都会调 {@code refreshAll} / {@code refreshResources}，本表被整体重建，
     * 下一轮取行就会拿到正确的徽章与按钮文本。
     */
    private void wireCoverButtonRefresh() {
        if (resourceTable == null || setCoverButton == null) {
            return;
        }
        resourceTable.getSelectionModel().selectedItemProperty().addListener(
                (obs, oldRow, newRow) -> refreshCoverButtonText(newRow));
    }

    private void refreshCoverButtonText(ResourceRow row) {
        if (setCoverButton == null) {
            return;
        }
        if (row != null && row.isCover()) {
            setCoverButton.setText("取消封面");
        } else {
            setCoverButton.setText("设为封面");
        }
    }

    /** 资源列表初始化/重渲染：nav/NCX 资源不展示，由 {@link BookContext} 的 Epub 写出流程维护。 */
    public void refresh() {
        if (resourceTable == null || ctx.book() == null || ctx.book().resources() == null) {
            return;
        }
        Resource nav = ctx.book().navResource();
        List<ResourceRow> rows = new ArrayList<>();
        String currentCoverId = ctx.book().coverResourceId();
        for (Resource resource : ctx.book().resources().all()) {
            if (resource == nav || resource.isNavDocument() || MediaTypes.NCX.equals(resource.mediaType())) {
                continue;
            }
            ResourceRow row = new ResourceRow(resource);
            row.markCoverBadgeFor(currentCoverId);
            rows.add(row);
        }
        resourceTable.getItems().setAll(rows);
        refreshCoverButtonText(resourceTable.getSelectionModel().getSelectedItem());
    }

    public void importResources() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("导入资源");
        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("图片 / 样式 / 字体",
                        "*.png", "*.jpg", "*.jpeg", "*.gif", "*.webp", "*.svg",
                        "*.css", "*.ttf", "*.otf", "*.woff", "*.woff2"),
                new FileChooser.ExtensionFilter("所有文件", "*.*"));
        List<File> files = chooser.showOpenMultipleDialog(ctx.stage());
        if (files == null || files.isEmpty()) {
            return;
        }
        beginChange.run();
        int imported = 0;
        for (File file : files) {
            try {
                ctx.book().addResource(file.toPath());
                imported++;
            } catch (IOException ex) {
                showError.report("导入失败", "无法读取 " + file.getName(), ex);
            }
        }
        markDirty.run();
        refreshResources.run();
        updateStatus.run();
        setStatus.accept("已导入 " + imported + " 个资源");
    }

    public void exportSelected() {
        ResourceRow row = selectedRow();
        if (row == null) {
            warn.accept("请先在资源列表中选择一项");
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle("导出资源");
        chooser.setInitialFileName(row.getName());
        File file = chooser.showSaveDialog(ctx.stage());
        if (file == null) {
            return;
        }
        try {
            Files.write(file.toPath(), row.getResource().data());
            setStatus.accept("已导出到 " + file.getName());
        } catch (IOException ex) {
            showError.report("导出失败", "无法写入 " + file.getAbsolutePath(), ex);
        }
    }

    public void deleteSelected() {
        ResourceRow row = selectedRow();
        if (row == null) {
            warn.accept("请先在资源列表中选择一项");
            return;
        }
        Resource resource = row.getResource();
        String message = "确定删除资源「" + row.getName() + "」？";
        if (ResourceOps.isReferencedByChapters(ctx.book(), resource)) {
            message += "\n\n注意：正文中存在对它的引用，删除后相关图片或样式将无法显示。";
        }
        if (!confirm.getAsBoolean()) {
            return;
        }
        beginChange.run();
        ctx.book().removeResource(resource);
        markDirty.run();
        refreshAll.run();
        setStatus.accept("已删除资源：" + row.getName());
    }

    /**
     * 「设为封面 / 取消封面」按钮点击：根据当前按钮文本分支——选中的行已是封面则清除，
     * 否则设为封面。
     *
     * <p>对未选中行、非图片行做防御：不允许把非图资源设为封面。
     */
    public void setCoverFromSelected() {
        ResourceRow row = selectedRow();
        if (row == null) {
            warn.accept("请先在资源列表中选择一张图片");
            return;
        }
        boolean cancelling = row.isCover();
        if (!cancelling && !CoverOps.pick(ctx.book(), row.getResource())) {
            warn.accept("封面必须是图片资源（PNG / JPEG / GIF / WebP / SVG）");
            return;
        }
        beginChange.run();
        if (cancelling) {
            CoverOps.clear(ctx.book());
            setStatus.accept("已移除封面");
        } else {
            CoverOps.set(ctx.book(), row.getResource());
            setStatus.accept("已设为封面：" + row.getName());
        }
        markDirty.run();
        refreshResources.run();
        if (refreshCoverCard != null) {
            refreshCoverCard.run();
        }
    }

    public void insertSelectedImageIntoChapter() {
        ResourceRow row = selectedRow();
        if (row == null) {
            warn.accept("请先在资源列表中选择一张图片");
            return;
        }
        if (!row.isImage()) {
            warn.accept("只能向正文插入图片资源");
            return;
        }
        ChapterNode current = ctx.currentNode();
        if (current == null || current.resource() == null) {
            warn.accept("请先在左侧目录中选择要插入图片的章节");
            return;
        }
        String tag = ResourceOps.buildInsertImageTag(
                current.resource().href(), row.getResource().href(), row.getName());
        contentArea.insertText(contentArea.getAnchor(), tag);
        editorTabs.getSelectionModel().selectFirst();
        setStatus.accept("已在正文中插入：" + row.getName());
    }

    public void cleanupUnused() {
        List<Resource> orphans = ctx.book().unreferencedResources();
        if (orphans.isEmpty()) {
            setStatus.accept("没有未被引用的资源");
            return;
        }
        String names = orphans.stream()
                .limit(12)
                .map(Resource::fileName)
                .reduce((a, b) -> a + "、" + b)
                .orElse("");
        if (orphans.size() > 12) {
            names += " 等";
        }
        if (!confirm.getAsBoolean()) {
            return;
        }
        beginChange.run();
        orphans.forEach(ctx.book()::removeResource);
        markDirty.run();
        refreshAll.run();
        setStatus.accept("已清理 " + orphans.size() + " 个未引用资源");
    }

    /** 主 controller 用于在外部拿到当前选中行的钩子，ResourceController 自己内部也用它。 */
    public ResourceRow selectedRow() {
        if (resourceTable == null) {
            return null;
        }
        return resourceTable.getSelectionModel().getSelectedItem();
    }

    /** 文件/导入类异常走 MainController 的 showError 通道——本地定义为函数式接口避免依赖 Alert。 */
    @FunctionalInterface
    public interface ErrorReporter {
        void report(String title, String message, Exception e);
    }
}

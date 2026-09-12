package org.chobit.epubra.app.controller.view;

import org.chobit.epubra.app.ui.model.ChapterNode;
import org.chobit.epubra.app.ui.model.ResourceRow;
import org.chobit.epubra.app.platform.AsyncTasks;
import org.chobit.epubra.app.context.BookContext;
import org.chobit.epubra.app.resource.CoverOps;
import org.chobit.epubra.app.resource.ResourceOps;
import org.chobit.epubra.lib.domain.MediaTypes;
import org.chobit.epubra.lib.domain.Book;
import org.chobit.epubra.lib.domain.Resource;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.TableView;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 资源面板控制器——导入 / 导出 / 删除 / 设为封面 / 插入图片正文 / 清理未引用资源。
 *
 * <p>作为 {@code resource-view.fxml} 的 {@code fx:controller} 由 FXML 实例化：
 * 面板内的表格与按钮经 {@code @FXML} 注入并直接绑定本类方法；回调在父控制器
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
    private AsyncTasks.ProgressController progress;
    private Supplier<ChapterNode> currentNodeProvider = () -> null;
    /**
     * 把一段 XHTML 片段插到当前激活的编辑器（编辑 tab → 可视化编辑器，否则源码区）。
     *
     * <p>返回值有意义：编辑器当前不可用时（没有选中章节、可视化编辑器尚未加载完成、
     * 源码区被禁用）会返回 {@code false}。调用方必须据此决定是否报「已插入」，
     * 不能无条件宣告成功。
     */
    private XhtmlInserter insertXhtml = xhtml -> false;

    /** 主窗口 stage（FileChooser 的 owner）。由 {@link #setStage} 在 FXML 加载后补发。 */
    private Stage stage;

    public void setStage(Stage stage) {
        this.stage = stage;
    }

    /** FXML 加载后由父控制器注入运行时依赖；必须在任何 onAction 触发前完成。 */
    public void bind(BookContext ctx,
                     Runnable beginChange, Runnable markDirty,
                     Runnable refreshAll, Runnable refreshResources,
                     Runnable refreshCoverCard,
                     Runnable updateStatus, Consumer<String> setStatus,
                     Consumer<String> warn, BooleanSupplier confirm,
                     ErrorReporter showError,
                     AsyncTasks.ProgressController progress,
                     XhtmlInserter insertXhtml) {
        this.ctx = ctx;
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
        this.progress = progress;
        this.insertXhtml = insertXhtml;
        wireCoverButtonRefresh();
    }

    public void setCurrentNodeProvider(Supplier<ChapterNode> currentNodeProvider) {
        this.currentNodeProvider = currentNodeProvider == null ? () -> null : currentNodeProvider;
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
        List<File> files = chooser.showOpenMultipleDialog(stage);
        if (files == null || files.isEmpty()) {
            return;
        }
        // 快照路径——后台线程不直接拿到 File 句柄，只拿路径，避免 GUI 句柄跨线程泄漏。
        final List<Path> paths = files.stream().map(File::toPath).toList();
        AsyncTasks.runIo(
                "正在导入 " + paths.size() + " 个资源",
                () -> readFilesInBackground(paths),
                progress != null ? progress : AsyncTasks.NOOP_PROGRESS,
                loaded -> attachLoadedFiles(loaded),
                err -> showError.report("导入失败", "后台读取出错", (Exception) err)
        );
    }

    /**
     * 后台线程跑的文件读取：每个 Path → 字节数组；失败时把异常塞进结果项，
     * 由 FX 线程的 {@link #attachLoadedFiles} 走错误通道。
     *
     * <p>故意把 IO 与「往书籍挂资源」分开——{@link Book#addResource}
     * 会改 {@code Resources} 集合，与 FX 线程的 refreshResources / status bar / autosave
     * 等读取存在 race；IO 后台、mutation FX 线程是安全做法。
     */
    private List<LoadedFile> readFilesInBackground(List<Path> paths) {
        List<LoadedFile> loaded = new ArrayList<>(paths.size());
        for (Path path : paths) {
            try {
                loaded.add(new LoadedFile(path.getFileName().toString(),
                        Files.readAllBytes(path), null));
            } catch (IOException ex) {
                loaded.add(new LoadedFile(path.getFileName().toString(), null, ex));
            }
        }
        return loaded;
    }

    /**
     * FX 线程回调：把后台读到的字节挂到书籍上，统一走 {@code beginChange → addResource
     * → markDirty → refreshResources → updateStatus} 这条流水线，与原同步版本语义一致。
     * 单文件失败已由 {@code LoadedFile.error} 单独报，批量失败不会阻断其它文件。
     */
    private void attachLoadedFiles(List<LoadedFile> loaded) {
        beginChange.run();
        int imported = 0;
        for (LoadedFile lf : loaded) {
            if (lf.error != null) {
                showError.report("导入失败", "无法读取 " + lf.fileName, lf.error);
                continue;
            }
            ctx.book().addResource(lf.fileName, lf.data);
            imported++;
        }
        markDirty.run();
        refreshResources.run();
        updateStatus.run();
        setStatus.accept("已导入 " + imported + " 个资源");
    }

    /** 后台读取产物的传输结构：bytes + 失败信息分两路，便于 FX 线程侧逐项处理。 */
    private record LoadedFile(String fileName, byte[] data, IOException error) {}

    public void exportSelected() {
        ResourceRow row = selectedRow();
        if (row == null) {
            warn.accept("请先在资源列表中选择一项");
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle("导出资源");
        chooser.setInitialFileName(row.getName());
        File file = chooser.showSaveDialog(stage);
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
        ChapterNode current = currentNodeProvider.get();
        if (current == null || current.resource() == null) {
            warn.accept("请先在左侧目录中选择要插入图片的章节");
            return;
        }
        String tag = ResourceOps.buildInsertImageTag(
                current.resource().href(), row.getResource().href(), row.getName());
        // 插入策略（编辑 tab 落可视化编辑器 / 源码 tab 落源码区 + 光标处理）由父控制器决定，
        // 本类只负责「选中了哪张图、该拼成什么标签」。
        if (insertXhtml.insert(tag)) {
            setStatus.accept("已在正文中插入：" + row.getName());
        } else {
            setStatus.accept("未能插入正文——请先打开该章节的编辑或源码视图");
        }
    }

    /** 编辑 tab 工具条「图片」按钮支持的类型，与资源导入的图片部分保持一致。 */
    private static final String[] IMAGE_EXTENSIONS =
            {"*.png", "*.jpg", "*.jpeg", "*.gif", "*.webp", "*.svg"};

    private static boolean isImageFileName(String fileName) {
        return MediaTypes.guessByExtension(fileName).startsWith("image/");
    }

    /**
     * 「从计算机选择图片插入正文」——编辑 tab 工具条「图片」按钮的入口。
     *
     * <p>与 {@link #insertSelectedImageIntoChapter()}（插入资源列表里选中的图）并存：
     * 后者服务资源面板与菜单栏，本方法服务编辑器工具条。
     *
     * <p><b>选中的图片会先导入为书内资源再插入</b>——EPUB 规定正文引用的图片必须位于包内，
     * 直接写本机绝对路径会留下悬空引用，结构校验必然报错。导入走
     * {@link Book#addResource(String, byte[])}，重名文件经 {@code Resources.uniqueHref}
     * 自动改名，不会覆盖已有资源。
     *
     * <p>IO 在后台线程，{@code addResource} 与插入回 FX 线程——与 {@link #importResources()}
     * 同一套「IO 后台 + mutation FX」模型，避免与 refreshResources / 自动暂存竞争。
     * 导入与插入共用一次 {@code beginChange}，因此一次撤销即可整体回退。
     */
    public void insertImagesFromDisk() {
        ChapterNode current = currentNodeProvider.get();
        if (current == null || current.resource() == null) {
            warn.accept("请先在左侧目录中选择要插入图片的章节");
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle("插入图片");
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("图片", IMAGE_EXTENSIONS));
        List<File> picked = chooser.showOpenMultipleDialog(stage);
        if (picked == null || picked.isEmpty()) {
            return;
        }
        insertImagesFromPaths(picked.stream().map(File::toPath).toList(), current.resource().href());
    }

    /**
     * {@link #insertImagesFromDisk()} 去掉「弹选择器」之后的部分：
     * 过滤非图片 → 后台读字节 → 挂成书内资源 → 拼标签插入正文。
     *
     * <p>单独抽出来是为了能在无头测试里驱动整条流水线——{@code FileChooser} 在测试环境里弹不出来。
     */
    void insertImagesFromPaths(List<Path> paths, String chapterHref) {
        if (paths == null || paths.isEmpty()) {
            return;
        }
        // 选择器只列图片，但仍做一次防御：个别平台允许直接输入文件名绕过过滤。
        List<Path> images = new ArrayList<>(paths.size());
        List<String> rejected = new ArrayList<>();
        for (Path path : paths) {
            String fileName = path.getFileName() == null ? "" : path.getFileName().toString();
            if (isImageFileName(fileName)) {
                images.add(path);
            } else {
                rejected.add(fileName);
            }
        }
        if (!rejected.isEmpty()) {
            warn.accept("已跳过非图片文件：" + String.join("、", rejected));
        }
        if (images.isEmpty()) {
            return;
        }
        AsyncTasks.runIo(
                "正在插入 " + images.size() + " 张图片",
                () -> readFilesInBackground(images),
                progress != null ? progress : AsyncTasks.NOOP_PROGRESS,
                loaded -> attachImagesAndInsert(chapterHref, loaded),
                err -> showError.report("插入图片失败", "后台读取出错", (Exception) err)
        );
    }

    /**
     * FX 线程回调：把后台读到的图片挂成书内资源，拼成 {@code <img>} 标签交给父控制器插入。
     *
     * <p>顺序有讲究：{@code beginChange} 会置「编辑步已捕获」，紧随其后的插入（走编辑器输入
     * 管线）因此不会再记一次快照——导入与插入合并成**一次**可撤销操作。
     *
     * <p>若一个文件都没读成功，直接返回：不开变更步、不打脏标记，避免留下一次空操作。
     *
     * <p>两条容易踩的坑，都在这里收口：
     * <ul>
     *   <li><b>已存在同样文件名 + 同样字节的资源就复用</b>（{@link ResourceOps#findEquivalent}），
     *       重复选同一张图不再堆积 {@code foo-1.png} 副本；</li>
     *   <li><b>插入结果要检查</b>（{@link XhtmlInserter#insert} 的返回值）——没有可插入位置时
     *       资源仍然进了书，但状态栏要说「已导入、未能插入正文」，不能谎报「已插入 N 张图片」。</li>
     * </ul>
     */
    private void attachImagesAndInsert(String chapterHref, List<LoadedFile> loaded) {
        List<LoadedFile> readable = new ArrayList<>(loaded.size());
        for (LoadedFile lf : loaded) {
            if (lf.error == null) {
                readable.add(lf);
            } else {
                showError.report("插入图片失败", "无法读取 " + lf.fileName, lf.error);
            }
        }
        if (readable.isEmpty()) {
            return;
        }
        beginChange.run();
        List<String> tags = new ArrayList<>(readable.size());
        for (LoadedFile lf : readable) {
            Resource image = ResourceOps.findEquivalent(ctx.book(), lf.fileName, lf.data);
            if (image == null) {
                image = ctx.book().addResource(lf.fileName, lf.data);
            }
            tags.add(ResourceOps.buildInsertImageTag(chapterHref, image.href(), lf.fileName));
        }
        markDirty.run();
        boolean inserted = insertXhtml.insert(ResourceOps.joinInsertFragments(tags));
        refreshResources.run();
        updateStatus.run();
        int count = readable.size();
        if (inserted) {
            setStatus.accept(count == 1
                    ? "已插入图片：" + readable.get(0).fileName
                    : "已插入 " + count + " 张图片");
        } else {
            setStatus.accept(count == 1
                    ? "图片已导入资源列表，但未能插入正文——请先打开该章节的编辑或源码视图"
                    : count + " 张图片已导入资源列表，但未能插入正文——请先打开该章节的编辑或源码视图");
        }
    }

    public void cleanupUnused() {
        // 设计上保持同步：B1 评估时把 6 个操作都过了一遍，cleanupUnused 的三个 FX 线程
        // 步骤（计算 orphans / 弹确认 / forEach removeResource）都不涉及文件 IO，
        // 对常规尺寸的书来说总耗时远低于人眼能感知的 100ms；走 AsyncTasks 包装反而会让
        // 用户看到「正在清理 N 个」一闪而过（进度条实际无后台工作），UX 噪音大于收益。
        // 若未来 books 容量增长到这一步真的卡顿，按 importResources 同款「IO 后台 +
        // mutation FX」拆分即可。
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

    /**
     * 把一段 XHTML 片段插到「当前激活的编辑器」的钩子。
     *
     * <p>返回 {@code false} 表示当前没有可插入的位置（未选中章节 / 可视化编辑器未加载完成 /
     * 源码区不可用）。调用方据此避免谎报插入成功。
     */
    @FunctionalInterface
    public interface XhtmlInserter {
        boolean insert(String xhtml);
    }
}

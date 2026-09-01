package com.epubra.app.controller;

import com.epubra.app.MainApp;
import com.epubra.epublib.domain.Book;
import com.epubra.epublib.domain.BookFactory;
import com.epubra.epublib.domain.MediaTypes;
import com.epubra.epublib.domain.Metadata;
import com.epubra.epublib.domain.Resource;
import com.epubra.epublib.domain.SpineReference;
import com.epubra.epublib.domain.TOCReference;
import com.epubra.epublib.io.EpubReader;
import com.epubra.epublib.io.EpubWriter;
import com.epubra.epublib.util.Hrefs;
import javafx.fxml.FXML;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.web.WebView;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 主窗口控制器：目录浏览、章节编辑、元数据维护与 EPUB 存取。
 */
public class MainController {

    @FXML
    private TreeView<ChapterNode> tocTree;
    @FXML
    private TextArea contentArea;
    @FXML
    private WebView previewView;
    @FXML
    private TabPane editorTabs;
    @FXML
    private TableView<ResourceRow> resourceTable;

    @FXML
    private TextField titleField;
    @FXML
    private TextField authorField;
    @FXML
    private TextField languageField;
    @FXML
    private TextField publisherField;
    @FXML
    private Label identifierLabel;
    @FXML
    private TextArea descriptionArea;

    @FXML
    private Label statusLabel;
    @FXML
    private Label statsLabel;

    private final EpubReader reader = new EpubReader();
    private final EpubWriter writer = new EpubWriter();

    private Stage stage;
    private Book book;
    private Path currentFile;
    private ChapterNode currentNode;
    private boolean dirty;
    private boolean loading;

    public void setStage(Stage stage) {
        this.stage = stage;
    }

    @FXML
    public void initialize() {
        tocTree.setShowRoot(false);
        tocTree.setCellFactory(tree -> new TreeCell<>() {
            @Override
            protected void updateItem(ChapterNode item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.displayTitle());
            }
        });
        tocTree.getSelectionModel().selectedItemProperty().addListener((obs, oldValue, selected) -> {
            if (!loading) {
                showChapter(selected == null ? null : selected.getValue());
            }
        });
        contentArea.textProperty().addListener((obs, oldValue, text) -> {
            if (!loading) {
                markDirty();
            }
        });

        newBook();
    }

    // ------------------------------------------------------------------ 文件

    @FXML
    public void onNew() {
        if (!confirmDiscardChanges()) {
            return;
        }
        newBook();
        setStatus("已新建空白书籍");
    }

    @FXML
    public void onOpen() {
        if (!confirmDiscardChanges()) {
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle("打开 EPUB 文件");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("EPUB 文件", "*.epub"));
        File file = chooser.showOpenDialog(stage);
        if (file == null) {
            return;
        }
        try {
            book = reader.read(file.toPath());
            currentFile = file.toPath();
            refreshAll();
            setStatus("已打开 " + file.getName());
        } catch (IOException e) {
            showError("打开失败", "无法读取 " + file.getName(), e);
        }
    }

    @FXML
    public void onSave() {
        if (currentFile == null) {
            onSaveAs();
            return;
        }
        saveTo(currentFile);
    }

    @FXML
    public void onSaveAs() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("保存 EPUB 文件");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("EPUB 文件", "*.epub"));
        chooser.setInitialFileName(defaultFileName());
        File file = chooser.showSaveDialog(stage);
        if (file == null) {
            return;
        }
        saveTo(file.toPath());
    }

    @FXML
    public void onExit() {
        if (!confirmDiscardChanges()) {
            return;
        }
        stage.close();
    }

    @FXML
    public void onAbout() {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("关于 " + MainApp.APP_NAME);
        alert.setHeaderText(MainApp.APP_NAME + " - EPUB 编辑器");
        alert.setContentText("JavaFX 前端 + 项目内自维护的 epublib 内核\n支持 EPUB 2/3 的读取、编辑与写出。");
        alert.initOwner(stage);
        alert.showAndWait();
    }

    // ------------------------------------------------------------------ 章节

    @FXML
    public void onAddChapter() {
        flushCurrentChapter();
        String title = "第 " + (book.spine().size() + 1) + " 章";
        Resource chapter = book.addChapter(title, null);
        markDirty();
        refreshAll();
        selectResource(chapter);
        setStatus("已添加章节：" + title);
    }

    @FXML
    public void onDeleteChapter() {
        ChapterNode node = currentNode;
        if (node == null || node.resource() == null) {
            return;
        }
        Resource target = node.resource();
        book.resources().removeByHref(target.href());
        book.spine().removeResourceId(target.id());
        removeTocReference(book.toc().roots(), target);

        currentNode = null;
        markDirty();
        refreshAll();
        setStatus("已删除章节：" + node.displayTitle());
    }

    @FXML
    public void onMoveUp() {
        moveChapter(-1);
    }

    @FXML
    public void onMoveDown() {
        moveChapter(1);
    }

    @FXML
    public void onRefreshPreview() {
        flushCurrentChapter();
        refreshPreview();
        setStatus("预览已刷新");
    }

    @FXML
    public void onApplyMetadata() {
        flushMetadata();
        markDirty();
        refreshAll();
        setStatus("元数据已更新");
    }

    // ------------------------------------------------------------------ 资源

    @FXML
    public void onImportResources() {
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
        int imported = 0;
        for (File file : files) {
            try {
                book.addResource(file.toPath());
                imported++;
            } catch (IOException e) {
                showError("导入失败", "无法读取 " + file.getName(), e);
            }
        }
        markDirty();
        refreshResources();
        updateStatus();
        setStatus("已导入 " + imported + " 个资源");
    }

    @FXML
    public void onExportResource() {
        ResourceRow row = selectedResourceRow();
        if (row == null) {
            warn("请先在资源列表中选择一项");
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
            setStatus("已导出到 " + file.getName());
        } catch (IOException e) {
            showError("导出失败", "无法写入 " + file.getAbsolutePath(), e);
        }
    }

    @FXML
    public void onDeleteResource() {
        ResourceRow row = selectedResourceRow();
        if (row == null) {
            warn("请先在资源列表中选择一项");
            return;
        }
        Resource resource = row.getResource();
        String message = "确定删除资源「" + row.getName() + "」？";
        if (isReferencedByChapters(resource)) {
            message += "\n\n注意：正文中存在对它的引用，删除后相关图片或样式将无法显示。";
        }
        if (!confirm("删除资源", message)) {
            return;
        }
        book.removeResource(resource);
        markDirty();
        refreshAll();
        setStatus("已删除资源：" + row.getName());
    }

    @FXML
    public void onSetCover() {
        ResourceRow row = selectedResourceRow();
        if (row == null) {
            warn("请先在资源列表中选择一张图片");
            return;
        }
        if (!row.isImage()) {
            warn("封面必须是图片资源（PNG / JPEG / GIF / WebP / SVG）");
            return;
        }
        book.setCover(row.getResource());
        markDirty();
        refreshResources();
        setStatus("已设为封面：" + row.getName());
    }

    @FXML
    public void onInsertImage() {
        ResourceRow row = selectedResourceRow();
        if (row == null) {
            warn("请先在资源列表中选择一张图片");
            return;
        }
        if (!row.isImage()) {
            warn("只能向正文插入图片资源");
            return;
        }
        if (currentNode == null || currentNode.resource() == null) {
            warn("请先在左侧目录中选择要插入图片的章节");
            return;
        }
        Resource image = row.getResource();
        String chapterDir = Hrefs.parentDirectory(currentNode.resource().href());
        String relative = Hrefs.relativize(chapterDir, image.href());
        String tag = String.format("<img src=\"%s\" alt=\"%s\"/>", relative, row.getName());
        contentArea.insertText(contentArea.getAnchor(), tag);
        editorTabs.getSelectionModel().selectFirst();
        markDirty();
        setStatus("已在正文中插入：" + row.getName());
    }

    @FXML
    public void onCleanupResources() {
        List<Resource> orphans = book.unreferencedResources();
        if (orphans.isEmpty()) {
            setStatus("没有未被引用的资源");
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
        if (!confirm("清理未引用资源", "以下 " + orphans.size() + " 个资源未被引用：\n\n" + names + "\n\n确定删除？")) {
            return;
        }
        orphans.forEach(book::removeResource);
        markDirty();
        refreshAll();
        setStatus("已清理 " + orphans.size() + " 个未引用资源");
    }

    /** 刷新资源列表；nav 与 ncx 由写出流程自动维护，不展示给用户。 */
    private void refreshResources() {
        Resource nav = book.navResource();
        List<ResourceRow> rows = new ArrayList<>();
        for (Resource resource : book.resources().all()) {
            if (resource == nav || resource.isNavDocument() || MediaTypes.NCX.equals(resource.mediaType())) {
                continue;
            }
            rows.add(new ResourceRow(resource));
        }
        resourceTable.getItems().setAll(rows);
    }

    private ResourceRow selectedResourceRow() {
        return resourceTable.getSelectionModel().getSelectedItem();
    }

    private boolean isReferencedByChapters(Resource resource) {
        String fileName = resource.fileName();
        if (fileName.isEmpty()) {
            return false;
        }
        for (Resource chapter : book.spineResources()) {
            if (chapter != resource && chapter.asString().contains(fileName)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ 内部逻辑

    private void newBook() {
        book = BookFactory.createEmpty("新书籍");
        currentFile = null;
        currentNode = null;
        refreshAll();
    }

    private void saveTo(Path target) {
        flushCurrentChapter();
        flushMetadata();
        try {
            writer.write(book, target);
            currentFile = target;
            book.setSource(target);
            dirty = false;
            updateStatus();
            setStatus("已保存到 " + target.getFileName());
        } catch (IOException e) {
            showError("保存失败", "无法写入 " + target, e);
        }
    }

    private void refreshAll() {
        loading = true;
        try {
            titleField.setText(book.metadata().firstTitle());
            authorField.setText(book.metadata().creatorsInline());
            languageField.setText(nullSafe(book.metadata().language()));
            publisherField.setText(nullSafe(book.metadata().publisher()));
            descriptionArea.setText(nullSafe(book.metadata().description()));
            Metadata.Identifier identifier = book.metadata().primaryIdentifier();
            identifierLabel.setText(identifier == null ? "—（保存时自动生成）" : identifier.raw());
        } finally {
            loading = false;
        }
        refreshToc();
        refreshResources();
        updateStatus();
    }

    private void refreshToc() {
        TreeItem<ChapterNode> toSelect;
        loading = true;
        try {
            TreeItem<ChapterNode> root = new TreeItem<>(new ChapterNode("目录", null, null));
            for (TOCReference reference : book.toc().roots()) {
                root.getChildren().add(buildTreeItem(reference));
            }
            root.setExpanded(true);
            ChapterNode previous = currentNode;
            tocTree.setRoot(root);

            toSelect = previous == null || previous.resource() == null
                    ? null : findByResource(root, previous.resource());
            if (toSelect == null && !root.getChildren().isEmpty()) {
                toSelect = root.getChildren().get(0);
            }
        } finally {
            loading = false;
        }
        // 选中必须在 loading 结束之后进行，否则会被选择监听器上的 loading 守卫忽略
        if (toSelect != null) {
            tocTree.getSelectionModel().select(toSelect);
            if (currentNode == null) {
                showChapter(toSelect.getValue());
            }
        } else {
            showChapter(null);
        }
    }

    private TreeItem<ChapterNode> buildTreeItem(TOCReference reference) {
        Resource resource = resolveResource(reference);
        TreeItem<ChapterNode> item = new TreeItem<>(new ChapterNode(reference.title(), resource, reference));
        for (TOCReference child : reference.children()) {
            item.getChildren().add(buildTreeItem(child));
        }
        item.setExpanded(true);
        return item;
    }

    private Resource resolveResource(TOCReference reference) {
        return book.resources().getByHref(Hrefs.resolve(book.contentDirectory(), reference.resourceHref()));
    }

    private TreeItem<ChapterNode> findByResource(TreeItem<ChapterNode> parent, Resource resource) {
        for (TreeItem<ChapterNode> child : parent.getChildren()) {
            if (resource.equals(child.getValue().resource())) {
                return child;
            }
            TreeItem<ChapterNode> nested = findByResource(child, resource);
            if (nested != null) {
                return nested;
            }
        }
        return null;
    }

    private void selectResource(Resource resource) {
        TreeItem<ChapterNode> root = tocTree.getRoot();
        if (root == null) {
            return;
        }
        TreeItem<ChapterNode> item = findByResource(root, resource);
        if (item != null) {
            tocTree.getSelectionModel().select(item);
        }
    }

    private void showChapter(ChapterNode node) {
        flushCurrentChapter();
        currentNode = node;
        loading = true;
        try {
            if (node == null || node.resource() == null) {
                contentArea.clear();
                contentArea.setDisable(true);
            } else {
                contentArea.setDisable(false);
                contentArea.setText(node.resource().asString());
                contentArea.positionCaret(0);
            }
        } finally {
            loading = false;
        }
        refreshPreview();
        updateStatus();
    }

    private void refreshPreview() {
        if (currentNode == null || currentNode.resource() == null) {
            previewView.getEngine().loadContent("<html><body></body></html>");
            return;
        }
        previewView.getEngine().loadContent(currentNode.resource().asString(), "application/xhtml+xml");
    }

    /** 把编辑器中的内容写回当前章节资源。 */
    private void flushCurrentChapter() {
        if (currentNode == null || currentNode.resource() == null) {
            return;
        }
        currentNode.resource().setString(contentArea.getText());
    }

    private void flushMetadata() {
        Metadata metadata = book.metadata();
        metadata.setFirstTitle(titleField.getText());
        metadata.setCreatorsInline(authorField.getText());
        metadata.setLanguage(languageField.getText());
        metadata.setPublisher(publisherField.getText());
        metadata.setDescription(descriptionArea.getText());
    }

    private void removeTocReference(List<TOCReference> nodes, Resource target) {
        List<TOCReference> matched = new ArrayList<>();
        for (TOCReference node : nodes) {
            Resource resolved = resolveResource(node);
            if (target.equals(resolved)) {
                matched.add(node);
            } else {
                removeTocReference(node.children(), target);
            }
        }
        nodes.removeAll(matched);
    }

    private void moveChapter(int delta) {
        ChapterNode node = currentNode;
        if (node == null || node.resource() == null) {
            return;
        }
        List<SpineReference> references = new ArrayList<>(book.spine().references());
        int index = -1;
        for (int i = 0; i < references.size(); i++) {
            if (references.get(i).resourceId().equals(node.resource().id())) {
                index = i;
                break;
            }
        }
        int target = index + delta;
        if (index < 0 || target < 0 || target >= references.size()) {
            return;
        }
        // 记录交换前的邻居，用于同步目录顺序
        String neighborId = references.get(target).resourceId();
        Collections.swap(references, index, target);
        book.spine().clear();
        references.forEach(book.spine()::add);

        // 目录与阅读顺序保持一致：仅调整顶层节点
        List<TOCReference> roots = book.toc().roots();
        int from = -1;
        int to = -1;
        for (int i = 0; i < roots.size(); i++) {
            Resource resolved = resolveResource(roots.get(i));
            if (resolved == null) {
                continue;
            }
            if (node.resource().equals(resolved)) {
                from = i;
            } else if (neighborId.equals(resolved.id())) {
                to = i;
            }
        }
        if (from >= 0 && to >= 0) {
            Collections.swap(roots, from, to);
        }

        markDirty();
        refreshAll();
        selectResource(node.resource());
        setStatus(delta < 0 ? "章节已上移" : "章节已下移");
    }

    // ------------------------------------------------------------------ 状态

    private boolean confirmDiscardChanges() {
        if (!dirty) {
            return true;
        }
        return confirm("未保存的修改", "当前书籍有未保存的修改。\n继续操作将丢弃这些修改，是否继续？");
    }

    private boolean confirm(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.initOwner(stage);
        return alert.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK;
    }

    private void warn(String message) {
        Alert alert = new Alert(Alert.AlertType.WARNING);
        alert.setTitle("提示");
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.initOwner(stage);
        alert.showAndWait();
    }

    private void markDirty() {
        dirty = true;
        updateStatus();
    }

    private void setStatus(String message) {
        statusLabel.setText(message);
    }

    private void updateStatus() {
        statsLabel.setText("章节 " + book.spineResources().size()
                + " · 资源 " + book.resources().size()
                + " · " + (currentFile == null ? "未保存" : currentFile.getFileName().toString()));
        updateTitle();
    }

    private void updateTitle() {
        if (stage == null) {
            return;
        }
        String name = currentFile == null ? "新书籍" : currentFile.getFileName().toString();
        stage.setTitle(MainApp.APP_NAME + " - " + name + (dirty ? " *" : ""));
    }

    private String defaultFileName() {
        String title = book.metadata().firstTitle().isBlank() ? "新书籍" : book.metadata().firstTitle();
        return title.replaceAll("[\\\\/:*?\"<>|]", "_") + ".epub";
    }

    private void showError(String title, String message, Exception e) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle(title);
        alert.setHeaderText(message);
        alert.setContentText(e.getMessage());
        alert.initOwner(stage);
        alert.showAndWait();
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }
}

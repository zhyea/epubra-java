package org.chobit.epubra.app.controller.view;

import org.chobit.epubra.app.context.AppEventBus;
import org.chobit.epubra.app.context.BookContext;
import org.chobit.epubra.app.context.Unsubscriber;
import org.chobit.epubra.app.document.DraftDocument;
import org.chobit.epubra.app.platform.AsyncTasks;
import org.chobit.epubra.app.workspace.WorkspaceScanner;
import org.chobit.epubra.app.workspace.WorkspaceStore;
import org.chobit.epubra.lib.domain.Book;
import org.chobit.epubra.lib.domain.Resource;
import org.chobit.epubra.lib.io.EpubReader;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/** 启动首页：展示当前工作空间中的图书书架。 */
public class WelcomePageController {

    private static final double COVER_WIDTH = 132;
    private static final double COVER_HEIGHT = 190;

    @FXML
    private StackPane welcomeRoot;
    @FXML
    private Label workspaceTitle;
    @FXML
    private Label workspaceHint;
    @FXML
    private FlowPane bookShelf;

    private Runnable onNewBook;
    private Consumer<Path> onOpenBook;
    private Runnable onExit;
    private Path currentWorkspace;
    private long rebuildGeneration;
    private Unsubscriber bookLoadedUnsubscriber;

    public void bind(Runnable onNewBook, Consumer<Path> onOpenBook, Runnable onExit) {
        this.onNewBook = onNewBook;
        this.onOpenBook = onOpenBook;
        this.onExit = onExit;
        showWorkspace(resolveInitialWorkspace());
    }

    public void subscribeVisibility(BookContext ctx) {
        if (bookLoadedUnsubscriber != null) {
            bookLoadedUnsubscriber.close();
        }
        bookLoadedUnsubscriber = ctx.bus().subscribe(AppEventBus.BookLoadedEvent.class, e -> hide());
    }

    @FXML
    private void onExitAction() {
        if (onExit != null) {
            onExit.run();
        }
    }

    public void showWorkspace(Path workspace) {
        currentWorkspace = workspace;
        rebuildBookshelf();
    }

    public Path currentWorkspace() {
        return currentWorkspace;
    }

    private Path resolveInitialWorkspace() {
        Optional<Path> last = WorkspaceStore.last();
        if (last.isPresent()) {
            return last.get();
        }
        return WorkspaceStore.recentExisting().stream().findFirst().orElse(null);
    }

    private void rebuildBookshelf() {
        if (bookShelf == null) {
            return;
        }
        long generation = ++rebuildGeneration;
        bookShelf.getChildren().clear();
        bookShelf.getChildren().add(newBookCard());

        if (workspaceTitle != null) {
            workspaceTitle.setText(currentWorkspace == null
                    ? "未选择工作空间"
                    : displayName(currentWorkspace));
        }
        if (workspaceHint != null) {
            workspaceHint.setText(currentWorkspace == null
                    ? "请从“文件 → 打开最近工作空间”切换，或点击“+”新建图书"
                    : "双击图书打开编辑器");
        }
        if (currentWorkspace == null) {
            return;
        }

        List<DraftDocument> documents = WorkspaceScanner.scan(currentWorkspace);
        if (documents.isEmpty()) {
            return;
        }
        AsyncTasks.runIo(
                "正在加载书架",
                () -> loadShelfBooks(documents),
                AsyncTasks.NOOP_PROGRESS,
                books -> {
                    if (generation != rebuildGeneration || bookShelf == null) {
                        return;
                    }
                    for (ShelfBook book : books) {
                        bookShelf.getChildren().add(bookCard(book));
                    }
                },
                ignored -> {
                    // 书架是辅助展示，单本文档损坏时仍保留新建入口。
                });
    }

    private List<ShelfBook> loadShelfBooks(List<DraftDocument> documents) {
        EpubReader reader = new EpubReader();
        List<ShelfBook> books = new ArrayList<>(documents.size());
        for (DraftDocument document : documents) {
            String title = document.displayTitle();
            byte[] cover = null;
            try {
                Book loaded = reader.read(document.path());
                if (loaded.metadata().firstTitle() != null
                        && !loaded.metadata().firstTitle().isBlank()) {
                    title = loaded.metadata().firstTitle();
                }
                Resource coverResource = loaded.coverResource().orElse(null);
                if (coverResource != null && coverResource.data().length > 0) {
                    cover = coverResource.data();
                }
            } catch (Exception ignored) {
                // 文件可能是尚未完成写入的草稿，仍以文件名展示卡片。
            }
            books.add(new ShelfBook(document.path(), title,
                    document.relativeTimeText(java.time.Instant.now()), cover));
        }
        return books;
    }

    private Node newBookCard() {
        StackPane cover = coverContainer();
        cover.getChildren().add(new Label("+"));
        cover.getStyleClass().addAll("book-cover-placeholder", "new-book-plus");

        Label title = new Label("新建图书");
        title.getStyleClass().add("book-title");
        VBox card = new VBox(7, cover, title);
        card.getStyleClass().addAll("book-card", "new-book-card");
        card.setOnMouseClicked(event -> {
            if (onNewBook != null) {
                onNewBook.run();
            }
            event.consume();
        });
        Tooltip.install(card, new Tooltip("新建图书"));
        return card;
    }

    private Node bookCard(ShelfBook book) {
        StackPane cover = coverContainer();
        if (book.coverBytes() != null) {
            Image image = new Image(new ByteArrayInputStream(book.coverBytes()),
                    COVER_WIDTH, COVER_HEIGHT, true, true);
            if (!image.isError()) {
                ImageView imageView = new ImageView(image);
                imageView.setFitWidth(COVER_WIDTH);
                imageView.setFitHeight(COVER_HEIGHT);
                imageView.setPreserveRatio(true);
                cover.getChildren().add(imageView);
            }
        }
        if (cover.getChildren().isEmpty()) {
            cover.getChildren().add(defaultCoverContent(book.title()));
        }

        Label title = new Label(book.title());
        title.setWrapText(true);
        title.setMaxWidth(COVER_WIDTH + 12);
        title.getStyleClass().add("book-title");
        Label meta = new Label(book.relativeTime());
        meta.getStyleClass().add("book-meta");

        VBox card = new VBox(7, cover, title, meta);
        card.getStyleClass().add("book-card");
        card.setOnMouseClicked(event -> openBookOnDoubleClick(event, book.path()));
        Tooltip.install(card, new Tooltip(book.path().toString()));
        return card;
    }

    private static StackPane coverContainer() {
        StackPane cover = new StackPane();
        cover.setMinSize(COVER_WIDTH, COVER_HEIGHT);
        cover.setPrefSize(COVER_WIDTH, COVER_HEIGHT);
        cover.setMaxSize(COVER_WIDTH, COVER_HEIGHT);
        cover.getStyleClass().add("book-cover");
        return cover;
    }

    private static Node defaultCoverContent(String title) {
        VBox content = new VBox(10);
        content.setAlignment(Pos.CENTER);
        content.setMaxWidth(COVER_WIDTH - 24);
        content.getStyleClass().add("default-book-cover-content");

        Label mark = new Label("EPUB");
        mark.getStyleClass().add("default-book-cover-mark");

        Label titleLabel = new Label(title == null || title.isBlank() ? "未命名图书" : title);
        titleLabel.setWrapText(true);
        titleLabel.setMaxWidth(COVER_WIDTH - 28);
        titleLabel.setAlignment(Pos.CENTER);
        titleLabel.getStyleClass().add("default-book-cover-title");

        Label caption = new Label("暂无封面");
        caption.getStyleClass().add("default-book-cover-caption");

        content.getChildren().addAll(mark, titleLabel, caption);
        return content;
    }

    private void openBookOnDoubleClick(MouseEvent event, Path path) {
        if (event.getClickCount() == 2 && onOpenBook != null) {
            onOpenBook.accept(path);
            event.consume();
        }
    }

    public void hide() {
        if (welcomeRoot == null) {
            return;
        }
        welcomeRoot.setVisible(false);
        welcomeRoot.setManaged(false);
    }

    public void show() {
        if (welcomeRoot == null) {
            return;
        }
        rebuildBookshelf();
        welcomeRoot.setVisible(true);
        welcomeRoot.setManaged(true);
    }

    public void dispose() {
        rebuildGeneration++;
        if (bookLoadedUnsubscriber != null) {
            bookLoadedUnsubscriber.close();
            bookLoadedUnsubscriber = null;
        }
    }

    public boolean isVisible() {
        return welcomeRoot != null && welcomeRoot.isVisible();
    }

    private static String displayName(Path path) {
        if (path == null || path.getFileName() == null) {
            return path == null ? "" : path.toString();
        }
        return path.getFileName().toString();
    }

    private record ShelfBook(Path path, String title, String relativeTime, byte[] coverBytes) {
    }
}

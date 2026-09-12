package org.chobit.epubra.app.controller.view;

import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.StackPane;
import org.chobit.epubra.app.workspace.WorkspaceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 首页书架的「未选择工作空间」空态契约：
 * 没有落盘位置时只给「选择工作空间」，<b>不</b>提供「新建图书」——否则用户建了书也不知道存哪儿。
 */
class WorkspaceShelfEmptyStateTest {

    private static final CountDownLatch FX_STARTED = new CountDownLatch(1);

    @BeforeAll
    static void startToolkit() throws Exception {
        try {
            Platform.startup(FX_STARTED::countDown);
        } catch (IllegalStateException alreadyInitialized) {
            FX_STARTED.countDown();
        }
        assertTrue(FX_STARTED.await(10, TimeUnit.SECONDS), "JavaFX toolkit 启动超时");
        Platform.setImplicitExit(false);
    }

    @TempDir
    Path workspace;

    @BeforeEach
    @AfterEach
    void resetStore() {
        WorkspaceStore.resetForTesting();
    }

    /** 在 FX 线程加载 welcome-page.fxml 并 bind，返回书架容器（反射取私有字段）。 */
    private FlowPane loadShelf() throws Exception {
        AtomicReference<FlowPane> shelf = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                FXMLLoader loader = new FXMLLoader(
                        WorkspaceShelfEmptyStateTest.class.getResource("/org/chobit/epubra/app/view/welcome-page.fxml"));
                loader.load();
                WelcomePageController controller = loader.getController();
                controller.bind(() -> { }, p -> { }, () -> { }, () -> { });
                java.lang.reflect.Field f = WelcomePageController.class.getDeclaredField("bookShelf");
                f.setAccessible(true);
                shelf.set((FlowPane) f.get(controller));
            } catch (Throwable t) {
                failure.set(t);
            } finally {
                done.countDown();
            }
        });
        assertTrue(done.await(20, TimeUnit.SECONDS), "FXML 加载超时");
        if (failure.get() != null) {
            throw new AssertionError(failure.get());
        }
        return shelf.get();
    }

    /** 递归收集卡片子树里的所有 Label 文本——卡片是代码构建的 VBox，没有 id 可查。 */
    private static String cardText(FlowPane shelf) {
        StringBuilder text = new StringBuilder();
        for (Node card : shelf.getChildren()) {
            collectText(card, text);
        }
        return text.toString();
    }

    private static void collectText(Node node, StringBuilder out) {
        if (node instanceof javafx.scene.control.Label label && label.getText() != null) {
            out.append(label.getText()).append('|');
        }
        if (node instanceof javafx.scene.Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                collectText(child, out);
            }
        }
    }

    @Test
    void withoutWorkspaceShowsChooseWorkspaceInsteadOfNewBook() throws Exception {
        FlowPane shelf = loadShelf();

        assertEquals(1, shelf.getChildren().size(), "空态只应有一张引导卡");
        assertTrue(cardText(shelf).contains("选择工作空间"),
                "未选择工作空间时必须引导选目录，实际卡片：" + cardText(shelf));
        assertTrue(cardText(shelf).replace("选择工作空间", "").isEmpty()
                        || !cardText(shelf).contains("新建图书"),
                "没有落盘位置时不应出现「新建图书」");
    }

    @Test
    void withWorkspaceShowsNewBookCard() throws Exception {
        WorkspaceStore.setLast(workspace);

        FlowPane shelf = loadShelf();

        assertTrue(cardText(shelf).contains("新建图书"),
                "已选定工作空间后应恢复「新建图书」入口，实际卡片：" + cardText(shelf));
    }
}

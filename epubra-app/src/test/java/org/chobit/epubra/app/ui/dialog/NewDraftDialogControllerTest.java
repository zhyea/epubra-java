package org.chobit.epubra.app.ui.dialog;

import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import org.chobit.epubra.app.workspace.WorkspaceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「新建图书草稿」对话框在<b>非法工作空间路径</b>下的行为守卫。
 *
 * <p>工作空间输入框是可编辑的 {@code TextField}，每次击键都会走一遍校验。旧实现直接调
 * {@code Path.of(text)}，Windows 下用户输入 {@code C:\a|b} 这类字符会抛
 * {@code InvalidPathException}——它从 {@code setResultConverter} 里的 {@code collectResult()}
 * 冒到 {@code showAndWait()} 调用方，对话框直接崩掉、用户还看不到任何原因。
 *
 * <p>现在两个入口（{@code revalidate} 校验、{@code collectResult} 取值）都走
 * {@link NewDraftDialogController} 内部的 {@code pathFromField}，非法输入按「给原因 / 当取消」处理。
 *
 * <h2>⚠ 跨 class 共享 JavaFX Platform</h2>
 * <p>{@code Platform.startup} 全 JVM 只允许一次，后跑的 class 会抛
 * {@code IllegalStateException}——吞掉异常并 {@code countDown} 放行。本类不写
 * {@code @AfterAll} 调 {@code Platform.exit()}（{@code setImplicitExit(false)} 已保证
 * toolkit 不会被窗口关掉带走）。
 */
class NewDraftDialogControllerTest {

    private static final String FXML_PATH = "/org/chobit/epubra/app/view/new-draft-dialog.fxml";
    private static final CountDownLatch FX_STARTED = new CountDownLatch(1);

    @BeforeAll
    static void bootFx() throws Exception {
        try {
            Platform.startup(FX_STARTED::countDown);
        } catch (IllegalStateException alreadyInitialized) {
            FX_STARTED.countDown();
        }
        assertTrue(FX_STARTED.await(10, TimeUnit.SECONDS), "JavaFX toolkit 启动超时");
        Platform.setImplicitExit(false);
    }

    /** 在 FX 线程上跑一段动作并等它跑完，异常回抛到测试线程。 */
    private static void runOnFx(Runnable action) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                action.run();
            } catch (Throwable t) {
                failure.set(t);
            } finally {
                done.countDown();
            }
        });
        assertTrue(done.await(10, TimeUnit.SECONDS), "FX 任务超时");
        if (failure.get() != null) {
            throw new AssertionError("FX 任务抛出异常", failure.get());
        }
    }

    @BeforeEach
    void resetStores() {
        WorkspaceStore.resetForTesting();
    }

    @AfterEach
    void cleanStores() {
        WorkspaceStore.resetForTesting();
    }

    @Test
    void invalidWorkspacePathIsReportedInsteadOfThrowing() throws Exception {
        AtomicReference<String> errorText = new AtomicReference<>();
        AtomicReference<Boolean> okDisabled = new AtomicReference<>();
        AtomicReference<Boolean> resultEmpty = new AtomicReference<>();

        runOnFx(() -> {
            FXMLLoader loader = new FXMLLoader(NewDraftDialogController.class.getResource(FXML_PATH));
            Parent root;
            try {
                root = loader.load();
            } catch (java.io.IOException e) {
                throw new IllegalStateException("加载 " + FXML_PATH + " 失败", e);
            }
            NewDraftDialogController controller = loader.getController();
            Button ok = new Button();
            controller.configure(ok, null);

            ((TextField) root.lookup("#nameField")).setText("三体");
            // Windows 的 WindowsPathParser 对 | < > : " ? * 以及控制字符一律抛
            // InvalidPathException —— 这正是用户手打工作空间路径时能真实撞上的输入
            ((TextField) root.lookup("#workspaceField")).setText("C:\\坏|路径");

            errorText.set(((Label) root.lookup("#errorLabel")).getText());
            okDisabled.set(ok.isDisable());
            resultEmpty.set(controller.collectResult().isEmpty());
        });

        assertEquals("工作空间路径包含非法字符", errorText.get(), "非法路径必须给出明确原因");
        assertTrue(okDisabled.get(), "路径非法时 OK 必须保持禁用");
        assertTrue(resultEmpty.get(),
                "路径非法时按「取消」处理，不能把 InvalidPathException 抛给 showAndWait 调用方");
    }

    @Test
    void blankWorkspaceStillReportsMissingSelection() throws Exception {
        AtomicReference<String> errorText = new AtomicReference<>();

        runOnFx(() -> {
            FXMLLoader loader = new FXMLLoader(NewDraftDialogController.class.getResource(FXML_PATH));
            Parent root;
            try {
                root = loader.load();
            } catch (java.io.IOException e) {
                throw new IllegalStateException("加载 " + FXML_PATH + " 失败", e);
            }
            NewDraftDialogController controller = loader.getController();
            controller.configure(new Button(), null);

            ((TextField) root.lookup("#workspaceField")).setText("   ");
            errorText.set(((Label) root.lookup("#errorLabel")).getText());
        });

        assertEquals("请选择工作空间目录", errorText.get(), "空输入应保持原有的「未选择」提示");
    }
}

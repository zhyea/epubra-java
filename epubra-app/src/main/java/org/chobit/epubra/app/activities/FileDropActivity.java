package org.chobit.epubra.app.activities;

import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableValue;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.input.Dragboard;
import javafx.scene.input.TransferMode;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

/**
 * 「把图书文件拖到窗口即打开」的装配器。
 *
 * <p>从 {@code MainController} 搬出（纯搬迁，行为不变）。挂在 <b>Scene</b> 上而不是欢迎页
 * 节点上——欢迎页在载入书籍后就隐藏了，挂在那里之后再也收不到拖放事件；而拖放打开应该是
 * 全流程可用的能力，不限于起始页。
 *
 * <p>{@code initialize()} 阶段 Scene 尚未挂上（FXML 刚加载完），因此
 * {@link #wireWhenSceneReady()} 先探测、未就绪则挂一次性 scene 监听等到就绪再装。
 */
public final class FileDropActivity {

    private final Node anchor;
    private final Consumer<Path> openBook;

    /**
     * @param anchor   任意已注入的节点，用于探测 / 等待 Scene（通常传状态栏标签）
     * @param openBook 打开图书的回调（通常 {@code workspaceActivity::openBook}）
     */
    public FileDropActivity(Node anchor, Consumer<Path> openBook) {
        this.anchor = anchor;
        this.openBook = openBook;
    }

    /** 装配拖放：Scene 已就绪直接装，否则等 scene 属性变成非 null。 */
    public void wireWhenSceneReady() {
        Scene scene = anchor.getScene();
        if (scene != null) {
            wireTo(scene);
            return;
        }
        anchor.sceneProperty().addListener(new ChangeListener<>() {
            @Override
            public void changed(ObservableValue<? extends Scene> obs, Scene oldScene, Scene newScene) {
                if (newScene == null) {
                    return;
                }
                anchor.sceneProperty().removeListener(this);
                wireTo(newScene);
            }
        });
    }

    private void wireTo(Scene scene) {
        scene.setOnDragOver(event -> {
            if (firstBookFile(event.getDragboard()) != null) {
                event.acceptTransferModes(TransferMode.COPY);
            }
            event.consume();
        });
        scene.setOnDragDropped(event -> {
            Path file = firstBookFile(event.getDragboard());
            if (file == null) {
                event.setDropCompleted(false);
                event.consume();
                return;
            }
            event.setDropCompleted(true);
            event.consume();
            openBook.accept(file);
        });
    }

    /** 从拖放载体里挑第一个图书文件（{@code .draft} / {@code .epub}）；没有则返回 null。 */
    public static Path firstBookFile(Dragboard board) {
        if (board == null || !board.hasFiles()) {
            return null;
        }
        List<File> files = board.getFiles();
        if (files == null) {
            return null;
        }
        for (File file : files) {
            String lower = file.getName().toLowerCase();
            if (file.isFile() && (lower.endsWith(".draft") || lower.endsWith(".epub"))) {
                return file.toPath();
            }
        }
        return null;
    }
}

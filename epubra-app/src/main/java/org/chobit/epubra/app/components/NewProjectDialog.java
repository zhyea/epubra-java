package org.chobit.epubra.app.components;

import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogPane;
import javafx.stage.Stage;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Path;
import java.util.Optional;

/**
 * 「新建 EPUB 项目」对话框（模态阻塞）。
 *
 * <p>展示为 JavaFX 内置 {@link Dialog}：按钮 OK / Cancel 由 DialogPane 自带，
 * 字段视图从 {@code new-project-dialog.fxml} 加载；FXML 根是 VBox 而非 DialogPane，
 * 是为了避开 DialogPane 必须作为 FXMLLoader 根节点时的若干约束（fx:controller 与
 * DialogPane 自带按钮布局冲突等）。
 *
 * <p>每次 show 都是新实例，无状态保留——符合 {@link Dialog} 一次一用的惯例。
 */
public final class NewProjectDialog {

    private static final String FXML_PATH = "/org/chobit/epubra/app/view/new-project-dialog.fxml";

    private NewProjectDialog() {
    }

    /**
     * 展示对话框。阻塞到用户点 OK 或 Cancel；返回 Optional：
     * <ul>
     *   <li>{@code Optional.of(result)} — OK，result 已经实时校验通过</li>
     *   <li>{@code Optional.empty()} — Cancel / 关闭</li>
     * </ul>
     *
     * @param owner            父窗口；对话框 owner-modal 挂到它上面，避免漂出主窗口
     * @param initialWorkspace 默认填入的工作空间；可空，空则回退到最近 workspace
     */
    public static Optional<NewProjectResult> show(Stage owner, Path initialWorkspace) {
        Dialog<NewProjectResult> dialog = new Dialog<>();
        dialog.setTitle("新建 EPUB 项目");
        dialog.setHeaderText("选择工作空间并填写项目信息");
        if (owner != null) {
            dialog.initOwner(owner);
        }
        DialogPane pane = dialog.getDialogPane();
        pane.getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        Node okBtn = pane.lookupButton(ButtonType.OK);
        okBtn.setDisable(true);

        URL fxml = NewProjectDialog.class.getResource(FXML_PATH);
        if (fxml == null) {
            throw new IllegalStateException("missing FXML: " + FXML_PATH);
        }
        // controller 必须在 result converter 里能拿到，但 FXML 加载完成后控制器实例就
        // 暴露在 loader.getController() 上。用长度为 1 的数组持有引用，是 Java 里
        // 「闭包」最轻量的写法，避免在静态方法里再造一层 helper 类。
        NewProjectDialogController[] holder = new NewProjectDialogController[1];
        try {
            FXMLLoader loader = new FXMLLoader(fxml);
            Node content = loader.load();
            holder[0] = loader.getController();
            holder[0].configure(okBtn, initialWorkspace);
            pane.setContent(content);
        } catch (IOException e) {
            throw new IllegalStateException("failed to load " + FXML_PATH, e);
        }

        dialog.setResultConverter(buttonType -> {
            if (buttonType != ButtonType.OK) {
                return null;
            }
            return holder[0].collectResult().orElse(null);
        });
        return dialog.showAndWait();
    }
}

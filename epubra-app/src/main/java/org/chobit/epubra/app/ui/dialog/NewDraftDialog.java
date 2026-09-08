package org.chobit.epubra.app.ui.dialog;

import org.chobit.epubra.app.ui.model.NewDraftResult;
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
 * 「新建图书草稿」对话框（模态阻塞）。
 */
public final class NewDraftDialog {

    private static final String FXML_PATH = "/org/chobit/epubra/app/view/new-draft-dialog.fxml";

    private NewDraftDialog() {
    }

    public static Optional<NewDraftResult> show(Stage owner, Path initialWorkspace) {
        Dialog<NewDraftResult> dialog = new Dialog<>();
        dialog.setTitle("新建图书");
        dialog.setHeaderText("选择工作空间并填写图书信息");
        if (owner != null) {
            dialog.initOwner(owner);
        }
        DialogPane pane = dialog.getDialogPane();
        pane.getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        Node okBtn = pane.lookupButton(ButtonType.OK);
        okBtn.setDisable(true);

        URL fxml = NewDraftDialog.class.getResource(FXML_PATH);
        if (fxml == null) {
            throw new IllegalStateException("missing FXML: " + FXML_PATH);
        }
        NewDraftDialogController[] holder = new NewDraftDialogController[1];
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

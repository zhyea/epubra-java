package org.chobit.epubra.app.ui.dialog;

import org.chobit.epubra.app.editor.ChapterSplitOps;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogPane;
import javafx.stage.Stage;

import java.io.IOException;
import java.net.URL;
import java.util.Optional;

/**
 * 「章节拆分」对话框（模态阻塞）。结构复用 {@link NewDraftDialog} 的 DialogPane + FXML 模式。
 */
public final class ChapterSplitDialog {

    private static final String FXML_PATH = "/org/chobit/epubra/app/view/chapter-split-dialog.fxml";

    private ChapterSplitDialog() {
    }

    /**
     * @param owner     宿主窗口
     * @param baseTitle 原章节标题（无标记段落的回退标题，也用于提示文案）
     * @return 确定时返回拆分参数；取消或校验不过返回 empty
     */
    public static Optional<ChapterSplitOps.Params> show(Stage owner, String baseTitle) {
        Dialog<ChapterSplitOps.Params> dialog = new Dialog<>();
        dialog.setTitle("章节拆分");
        dialog.setHeaderText("拆分章节：" + baseTitle);
        if (owner != null) {
            dialog.initOwner(owner);
        }
        DialogPane pane = dialog.getDialogPane();
        pane.getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        Node okBtn = pane.lookupButton(ButtonType.OK);
        okBtn.setDisable(true);

        URL fxml = ChapterSplitDialog.class.getResource(FXML_PATH);
        if (fxml == null) {
            throw new IllegalStateException("missing FXML: " + FXML_PATH);
        }
        ChapterSplitDialogController[] holder = new ChapterSplitDialogController[1];
        try {
            FXMLLoader loader = new FXMLLoader(fxml);
            Node content = loader.load();
            holder[0] = loader.getController();
            holder[0].configure(okBtn, baseTitle);
            pane.setContent(content);
        } catch (IOException e) {
            throw new IllegalStateException("failed to load " + FXML_PATH, e);
        }

        dialog.setResultConverter(buttonType -> buttonType == ButtonType.OK
                ? holder[0].collectResult().orElse(null)
                : null);
        // collectResult 校验不过返回 null → result 为 empty，调用方按取消处理
        return dialog.showAndWait();
    }
}

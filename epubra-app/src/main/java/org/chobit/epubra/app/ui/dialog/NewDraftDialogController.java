package org.chobit.epubra.app.ui.dialog;

import org.chobit.epubra.app.ui.model.NewDraftResult;
import org.chobit.epubra.app.workspace.WorkspaceStore;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.stage.DirectoryChooser;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 「新建图书草稿」对话框的表单控制器。
 */
public class NewDraftDialogController {

    private static final Pattern INVALID_NAME_CHARS = Pattern.compile("[\\\\/:*?\"<>|\\x00-\\x1F]");

    @FXML
    private TextField workspaceField;
    @FXML
    private Button browseWorkspaceBtn;
    @FXML
    private TextField nameField;
    @FXML
    private TextField titleField;
    @FXML
    private Label errorLabel;

    private Node okButton;
    private Path initialWorkspace;

    public void configure(Node okButton, Path initialWorkspace) {
        this.okButton = okButton;
        this.initialWorkspace = initialWorkspace;
        if (initialWorkspace != null) {
            workspaceField.setText(initialWorkspace.toString());
        } else {
            List<Path> recent = WorkspaceStore.recentExisting();
            if (!recent.isEmpty()) {
                workspaceField.setText(recent.get(0).toString());
            }
        }
        revalidate();
        workspaceField.textProperty().addListener((o, a, b) -> revalidate());
        nameField.textProperty().addListener((o, a, b) -> revalidate());
        nameField.textProperty().addListener((o, a, b) -> {
            if (titleField.getText().isBlank()) {
                titleField.setText(b);
            }
        });
        titleField.textProperty().addListener((o, a, b) -> revalidate());
    }

    @FXML
    private void onBrowseWorkspace() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("选择工作空间");
        if (initialWorkspace != null && Files.isDirectory(initialWorkspace)) {
            chooser.setInitialDirectory(initialWorkspace.toFile());
        } else {
            String current = workspaceField.getText();
            if (current != null && !current.isBlank()) {
                Path p = Path.of(current);
                if (Files.isDirectory(p)) {
                    chooser.setInitialDirectory(p.toFile());
                }
            }
        }
        java.io.File chosen = chooser.showDialog(browseWorkspaceBtn.getScene().getWindow());
        if (chosen != null) {
            workspaceField.setText(Path.of(chosen.getAbsolutePath()).toString());
        }
    }

    public Optional<NewDraftResult> collectResult() {
        String workspaceText = workspaceField.getText() == null ? "" : workspaceField.getText().trim();
        String name = nameField.getText() == null ? "" : nameField.getText().trim();
        String title = titleField.getText() == null ? "" : titleField.getText().trim();
        if (title.isBlank()) {
            title = name;
        }
        return Optional.of(new NewDraftResult(Path.of(workspaceText), name, title));
    }

    private void revalidate() {
        String wsText = workspaceField.getText() == null ? "" : workspaceField.getText().trim();
        String name = nameField.getText() == null ? "" : nameField.getText().trim();
        String reason = null;
        if (wsText.isEmpty()) {
            reason = "请选择工作空间目录";
        } else {
            Path ws = Path.of(wsText);
            if (!Files.isDirectory(ws)) {
                reason = "工作空间目录不存在";
            }
        }
        if (reason == null) {
            if (name.isEmpty()) {
                reason = "请输入图书文件名";
            } else if (INVALID_NAME_CHARS.matcher(name).find()) {
                reason = "图书文件名包含非法字符 (\\\\ / : * ? \" < > | 或控制字符)";
            } else if (name.equals(".") || name.equals("..")) {
                reason = "图书文件名不能是 . 或 ..";
            }
        }
        if (reason == null) {
            Path ws = Path.of(wsText);
            Path target = ws.resolve(name + ".draft");
            if (Files.exists(target)) {
                reason = "同名图书草稿已存在：" + target;
            }
        }
        boolean ok = reason == null;
        if (okButton != null) {
            okButton.setDisable(!ok);
        }
        if (errorLabel != null) {
            errorLabel.setText(reason == null ? "" : reason);
            errorLabel.setVisible(reason != null);
            errorLabel.setManaged(reason != null);
        }
    }
}

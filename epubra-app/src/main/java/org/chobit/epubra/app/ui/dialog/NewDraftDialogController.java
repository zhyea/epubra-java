package org.chobit.epubra.app.ui.dialog;

import org.chobit.epubra.app.ui.model.NewDraftResult;
import org.chobit.epubra.app.workspace.WorkspaceStore;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

import org.chobit.epubra.app.ui.model.NewDraftResult.Mode;

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
    private ChoiceBox<Mode> modeChoice;
    @FXML
    private Label sourceLabel;
    @FXML
    private TextField sourceField;
    @FXML
    private Button browseSourceBtn;
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
        modeChoice.getItems().setAll(Mode.values());
        modeChoice.setValue(Mode.EMPTY);
        modeChoice.getSelectionModel().selectedItemProperty()
                .addListener((o, a, b) -> {
                    updateSourceControls();
                    revalidate();
                });
        revalidate();
        workspaceField.textProperty().addListener((o, a, b) -> revalidate());
        nameField.textProperty().addListener((o, a, b) -> revalidate());
        nameField.textProperty().addListener((o, a, b) -> {
            if (titleField.getText().isBlank()) {
                titleField.setText(b);
            }
        });
        titleField.textProperty().addListener((o, a, b) -> revalidate());
        sourceField.textProperty().addListener((o, a, b) -> revalidate());
        updateSourceControls();
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

    @FXML
    private void onBrowseSource() {
        Mode mode = modeChoice.getValue();
        if (mode == null || mode == Mode.EMPTY) {
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle(mode == Mode.EPUB ? "选择 EPUB 文件" : "选择 TXT 文件");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(
                mode == Mode.EPUB ? "EPUB 文件" : "TXT 文件",
                mode == Mode.EPUB ? "*.epub" : "*.txt"));
        Path current = pathFromField(sourceField.getText());
        if (current != null) {
            Path directory = Files.isDirectory(current) ? current : current.getParent();
            if (directory != null && Files.isDirectory(directory)) {
                chooser.setInitialDirectory(directory.toFile());
            }
        }
        java.io.File chosen = chooser.showOpenDialog(browseSourceBtn.getScene().getWindow());
        if (chosen == null) {
            return;
        }
        Path source = Path.of(chosen.getAbsolutePath());
        sourceField.setText(source.toString());
        if (nameField.getText() == null || nameField.getText().isBlank()) {
            nameField.setText(fileStem(source));
        }
        if (titleField.getText() == null || titleField.getText().isBlank()) {
            titleField.setText(fileStem(source));
        }
    }

    public Optional<NewDraftResult> collectResult() {
        String workspaceText = workspaceField.getText() == null ? "" : workspaceField.getText().trim();
        String name = nameField.getText() == null ? "" : nameField.getText().trim();
        String title = titleField.getText() == null ? "" : titleField.getText().trim();
        if (title.isBlank()) {
            title = name;
        }
        // 工作空间路径非法时按「无结果」返回（对话框调用方把它当取消处理），
        // 不让 InvalidPathException 从 setResultConverter 冒到 showAndWait() 调用方。
        Path workspace = pathFromField(workspaceText);
        if (workspace == null) {
            return Optional.empty();
        }
        return Optional.of(new NewDraftResult(
                workspace, name, title, modeChoice.getValue(),
                pathFromField(sourceField.getText())));
    }

    private void revalidate() {
        String wsText = workspaceField.getText() == null ? "" : workspaceField.getText().trim();
        String name = nameField.getText() == null ? "" : nameField.getText().trim();
        String reason = null;
        Path workspace = null;
        if (wsText.isEmpty()) {
            reason = "请选择工作空间目录";
        } else {
            // ⚠ 工作空间输入框是可编辑的 TextField，每次击键都会走到这里。Windows 下
            //   Path.of("*") / Path.of("C:\\a|b") 会抛 InvalidPathException —— 直接调用会让
            //   校验中断在半路，OK 按钮停在旧状态，用户还看不到任何原因。
            workspace = pathFromField(wsText);
            if (workspace == null) {
                reason = "工作空间路径包含非法字符";
            } else if (!Files.isDirectory(workspace)) {
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
            Path target = workspace.resolve(name + ".draft");
            if (Files.exists(target)) {
                reason = "同名图书草稿已存在：" + target;
            }
        }
        Mode mode = modeChoice == null ? Mode.EMPTY : modeChoice.getValue();
        if (reason == null && mode != null && mode != Mode.EMPTY) {
            Path source = pathFromField(sourceField.getText());
            if (source == null) {
                reason = "请选择要导入的文件";
            } else if (!Files.isRegularFile(source)) {
                reason = "导入文件不存在";
            } else if (!hasExpectedExtension(source, mode)) {
                reason = mode == Mode.EPUB ? "请选择 .epub 文件" : "请选择 .txt 文件";
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

    private void updateSourceControls() {
        boolean visible = modeChoice.getValue() != null && modeChoice.getValue() != Mode.EMPTY;
        sourceLabel.setVisible(visible);
        sourceLabel.setManaged(visible);
        sourceField.setVisible(visible);
        sourceField.setManaged(visible);
        browseSourceBtn.setVisible(visible);
        browseSourceBtn.setManaged(visible);
    }

    private static Path pathFromField(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return Path.of(text.trim());
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static boolean hasExpectedExtension(Path source, Mode mode) {
        String fileName = source.getFileName() == null
                ? "" : source.getFileName().toString().toLowerCase();
        return mode == Mode.EPUB
                ? fileName.endsWith(".epub")
                : fileName.endsWith(".txt");
    }

    private static String fileStem(Path source) {
        String fileName = source.getFileName() == null ? "" : source.getFileName().toString();
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }
}

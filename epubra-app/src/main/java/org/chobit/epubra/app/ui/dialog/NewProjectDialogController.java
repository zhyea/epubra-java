package org.chobit.epubra.app.ui.dialog;

import org.chobit.epubra.app.support.workspace.RecentProjectsStore;
import org.chobit.epubra.app.ui.model.NewProjectResult;
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
 * 「新建 EPUB 项目」对话框的表单控制器。
 *
 * <p>布局：{@code new-project-dialog.fxml}，由 {@link NewProjectDialog#show} 加载。
 *
 * <p>字段：
 * <ul>
 *   <li>workspace 路径（必填，须是已存在的目录）</li>
 *   <li>项目名（必填，禁止文件系统非法字符）</li>
 *   <li>标题（可选，留空回退为项目名）</li>
 * </ul>
 *
 * <p>OK 按钮按字段实时校验动态启用 / 禁用——避免用户在新建流程路径里才发现输入不合法。
 */
public class NewProjectDialogController {

    /** 文件系统非法字符（Windows 视角）；含 NUL / 控制字符。 */
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

    /**
     * 由 {@link NewProjectDialog#show} 注入运行时依赖：OK 按钮节点 + 默认 workspace。
     * 必须在 FXML 加载完成后、对话框展示前调用。
     */
    public void configure(Node okButton, Path initialWorkspace) {
        this.okButton = okButton;
        this.initialWorkspace = initialWorkspace;
        if (initialWorkspace != null) {
            workspaceField.setText(initialWorkspace.toString());
        } else {
            // 回退：尝试从历史里挑最近的一个 workspace，避免每次都空着
            List<String> recent = RecentProjectsStore.workspaces();
            if (!recent.isEmpty()) {
                Path candidate = Path.of(recent.get(0));
                if (Files.isDirectory(candidate)) {
                    workspaceField.setText(candidate.toString());
                }
            }
        }
        revalidate();
        // 失焦即重新校验
        workspaceField.textProperty().addListener((o, a, b) -> revalidate());
        nameField.textProperty().addListener((o, a, b) -> revalidate());
        // 项目名变化时若标题空着，让标题跟随项目名——少敲字段
        nameField.textProperty().addListener((o, a, b) -> {
            if (titleField.getText().isBlank()) {
                titleField.setText(b);
            }
        });
        // 标题留空 → 自动回退为项目名（实现处见 collectResult）
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
        // 把当前已选 workspace 作为可选初始位置，没有就不动
        java.io.File chosen = chooser.showDialog(browseWorkspaceBtn.getScene().getWindow());
        if (chosen != null) {
            workspaceField.setText(Path.of(chosen.getAbsolutePath()).toString());
        }
    }

    /** 收集当前字段值并打包成结果，标题空回退到项目名。 */
    public Optional<NewProjectResult> collectResult() {
        String workspaceText = workspaceField.getText() == null ? "" : workspaceField.getText().trim();
        String name = nameField.getText() == null ? "" : nameField.getText().trim();
        String title = titleField.getText() == null ? "" : titleField.getText().trim();
        if (title.isBlank()) {
            title = name;
        }
        return Optional.of(new NewProjectResult(Path.of(workspaceText), name, title));
    }

    /**
     * 实时校验：workspace 存在且是目录 + 项目名非空且无非法字符 → OK 可用；
     * 否则禁用并把失败原因打到 errorLabel。
     */
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
                reason = "请输入项目名";
            } else if (INVALID_NAME_CHARS.matcher(name).find()) {
                reason = "项目名包含非法字符 (\\\\ / : * ? \" < > | 或控制字符)";
            } else if (name.equals(".") || name.equals("..")) {
                reason = "项目名不能是 . 或 ..";
            }
        }
        if (reason == null) {
            Path ws = Path.of(wsText);
            Path target = ws.resolve(name);
            if (Files.exists(target)) {
                reason = "同名目录或文件已存在：" + target;
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

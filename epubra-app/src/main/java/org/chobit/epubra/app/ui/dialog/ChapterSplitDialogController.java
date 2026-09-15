package org.chobit.epubra.app.ui.dialog;

import org.chobit.epubra.app.editor.ChapterSplitOps;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 「章节拆分」对话框的表单控制器。
 *
 * <p>三种拆分方式（见 {@link ChapterSplitOps.Mode}）各自对应一行参数，随「拆分方式」
 * 下拉切换显隐；前后缀模式额外有预设下拉（选中即填充，仍可手改成自定义）。
 * 校验失败写 {@code errorLabel} 并禁用确定按钮，与 {@code NewDraftDialogController} 同口径。
 */
public class ChapterSplitDialogController {

    /** 前后缀预设：显示文字 → {前缀, 后缀}；「自定义」不改字段值。 */
    private static final List<String[]> PRESETS = List.of(
            new String[]{"第…章", "第", "章"},
            new String[]{"第…节", "第", "节"},
            new String[]{"第…卷", "第", "卷"},
            new String[]{"Chapter N", "Chapter ", ""},
            new String[]{"自定义", null, null});

    private static final String MODE_MARKER = "前后缀标记（第…章 / Chapter N …）";
    private static final String MODE_REGEX = "正则表达式";
    private static final String MODE_LENGTH = "每段字数";

    @FXML
    private ChoiceBox<String> modeChoice;
    @FXML
    private ChoiceBox<String> presetChoice;
    @FXML
    private Label presetLabel;
    @FXML
    private Label prefixLabel;
    @FXML
    private TextField prefixField;
    @FXML
    private Label suffixLabel;
    @FXML
    private TextField suffixField;
    @FXML
    private Label regexLabel;
    @FXML
    private TextField regexField;
    @FXML
    private Label lengthLabel;
    @FXML
    private TextField lengthField;
    @FXML
    private Label errorLabel;

    private Node okButton;

    /** FXML 加载后由 {@code ChapterSplitDialog} 注入确定按钮与原章节标题。 */
    public void configure(Node okButton, String baseTitle) {
        this.okButton = okButton;
        modeChoice.getItems().setAll(MODE_MARKER, MODE_REGEX, MODE_LENGTH);
        modeChoice.setValue(MODE_MARKER);
        presetChoice.getItems().setAll(PRESETS.stream().map(p -> p[0]).toList());
        presetChoice.setValue(PRESETS.get(0)[0]);
        applyPreset(PRESETS.get(0));

        modeChoice.getSelectionModel().selectedItemProperty()
                .addListener((o, a, b) -> {
                    updateModeControls();
                    revalidate();
                });
        presetChoice.getSelectionModel().selectedItemProperty()
                .addListener((o, a, b) -> {
                    presetByName(b).ifPresent(this::applyPreset);
                    revalidate();
                });
        prefixField.textProperty().addListener((o, a, b) -> revalidate());
        suffixField.textProperty().addListener((o, a, b) -> revalidate());
        regexField.textProperty().addListener((o, a, b) -> revalidate());
        lengthField.textProperty().addListener((o, a, b) -> revalidate());
        updateModeControls();
        revalidate();
    }

    /** 收集表单为拆分参数；校验不过时返回 empty（对话框调用方按取消处理）。 */
    public Optional<ChapterSplitOps.Params> collectResult() {
        if (MODE_REGEX.equals(modeChoice.getValue())) {
            return Optional.of(new ChapterSplitOps.Params(
                    ChapterSplitOps.Mode.REGEX, null, null,
                    regexField.getText() == null ? "" : regexField.getText().trim(),
                    0, null));
        }
        if (MODE_LENGTH.equals(modeChoice.getValue())) {
            int length = parseLength(lengthField.getText());
            if (length < 1) {
                return Optional.empty();
            }
            return Optional.of(new ChapterSplitOps.Params(
                    ChapterSplitOps.Mode.LENGTH, null, null, null, length, null));
        }
        return Optional.of(new ChapterSplitOps.Params(
                ChapterSplitOps.Mode.MARKER,
                prefixField.getText() == null ? "" : prefixField.getText(),
                suffixField.getText() == null ? "" : suffixField.getText(),
                null, 0, null));
    }

    // ---- 内部 ----

    private void updateModeControls() {
        boolean marker = MODE_MARKER.equals(modeChoice.getValue());
        boolean regex = MODE_REGEX.equals(modeChoice.getValue());
        boolean length = MODE_LENGTH.equals(modeChoice.getValue());
        setVisible(presetLabel, marker);
        setVisible(prefixLabel, marker);
        setVisible(prefixField, marker);
        setVisible(suffixLabel, marker);
        setVisible(suffixField, marker);
        setVisible(regexLabel, regex);
        setVisible(regexField, regex);
        setVisible(lengthLabel, length);
        setVisible(lengthField, length);
    }

    private void revalidate() {
        String reason = null;
        if (MODE_REGEX.equals(modeChoice.getValue())) {
            String regex = regexField.getText() == null ? "" : regexField.getText().trim();
            if (regex.isEmpty()) {
                reason = "请输入正则表达式";
            } else {
                try {
                    Pattern.compile(regex);
                } catch (PatternSyntaxException failed) {
                    reason = "正则表达式无效：" + failed.getDescription();
                }
            }
        } else if (MODE_LENGTH.equals(modeChoice.getValue())) {
            if (parseLength(lengthField.getText()) < 1) {
                reason = "每段字数必须是不小于 1 的整数";
            }
        } else {
            String prefix = prefixField.getText() == null ? "" : prefixField.getText().trim();
            String suffix = suffixField.getText() == null ? "" : suffixField.getText().trim();
            if (prefix.isEmpty() && suffix.isEmpty()) {
                reason = "前缀与后缀不能同时为空";
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

    private void applyPreset(String[] preset) {
        if (preset[1] != null) {
            prefixField.setText(preset[1]);
        }
        if (preset[2] != null) {
            suffixField.setText(preset[2]);
        }
    }

    private Optional<String[]> presetByName(String name) {
        return PRESETS.stream().filter(p -> p[0].equals(name)).findFirst();
    }

    /** 宽松解析正整数；非法输入返回 0（调用方视为未通过校验）。 */
    private static int parseLength(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static void setVisible(Node node, boolean visible) {
        node.setVisible(visible);
        node.setManaged(visible);
    }
}

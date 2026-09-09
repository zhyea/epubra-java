package org.chobit.epubra.app.ui.model;

import java.nio.file.Path;

import org.chobit.epubra.app.ui.dialog.NewDraftDialog;

/**
 * 「新建图书草稿」对话框的返回结果：调用方从 {@link NewDraftDialog#show} 拿到。
 *
 * @param workspace 选定的工作空间根目录
 * @param name      草稿文件名主干（不含 .draft）
 * @param title     图书标题；空表示与 name 相同
 * @param mode      新建空白图书，或从 EPUB / TXT 导入
 * @param source    导入源文件；新建空白图书时为空
 */
public record NewDraftResult(Path workspace, String name, String title,
                             Mode mode, Path source) {

    public NewDraftResult(Path workspace, String name, String title) {
        this(workspace, name, title, Mode.EMPTY, null);
    }

    public enum Mode {
        EMPTY("新建空白 EPUB"),
        EPUB("导入已有 EPUB"),
        TXT("导入 TXT");

        private final String label;

        Mode(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /** 解析后的草稿文件路径：{@code workspace/name.draft}。 */
    public Path draftFile() {
        return workspace.resolve(name + ".draft");
    }

    public boolean importsSource() {
        return mode != Mode.EMPTY;
    }
}

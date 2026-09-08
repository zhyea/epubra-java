package org.chobit.epubra.app.ui.model;

import java.nio.file.Path;

import org.chobit.epubra.app.ui.dialog.NewDraftDialog;

/**
 * 「新建图书草稿」对话框的返回结果：调用方从 {@link NewDraftDialog#show} 拿到。
 *
 * @param workspace 选定的工作空间根目录
 * @param name      草稿文件名主干（不含 .draft）
 * @param title     图书标题；空表示与 name 相同
 */
public record NewDraftResult(Path workspace, String name, String title) {

    /** 解析后的草稿文件路径：{@code workspace/name.draft}。 */
    public Path draftFile() {
        return workspace.resolve(name + ".draft");
    }
}

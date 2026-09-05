package com.epubra.app.controller;

import java.nio.file.Path;

/**
 * 「新建 EPUB 项目」对话框的返回结果：调用方从 {@link NewProjectDialog#show} 拿到。
 *
 * @param workspace 选定的工作空间根目录
 * @param name      项目目录名（同时也是 EPUB 文件名，不带 .epub）
 * @param title     EPUB 标题；空表示与 name 相同
 */
public record NewProjectResult(Path workspace, String name, String title) {

    /** 解析后的 EPUB 文件路径：{@code workspace/name/name.epub}。 */
    public Path epubFile() {
        return workspace.resolve(name).resolve(name + ".epub");
    }
}

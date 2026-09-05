package com.epubra.app.controller;

import com.epubra.app.support.BookContext;
import com.epubra.app.support.MetadataDraft;
import com.epubra.app.support.MetadataOps;
import com.epubra.epublib.domain.Metadata;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;

import java.util.function.Consumer;

/**
 * 元数据面板控制器——书名 / 作者 / 语言 / 出版者 / 简介 5 个表单字段的读写编排。
 *
 * <p>作为 {@code metadata-view.fxml} 的 {@code fx:controller} 由 FXML 实例化：表单控件经
 * {@code @FXML} 注入；{@link BookContext} 与回调在父控制器 {@code initialize()} 阶段通过
 * {@link #bind} 注入。本类不得定义 {@code initialize()}。
 *
 * <p>字段级相等判定与归一化全部委托 {@link MetadataOps} / {@link MetadataDraft}；
 * 本类只负责「控件 ↔ draft」与「draft ↔ {@link Metadata}」之间的搬运。
 */
public class MetadataViewController {

    @FXML
    private TextField titleField;
    @FXML
    private TextField authorField;
    @FXML
    private TextField languageField;
    @FXML
    private TextField publisherField;
    @FXML
    private TextArea descriptionArea;
    @FXML
    private Label identifierLabel;

    private BookContext ctx;
    private Runnable recordBeforeChange;
    private Runnable markDirty;
    private Runnable refreshAll;
    private Consumer<String> setStatus;

    /** FXML 加载后由父控制器注入运行时依赖；必须在任何 onAction 触发前完成。 */
    public void bind(BookContext ctx, Runnable recordBeforeChange, Runnable markDirty,
                     Runnable refreshAll, Consumer<String> setStatus) {
        this.ctx = ctx;
        this.recordBeforeChange = recordBeforeChange;
        this.markDirty = markDirty;
        this.refreshAll = refreshAll;
        this.setStatus = setStatus;
    }

    /** 「应用修改」按钮：先拍快照再写回（顺序见 {@link #recordBeforeChange} 的语义）。 */
    @FXML
    public void onApplyMetadata() {
        if (!MetadataOps.isDirty(ctx.book().metadata(), draftFromFields())) {
            setStatus.accept("元数据没有变化");
            return;
        }
        // 必须先拍快照再写回：beginChange() 的顺序是先写回再快照，
        // 那样快照里存的是改后的元数据，撤销「应用修改」会什么都没发生。
        recordBeforeChange.run();
        flush();
        markDirty.run();
        refreshAll.run();
        setStatus.accept("元数据已更新");
    }

    /** 把 5 个表单字段打包成不可变 {@link MetadataDraft}，留给 {@link MetadataOps} 处理。 */
    public MetadataDraft draftFromFields() {
        return new MetadataDraft(
                titleField.getText(),
                authorField.getText(),
                languageField.getText(),
                publisherField.getText(),
                descriptionArea.getText());
    }

    /** 把 {@link Metadata} 投影回元数据面板上的 5 个输入控件与标识符标签。 */
    public void loadIntoFields(Metadata metadata) {
        MetadataDraft draft = MetadataOps.snapshot(metadata);
        titleField.setText(draft.title());
        authorField.setText(draft.authors());
        languageField.setText(draft.language());
        publisherField.setText(draft.publisher());
        descriptionArea.setText(draft.description());
        Metadata.Identifier identifier = metadata.primaryIdentifier();
        identifierLabel.setText(identifier == null ? "—（保存时自动生成）" : identifier.raw());
    }

    /** 把面板上的当前值写回 {@link Metadata}；撤销快照回放时由 UndoController 调用。 */
    public void flush() {
        MetadataOps.apply(ctx.book().metadata(), draftFromFields());
    }
}

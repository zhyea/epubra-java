package org.chobit.epubra.app.controller;

import org.chobit.epubra.app.support.BookContext;
import org.chobit.epubra.app.support.CoverImageInfo;
import org.chobit.epubra.app.support.CoverOps;
import org.chobit.epubra.app.support.CoverOps.CoverState;
import org.chobit.epubra.app.support.MetadataDraft;
import org.chobit.epubra.app.support.MetadataOps;
import org.chobit.epubra.lib.domain.Book;
import org.chobit.epubra.lib.domain.MediaTypes;
import org.chobit.epubra.lib.domain.Metadata;
import org.chobit.epubra.lib.domain.Resource;
import org.chobit.epubra.app.support.CoverImageInfo.Dimension;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputControl;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * 元数据面板控制器——书名 / 作者 / 语言 / 出版者 / 简介表单 + 封面卡三态。
 *
 * <p>作为 {@code metadata-view.fxml} 的 {@code fx:controller} 由 FXML 实例化：表单控件经
 * {@code @FXML} 注入；{@link BookContext} 与回调在父控制器 {@code initialize()} 阶段通过
 * {@link #bind} 注入。本类不得定义 {@code initialize()}。
 *
 * <p>封面卡三态逻辑全部委托 {@link CoverOps}（状态判定 + pick/clear）与
 * {@link CoverImageInfo}（图片头解析宽高）。本类只做「控件 ↔ 状态」的可视化映射：
 * 「重新选择 / 更换」都共用一个 FileChooser 入口（{@link #onPickCover}），状态机的细节藏在
 * {@link #refreshCoverCard()} 内部。
 */
public class MetadataViewController {

    // --- 表单字段 ---
    @FXML private TextField titleField;
    @FXML private TextField authorField;
    @FXML private TextField languageField;
    @FXML private TextField publisherField;
    @FXML private TextArea descriptionArea;
    @FXML private Label identifierLabel;

    // --- 封面卡 ---
    @FXML private VBox coverCard;
    @FXML private Label coverStateBadge;
    @FXML private HBox coverSetBox;
    @FXML private HBox coverSetActions;
    @FXML private ImageView coverThumb;
    @FXML private Label coverNameLabel;
    @FXML private Label coverMetaLabel;
    @FXML private Label coverHrefLabel;
    @FXML private Button chooseCoverButton;
    @FXML private Button clearCoverButton;
    @FXML private VBox coverEmptyBox;
    @FXML private Button chooseCoverEmptyButton;
    @FXML private HBox coverDanglingBox;
    @FXML private Label coverDanglingDetail;
    @FXML private HBox coverDanglingActions;
    @FXML private Button replaceCoverButton;
    @FXML private Button dropCoverReferenceButton;

    private BookContext ctx;
    private Runnable recordBeforeChange;
    private Runnable markDirty;
    private Runnable refreshAll;
    private Runnable refreshResources;
    private Consumer<String> setStatus;

    /** FXML 加载后由父控制器注入运行时依赖；必须在任何 onAction 触发前完成。 */
    public void bind(BookContext ctx, Runnable recordBeforeChange, Runnable markDirty,
                     Runnable refreshAll, Runnable refreshResources,
                     Consumer<String> setStatus) {
        this.ctx = ctx;
        this.recordBeforeChange = recordBeforeChange;
        this.markDirty = markDirty;
        this.refreshAll = refreshAll;
        this.refreshResources = refreshResources;
        this.setStatus = setStatus;
        wireAutoApply();
    }

    /**
     * 字段失焦即应用——把元数据的提交模型对齐正文的「输入即生效」。
     *
     * <p>原设计只有「应用修改」按钮一个提交入口，用户改了书名去点别的视图时输入还悬着，
     * 没有任何「未提交」标记，实际生效时机取决于 UndoController 何时回调 flush，
     * 对用户不可见也不可控。
     *
     * <p>失焦应用前先做 {@link MetadataOps#isDirty} 变更判定：没改动就不拍快照，
     * 避免在字段间 Tab 穿梭时产生一串空撤销步。
     */
    private void wireAutoApply() {
        for (TextInputControl field : editableFields()) {
            field.focusedProperty().addListener((obs, wasFocused, isNowFocused) -> {
                if (!isNowFocused) {
                    applyIfChanged(false);
                }
            });
        }
    }

    /** 5 个可编辑字段；对未注入场景（如直接 new 不加载 FXML 的测试）做 null 过滤。 */
    private List<TextInputControl> editableFields() {
        List<TextInputControl> fields = new ArrayList<>();
        addIfPresent(fields, titleField);
        addIfPresent(fields, authorField);
        addIfPresent(fields, languageField);
        addIfPresent(fields, publisherField);
        addIfPresent(fields, descriptionArea);
        return fields;
    }

    private static void addIfPresent(List<TextInputControl> target, TextInputControl field) {
        if (field != null) {
            target.add(field);
        }
    }

    /**
     * 「应用修改」按钮：显式提交，走完整 refresh（目录树 / 资源等联动重画）。
     *
     * <p>自动应用已覆盖绝大多数场景，此按钮保留给「想立刻看到全书联动效果」的用户。
     */
    @FXML
    public void onApplyMetadata() {
        applyIfChanged(true);
    }

    /**
     * 仅在确有变更时写回 {@link Metadata}。
     *
     * @param fullRefresh 是否触发 {@code refreshAll}。自动应用走 {@code false}——每次切字段
     *                    都重建目录树是浪费；显式点按钮走 {@code true}，保证界面完全同步。
     */
    private void applyIfChanged(boolean fullRefresh) {
        if (ctx == null || ctx.book() == null) {
            return;
        }
        if (!MetadataOps.isDirty(ctx.book().metadata(), draftFromFields())) {
            if (fullRefresh) {
                setStatus.accept("元数据没有变化");
            }
            return;
        }
        // 必须先拍快照再写回：beginChange() 的顺序是先写回再快照，
        // 那样快照里存的是改后的元数据，撤销「应用修改」会什么都没发生。
        recordBeforeChange.run();
        flush();
        markDirty.run();
        if (fullRefresh) {
            refreshAll.run();
        }
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

    /**
     * 把 {@link Metadata} 投影回元数据面板上的 5 个输入控件与标识符标签，
     * 并立即刷新封面卡（三态判定 + 缩略图加载）。
     */
    public void loadIntoFields(Metadata metadata) {
        MetadataDraft draft = MetadataOps.snapshot(metadata);
        titleField.setText(draft.title());
        authorField.setText(draft.authors());
        languageField.setText(draft.language());
        publisherField.setText(draft.publisher());
        descriptionArea.setText(draft.description());
        Metadata.Identifier identifier = metadata.primaryIdentifier();
        identifierLabel.setText(identifier == null ? "—（保存时自动生成）" : identifier.raw());
        refreshCoverCard();
    }

    /**
     * 把面板上的当前值写回 {@link Metadata}；撤销快照回放时由 UndoController 调用。
     */
    public void flush() {
        MetadataOps.apply(ctx.book().metadata(), draftFromFields());
    }

    // ============================================================ 封面卡

    /**
     * 按 {@link CoverOps#describe} 决定封面卡的可见布局：EMPTY / SET / DANGLING。
     *
     * <p>外部触发点：{@code MainController.refreshAll()}、资源表改封面、保存后重渲染。
     * 任何一处都会先经 {@link #loadIntoFields(Metadata)} 或者直接调本方法。
     */
    public void refreshCoverCard() {
        if (ctx == null) {
            return;
        }
        Book book = ctx.book();
        CoverState state = CoverOps.describe(book);

        // 三态通用：标题徽章跟随状态切换
        if (coverStateBadge != null) {
            coverStateBadge.setText(stateText(state));
        }

        boolean setVisible = state == CoverState.SET;
        boolean emptyVisible = state == CoverState.EMPTY;
        boolean danglingVisible = state == CoverState.DANGLING;

        showAndManage(coverSetBox, setVisible);
        showAndManage(coverSetActions, setVisible);
        showAndManage(coverEmptyBox, emptyVisible);
        showAndManage(chooseCoverEmptyButton, emptyVisible);
        showAndManage(coverDanglingBox, danglingVisible);
        showAndManage(coverDanglingDetail, danglingVisible);
        showAndManage(coverDanglingActions, danglingVisible);

        if (setVisible) {
            Resource cover = book.coverResource().orElse(null);
            renderSet(cover);
        }
        if (danglingVisible) {
            renderDangling(book);
        }
    }

    private void renderSet(Resource cover) {
        if (cover == null) {
            return;
        }
        if (coverNameLabel != null) {
            coverNameLabel.setText(cover.fileName());
        }
        if (coverHrefLabel != null) {
            coverHrefLabel.setText(cover.href());
        }
        if (coverMetaLabel != null) {
            coverMetaLabel.setText(coverMetaText(cover));
        }
        if (coverThumb != null) {
            // Image 是 toolkit 依赖；图片格式 / 损坏会让 Image 设为 null 不抛异常，
            // 这里用 bytes 喂入，ImageView 自然什么都不显示，封面卡降级为「无缩略图但有文件名」。
            try {
                Image image = new Image(new ByteArrayInputStream(cover.data()));
                if (image.isBackgroundLoading() || image.getWidth() > 0) {
                    coverThumb.setImage(image);
                }
            } catch (Throwable ignored) {
                coverThumb.setImage(null);
            }
        }
    }

    private void renderDangling(Book book) {
        if (coverDanglingDetail != null) {
            coverDanglingDetail.setText(
                    "封面引用 resource id “" + book.coverResourceId()
                            + "” 在 manifest 中找不到（校验规则 D18）。"
                            + " 可「清除引用」回到未设置，或「重新选择」指定新封面。");
        }
    }

    private static String coverMetaText(Resource resource) {
        String typeLabel = typeLabel(resource.mediaType());
        // 大小
        int bytes = resource.data().length;
        String sizeText;
        if (bytes < 1024) {
            sizeText = bytes + " B";
        } else if (bytes < 1024 * 1024) {
            sizeText = String.format("%.1f KB", bytes / 1024.0);
        } else {
            sizeText = String.format("%.2f MB", bytes / (1024.0 * 1024.0));
        }
        // 尺寸
        Optional<Dimension> dim = CoverImageInfo.read(resource.data());
        if (dim.isPresent()) {
            return typeLabel + " · " + sizeText + " · " + dim.get().width() + " × " + dim.get().height();
        }
        return typeLabel + " · " + sizeText;
    }

    private static String typeLabel(String mediaType) {
        if (mediaType == null) {
            return "未知";
        }
        return switch (mediaType) {
            case "image/png" -> "PNG";
            case "image/jpeg" -> "JPEG";
            case "image/gif" -> "GIF";
            case "image/webp" -> "WebP";
            case "image/svg+xml" -> "SVG";
            default -> mediaType;
        };
    }

    private static String stateText(CoverState state) {
        return switch (state) {
            case EMPTY -> "未设置";
            case SET -> "已设置";
            case DANGLING -> "引用失效";
        };
    }

    /** FileChooser 入口：SET / EMPTY / DANGLING 三态共用。 */
    @FXML
    public void onPickCover() {
        if (ctx == null || ctx.book() == null) {
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle("选择封面");
        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("封面图片",
                        "*.png", "*.jpg", "*.jpeg", "*.gif", "*.webp", "*.svg"),
                new FileChooser.ExtensionFilter("所有文件", "*.*"));
        File file = chooser.showOpenDialog(ctx.stage());
        if (file == null) {
            return;
        }
        Resource resource = importCoverFile(file);
        if (resource == null) {
            return;
        }
        recordBeforeChange.run();
        CoverOps.set(ctx.book(), resource);
        markDirty.run();
        if (refreshAll != null) {
            refreshAll.run();
        } else if (refreshResources != null) {
            refreshResources.run();
        }
        setStatus.accept("已设为封面：" + file.getName());
    }

    /**
     * 把选中的图片作为封面导入：若书内已有同 href 资源（一般没有，刚选的文件不会撞名）则
     * 复用，否则走 {@link Book#addResource(java.nio.file.Path)}。失败时弹错误并返回 null。
     */
    private Resource importCoverFile(File file) {
        if (ctx == null || ctx.book() == null) {
            return null;
        }
        try {
            byte[] data = Files.readAllBytes(file.toPath());
            String fileName = file.getName();
            String mediaType = MediaTypes.guessByExtension(fileName);
            // 优先复用同 href + mediaType 的资源
            for (Resource existing : ctx.book().resources().all()) {
                if (fileName.equals(existing.fileName())
                        && mediaType.equals(existing.mediaType())) {
                    return existing;
                }
            }
            return ctx.book().addResource(file.toPath());
        } catch (IOException e) {
            setStatus.accept("封面导入失败：" + e.getMessage());
            return null;
        }
    }

    /**
     * 移除 / 清除引用按钮：不论当前是 SET 还是 DANGLING，都把封面设为 null。
     * 这是设计文档里规定的「清除引用」语义——不清图片资源（正文可能仍引用）。
     */
    @FXML
    public void onClearCover() {
        if (ctx == null || ctx.book() == null) {
            return;
        }
        recordBeforeChange.run();
        CoverOps.clear(ctx.book());
        markDirty.run();
        if (refreshAll != null) {
            refreshAll.run();
        } else if (refreshResources != null) {
            refreshResources.run();
        }
        setStatus.accept("已移除封面");
    }

    private static void showAndManage(Node node, boolean visible) {
        if (node == null) {
            return;
        }
        node.setVisible(visible);
        node.setManaged(visible);
    }
}

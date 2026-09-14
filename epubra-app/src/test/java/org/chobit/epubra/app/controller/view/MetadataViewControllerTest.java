package org.chobit.epubra.app.controller.view;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * 元数据面板投影的健壮性守卫。
 *
 * <p>{@code MetadataViewController} 的 5 个输入控件与标识符标签都是 {@code @FXML} 私有字段，
 * 只有经 FXML 注入后才有值。但 {@code loadIntoFields} 是「把当前 Book 的元数据刷到面板上」
 * 的下发式方法（{@code MainController.refreshAll()} 会无条件调），因此它必须容忍两种缺省：
 * <ul>
 *   <li>{@code metadata == null}（无书打开）；</li>
 *   <li>字段未注入（不经 FXML 直接 new 控制器）。</li>
 * </ul>
 * 同类的 {@code textOf} / {@code editableFields()} 早已按这套口径判空，本类只是让
 * {@code loadIntoFields} 跟上，避免同类里两套判空标准。
 *
 * <p>反方向的 {@code flush()} 不在此列：它把面板写回 {@code ctx.book()}，没有绑定的
 * BookContext 就没有写回目标，属调用方前置条件（只会在有书时被 UndoActivity / 应用修改触发），
 * 不做「静默什么都不干」的兜底。
 */
class MetadataViewControllerTest {

    @Test
    void loadIntoFieldsToleratesNullMetadataAndUnwiredFields() {
        MetadataViewController controller = new MetadataViewController();

        assertDoesNotThrow(() -> controller.loadIntoFields(null),
                "无书（metadata 为 null）+ 字段未注入时不该抛 NPE");
    }
}

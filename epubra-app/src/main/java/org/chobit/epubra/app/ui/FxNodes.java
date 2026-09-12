package org.chobit.epubra.app.ui;

import javafx.scene.layout.Region;

/**
 * JavaFX 节点操作的小工具。
 *
 * <p>{@code visible} 与 {@code managed} 必须同步设置：只设 visible=false 的节点仍参与
 * 布局计算，会留下一段空白间距。项目里多处（状态栏分区、活动栏、预览区切换）都要这个
 * 组合，集中一处避免各控制器各写一份。
 */
public final class FxNodes {

    private FxNodes() {
    }

    /** 同步设置 visible 与 managed；node 为 null 时静默返回。 */
    public static void setVisibleManaged(Region node, boolean visible) {
        if (node == null) {
            return;
        }
        node.setVisible(visible);
        node.setManaged(visible);
    }
}

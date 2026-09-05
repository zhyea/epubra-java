package com.epubra.app.controller;

import javafx.scene.Node;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;

/**
 * 侧边栏与活动栏交互控制器。
 *
 * <p>把 MainController 中"切换侧边栏 / 维护活动栏按钮选中态 / 显示底部面板"这一组纯 UI
 * 编排抽出来，{@link MainController} 只剩 1 行委托入口。
 *
 * <p>职责边界：
 * <ul>
 *   <li>本类只持有"按钮 ↔ 视图"映射的状态（{@link #activeSideView} / {@link #lastSideButton}），
 *       不知道也不关心当前书籍、章节或元数据。</li>
 *   <li>底部面板"打开后是否要立即跑校验"由 {@link MainController} 通过 {@code Runnable} 钩子
 *       注入，避免本类反向依赖业务层。</li>
 *   <li>FXML 字段（{@code activityGroup} / 4 个按钮 / 3 个侧栏 / {@code bottomPanel}）由
 *       {@link MainController} 持有并构造时传入——本类横跨主 FXML（活动栏）与多个 include
 *       子 FXML（侧边视图 / 底部面板），无法归属某个子 FXML 的 {@code fx:controller}。</li>
 * </ul>
 */
public class SidebarController {

    private final ToggleGroup activityGroup;
    private final ToggleButton tocButton;
    private final ToggleButton resourceButton;
    private final ToggleButton metadataButton;
    private final ToggleButton validationButton;
    private final Node tocView;
    private final Node resourceView;
    private final Node metadataView;
    private final Node bottomPanel;

    /** 当前显示的侧边栏视图。{@code null} 表示侧栏全收起（仅底部面板可见）。 */
    private Node activeSideView;
    /** 切换到「校验」之前选中的侧栏按钮，用于校验按钮取消时一键还原。 */
    private ToggleButton lastSideButton;

    /**
     * 4 个活动栏按钮的「按下时刻」selected 快照。
     *
     * <p>JavaFX 对 {@code ToggleButton} 在 {@code ToggleGroup} 内的处理与 {@code RadioButton}
     * 等价——点击已选中的按钮不会真的把 {@code selected} 翻成 {@code false}（{@code ToggleGroup}
     * 不会让已选中按钮失去选中），但 {@code onAction} 仍会触发。这意味着仅靠
     * {@code onAction} 里读 {@code button.isSelected()} 无法区分"首次点击"与"再点同一按钮"。
     *
     * <p>{@code onMousePressed} 在 JavaFX 的 toggle 逻辑执行之前触发，于此处读取的
     * {@code isSelected()} 就是按下那一瞬的原始状态；在 {@code onAction} 里读这两个字段
     * 即可稳定判断「再点同一按钮」。
     */
    private boolean tocPressedSelected;
    private boolean resourcePressedSelected;
    private boolean metadataPressedSelected;
    private boolean validationPressedSelected;

    public SidebarController(ToggleGroup activityGroup,
                             ToggleButton tocButton, ToggleButton resourceButton,
                             ToggleButton metadataButton, ToggleButton validationButton,
                             Node tocView, Node resourceView, Node metadataView,
                             Node bottomPanel) {
        this.activityGroup = activityGroup;
        this.tocButton = tocButton;
        this.resourceButton = resourceButton;
        this.metadataButton = metadataButton;
        this.validationButton = validationButton;
        this.tocView = tocView;
        this.resourceView = resourceView;
        this.metadataView = metadataView;
        this.bottomPanel = bottomPanel;
    }

    /** 活动栏初始化：把「目录」切到前台。默认入口由 {@code MainController.setupActivityBar} 触发。 */
    public void setupDefault() {
        showSideView(tocView);
        // activityGroup 由 FXML 绑定 4 个按钮，无需手动管理
    }

    /**
     * 给 4 个活动栏按钮挂上 {@code onMousePressed}，按下时把 {@code isSelected()} 快照存好，
     * 给「再点同一按钮收起侧栏」用。{@link MainController#initialize()} 阶段调用一次即可。
     *
     * <p>必须在 4 个 {@link ToggleButton} 注入完成后调用——{@code MainController}
     * 把构造器参数传过来时按钮已经就绪。
     */
    public void setupActivityBarInteraction() {
        if (tocButton != null) {
            tocButton.setOnMousePressed(e -> tocPressedSelected = tocButton.isSelected());
        }
        if (resourceButton != null) {
            resourceButton.setOnMousePressed(e -> resourcePressedSelected = resourceButton.isSelected());
        }
        if (metadataButton != null) {
            metadataButton.setOnMousePressed(e -> metadataPressedSelected = metadataButton.isSelected());
        }
        if (validationButton != null) {
            validationButton.setOnMousePressed(
                    e -> validationPressedSelected = validationButton.isSelected());
        }
    }

    /**
     * 用户点击该按钮时它处于 selected 状态——视作「再点同一按钮」语义。
     * 校验按钮不在侧栏收起语义内，总是返回 {@code false}。
     */
    public boolean isCollapsingClick(ToggleButton button) {
        if (button == tocButton) {
            return tocPressedSelected;
        }
        if (button == resourceButton) {
            return resourcePressedSelected;
        }
        if (button == metadataButton) {
            return metadataPressedSelected;
        }
        return false;
    }

    public void showTocView() {
        showSideView(tocView);
    }

    public void showResourceView() {
        showSideView(resourceView);
    }

    public void showMetadataView() {
        showSideView(metadataView);
    }

    /**
     * 底部面板打开钩子。{@code onShown} 由调用方在面板真正呈现后触发，通常是跑一次校验。
     * 切换状态、可见性都在本方法内完成，调用方只关心"打开后做什么"。
     */
    public void showProblems(Runnable onShown) {
        showBottomPanel(true);
        if (validationButton != null) {
            validationButton.setSelected(true);
        }
        if (onShown != null) {
            onShown.run();
        }
    }

    /** 收起底部面板并恢复上一个侧栏按钮选中态。 */
    public void hideProblems() {
        showBottomPanel(false);
        restoreSideActivity();
    }

    /**
     * 仅切换底部面板可见性，不触动活动栏选中态。
     * 用于 {@code runValidation} 这类"程序化展开面板"路径，区别于用户主动切换。
     */
    public void showBottomPanelOnly(boolean visible) {
        showBottomPanel(visible);
    }

    /**
     * 切换侧边栏视图：3 个视图叠放在同一个布局里，只让目标视图参与布局。
     *
     * @param view {@link #tocView} / {@link #resourceView} / {@link #metadataView} 之一
     */
    private void showSideView(Node view) {
        activeSideView = view;
        lastSideButton = view == resourceView ? resourceButton
                : view == metadataView ? metadataButton
                : tocButton;
        setVisibleManaged(tocView, view == tocView);
        setVisibleManaged(resourceView, view == resourceView);
        setVisibleManaged(metadataView, view == metadataView);
    }

    /**
     * 收起所有侧边栏视图：与 {@link #hideProblems()} 不同，本方法只触动侧边栏，不影响底部面板
     * 与活动栏按钮选中态（按钮的取消由调用方负责）。
     *
     * <p>典型场景：用户点击已选中的侧栏按钮，希望像 VSCode 那样把整个侧栏折起来。
     * 调用方在调完本方法后，把对应按钮 {@code setSelected(false)} 即可让活动栏也跟着无选中。
     */
    public void hideAllSideViews() {
        activeSideView = null;
        setVisibleManaged(tocView, false);
        setVisibleManaged(resourceView, false);
        setVisibleManaged(metadataView, false);
        // 注意：保留 lastSideButton，供后续 hideProblems → restoreSideActivity 还原侧栏按钮
    }

    /** 任一侧栏视图是否处于可见状态——供「再点同一按钮收起」语义判断。 */
    public boolean isSidebarVisible() {
        return activeSideView != null;
    }

    /**
     * 活动栏从「校验」回到上一个侧边视图；按钮 setSelected 不会触发 onAction，无需防递归。
     *
     * <p>只有侧栏<b>当前仍在显示</b>对应视图（{@code activeSideView != null}）时，才把
     * 上次记录的侧栏按钮设回 {@code selected}。若用户已经手动把侧栏折叠
     * （{@code hideAllSideViews()} 把 {@code activeSideView} 置 {@code null} 但
     * 保留 {@code lastSideButton}），则不要重新激活按钮，否则会出现「按钮高亮但侧栏空」
     * 的视觉割裂。
     */
    private void restoreSideActivity() {
        if (validationButton != null) {
            validationButton.setSelected(false);
        }
        if (lastSideButton != null && activeSideView != null) {
            lastSideButton.setSelected(true);
        }
    }

    private void showBottomPanel(boolean visible) {
        setVisibleManaged(bottomPanel, visible);
    }

    private static void setVisibleManaged(Node node, boolean visible) {
        if (node == null) {
            return;
        }
        node.setVisible(visible);
        node.setManaged(visible);
    }

    /** 当前活动的侧栏视图（用于其他子控制器在面板显示时联动判断）。 */
    public Node activeSideView() {
        return activeSideView;
    }

    public boolean isOnTocView() {
        return activeSideView == tocView;
    }

    public boolean isOnResourceView() {
        return activeSideView == resourceView;
    }

    public boolean isOnMetadataView() {
        return activeSideView == metadataView;
    }
}

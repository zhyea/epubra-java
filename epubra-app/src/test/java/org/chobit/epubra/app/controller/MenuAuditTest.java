package org.chobit.epubra.app.controller;

import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.RadioMenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.ToggleGroup;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.stage.Stage;
import org.chobit.epubra.app.activities.ThemeActivity;
import org.chobit.epubra.app.context.AppEventBus;
import org.chobit.epubra.app.context.BookContext;
import org.chobit.epubra.app.controller.view.PreviewController;
import org.chobit.epubra.app.controller.view.TocController;
import org.chobit.epubra.app.editor.Theme;
import org.chobit.epubra.app.platform.PreferenceNodes;
import org.chobit.epubra.lib.domain.BookFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 顶部菜单的审计契约：<b>结构完整 / 条目都接上线 / 快捷键随菜单显隐 / 点下去真的有反应</b>。
 *
 * <p><b>为什么需要这个类</b>：既有的 {@code HomeMenuVisibilityTest}（菜单本身两态显隐）与
 * {@code HomeViewMenuTest}（「视图」菜单可见条目）覆盖的都只是「菜单长什么样」。而
 * <b>「点下去有没有反应」这一层此前完全没有守卫</b>——七个菜单的 {@code fx:id} 在任何测试里
 * 都没出现过。后果是：某个条目的 {@code onAction} 被漏掉、或快捷键被别的控件吃掉、
 * 或菜单收起了却没撤掉快捷键，都不会打红任何既有用例。
 *
 * <p><b>本类锁住四件事</b>：
 * <ol>
 *   <li><b>完整清单</b>——七个菜单下每一个条目的文字与加速键逐字固化。误删一个条目、
 *       误改一个加速键都会精确变红（「功能零丢失」的可执行凭证）；</li>
 *   <li><b>接线</b>——除「最近工作空间」的空占位项外，每个条目都必须有 {@code onAction}；
 *       且全菜单内加速键不得重复（重复会让其中一个静默失效）；</li>
 *   <li><b>加速键生命周期</b>——隐藏菜单的加速键**不能**留在 Scene 上（否则首页按 Ctrl+Z
 *       会打到没有书的编辑器），菜单变可见时注册、再变回隐藏时必须撤销；</li>
 *   <li><b>点击效果</b>——对不弹窗的命令（视图 / 编辑 / 工具 / 文件空书降级 / 章节命令无选中）
 *       真的 {@code fire()} 一次，断言状态栏或控件状态确实变了。</li>
 * </ol>
 *
 * <p><b>为什么不测全部 27 项</b>：剩下四类条目仍落在「模态对话框」上——
 * {@code FileChooser}（打开 / 导入资源 / 插入图片）、{@code TextInputDialog}（重命名章节）、
 * {@code Dialog}（章节拆分…）以及 {@code Alert}（关于；还有需要用户返回值的「丢弃未保存修改」确认）。
 * 在无人值守的测试里触发它们会把 FX 线程挂在嵌套事件循环上，所以只断言其接线（第 2 条），
 * 不断言点击。这是**刻意的边界**。
 *
 * <p>其中「章节命令无选中」这一类**原本也在边界之内**——那时 {@code MainController.warn()}
 * 走的是 {@code Alert#showAndWait()}。2026-09-15 把 {@code warn()} 统一成状态栏提示后
 * （与 {@code TocController} 既有的「已经是第一个」等提示同口径），这 6 条路径变得可断言，
 * 本类已把它们收进来，见 {@link #chapterCommandsWithoutSelectionReportToStatusBar}。
 * 这是**边界的收缩**，不是新增的重复覆盖。
 *
 * <p>跨 class 共享 JavaFX toolkit，见 {@code HomeMenuVisibilityTest} 的说明。
 */
class MenuAuditTest {

    private static MainController mainController;
    private static Stage stage;
    private static final CountDownLatch FX_STARTED = new CountDownLatch(1);

    @BeforeAll
    static void bootFxAndLoadMainWindow() throws Exception {
        // toolkit 全 JVM 只 init 一次；后跑的 class 调 Platform.startup 会 IllegalStateException，
        // 吞掉即可（latch 仍要 countDown 供后续 await）。
        try {
            Platform.startup(FX_STARTED::countDown);
        } catch (IllegalStateException alreadyInitialized) {
            FX_STARTED.countDown();
        }
        assertTrue(FX_STARTED.await(10, TimeUnit.SECONDS), "JavaFX toolkit 启动超时");
        Platform.setImplicitExit(false);

        // 隔离 Preferences 再加载 FXML：本类断言「最近工作空间为空 → 一条禁用占位项」，而
        // 「最近工作空间」菜单是在 initialize 阶段按 WorkspaceStore（Preferences 后端）构建的。
        // 若读的是开发者本机真实偏好，跑过一次 App 就会留下 recentWorkspaces（实测本机就有一条
        // D:\Users\robin\Desktop\workspace），这条断言会随环境变红——测试不该依赖「机器上是否
        // 开过工作空间」。这是「测试写盘必须隔离」的镜像问题：**读盘同样要隔离**。
        PreferenceNodes.useInMemoryForTesting();

        onFx(() -> {
            FXMLLoader loader = new FXMLLoader(
                    MenuAuditTest.class.getResource("/org/chobit/epubra/app/view/main-window.fxml"));
            Parent root = loader.load();
            mainController = loader.getController();
            stage = new Stage();
            stage.setScene(new Scene(root, 1280, 800));
            stage.show();
        });
    }

    @AfterAll
    static void hideStage() {
        // 不 Platform.exit()：多 class 共享 Platform，setImplicitExit(false) 下随 JVM 结束即可。
        Platform.runLater(() -> {
            if (stage != null) {
                stage.hide();
            }
        });
        // 还原 Preferences 覆盖，别把「内存存储」这个状态留给同 JVM 的其它测试类。
        PreferenceNodes.resetForTesting();
    }

    // ------------------------------------------------------------------ 1. 清单与接线

    @Test
    @Timeout(60)
    @DisplayName("七个菜单的条目与加速键逐字固化；除空占位外每个条目都有 onAction；加速键不重复")
    void menuInventoryIsCompleteAndEveryItemIsWired() throws Exception {
        // 清单已与「并排预览」的开关状态无关：该条目是 CheckMenuItem，文字**固定**，
        // 当前是否开启由勾选态表达（此前是翻转文字，那时必须先归一化到关闭态才能比对）。
        List<String> actual = onFxGet(MenuAuditTest::inventory);
        assertEquals(EXPECTED_INVENTORY, actual,
                "菜单条目或加速键与契约不符——删条目 / 改加速键都会在这里显形");

        // 每个真命令都必须接上线。两类条目天然没有 onAction，需排除：
        //   · 分隔线（不是命令）
        //   · 子菜单本身（「打开最近工作空间」是容器，动作在它的子项上）
        // 唯一允许为空的真条目是「暂无最近工作空间」——空列表时的禁用占位项。
        List<String> unwired = onFxGet(() -> collectItems().stream()
                .filter(i -> !(i instanceof SeparatorMenuItem))
                .filter(i -> !(i instanceof Menu))
                .filter(i -> i.getOnAction() == null)
                .map(MenuItem::getText)
                .toList());
        assertEquals(List.of("暂无最近工作空间"), unwired,
                "除空占位项外不允许出现没有 onAction 的死条目");

        // 加速键唯一：重复会让其中一个静默失效，且没有任何别的机制会报错
        List<String> duplicates = onFxGet(MenuAuditTest::duplicateAccelerators);
        assertEquals(List.of(), duplicates, "存在重复的加速键");
    }

    @Test
    @Timeout(60)
    @DisplayName("「打开最近工作空间」空态是禁用占位项，不会被误当成可点的空菜单")
    void recentWorkspaceMenuHasDisabledPlaceholderWhenEmpty() throws Exception {
        List<MenuItem> items = onFxGet(() -> recentWorkspaceMenu().getItems());
        assertEquals(1, items.size(), "没有最近工作空间时应恰有一条占位项");
        assertTrue(items.get(0).isDisable(), "占位项必须是禁用的，否则用户点了没反应");
        assertNull(items.get(0).getOnAction(), "占位项不应带动作");
    }

    // ------------------------------------------------------------------ 2. 加速键生命周期

    @Test
    @Timeout(60)
    @DisplayName("隐藏菜单的加速键不留在 Scene 上；菜单变可见时注册、再变回隐藏时撤销")
    void acceleratorsFollowMenuVisibilityWithoutLeaking() throws Exception {
        // 首页（书架）：只有可见的「文件」菜单注册了自己的加速键
        setEditorChrome(false);
        assertEquals(HOME_ACCELERATORS, sceneAccelerators(),
                "首页只应有「文件」菜单的加速键——编辑 / 章节菜单是收起的，"
                        + "它们的 Ctrl+Z、Ctrl+F、F2 不该生效");

        // 打开图书：编辑 / 章节菜单变可见，禁用的加速键随之注册
        openBook();
        Set<String> bookOpen = sceneAccelerators();
        assertTrue(bookOpen.containsAll(HOME_ACCELERATORS), "「文件」菜单的加速键不应丢");
        assertTrue(bookOpen.containsAll(EDITOR_ACCELERATORS),
                "有书时「编辑 / 章节」菜单的加速键应注册，实际：" + bookOpen);

        // 切回书架：必须撤销注册，否则 Ctrl+Z / Ctrl+F 会打到没有书的编辑器上
        setEditorChrome(false);
        assertEquals(HOME_ACCELERATORS, sceneAccelerators(),
                "切回书架后隐藏菜单的加速键必须被撤销，否则是只在首页复现的幽灵快捷键");
    }

    // ------------------------------------------------------------------ 3. 点击效果（不弹窗的那些）

    @Test
    @Timeout(60)
    @DisplayName("「视图」五项点击都有效果：刷新预览 / 并排预览开关 / 三个主题项")
    void viewMenuCommandsTakeEffect() throws Exception {
        openBook();

        onFx(() -> item("刷新预览").fire());
        assertEquals("预览已刷新", statusText(), "「刷新预览」应给出状态反馈");

        // 「并排预览」按 fx:id 定位（CheckMenuItem）。契约是：**文字固定**，当前是否开启由**勾选态**表达。
        CheckMenuItem split = field("splitPreviewItem");
        ensureSplitOff();
        assertEquals("并排预览", onFxGet(split::getText), "文字必须固定，不随模式变化");

        onFx(split::fire);
        assertTrue(splitEnabled(), "「并排预览」应切到并排模式");
        assertEquals("已切换为并排预览", statusText());
        assertTrue(onFxGet(split::isSelected), "并排模式下菜单项应处于勾选态");
        assertEquals("并排预览", onFxGet(split::getText), "文字不随模式翻转");

        onFx(split::fire);
        assertFalse(splitEnabled(), "再点一次应切回标签模式");
        assertEquals("已切换为标签预览", statusText());
        assertFalse(onFxGet(split::isSelected), "切回标签模式后应取消勾选");

        // 关键一条：**绕过菜单命令**，直接调 PreviewController.toggleSplit()，断言勾选态跟着走。
        //
        // 为什么值得单独验：实测 `CheckMenuItem.fire()` **自己不会翻选中态**（和 RadioMenuItem
        // 同源——`MenuItem.fire()` 只发 ActionEvent，选中态由菜单的点击行为/skin 完成）。
        // 也就是说勾选态**完全依赖** applyMode() 里那句 setSelected()。上面两条走 fire() 的断言
        // 其实已经能验到它；这一条把「经由菜单」这条路径摘掉，改用状态变化本身触发——
        // 守住的是「同步挂在 applyMode()（状态变更的必经之路）上」，而不是被挪进
        // onToggleSplitPreview()（只有菜单命令才会走）。两者语义不同，将来重构很容易挪错。
        onFx(() -> previewController().toggleSplit());
        assertTrue(onFxGet(split::isSelected),
                "开启并排后菜单项必须被勾上——这条守的是 applyMode() 里那句 setSelected()");
        onFx(() -> previewController().toggleSplit());
        assertFalse(onFxGet(split::isSelected),
                "关闭并排后菜单项必须取消勾选（同上，绕开菜单命令才验得到）");

        // 主题：三选一必须共用**同一个** ToggleGroup——FXML 里漏了 toggleGroup 会让两个主题
        // 同时高亮，而主题值本身仍然是对的，是个很难靠肉眼发现的接线缺陷。
        ToggleGroup themeGroup = onFxGet(() -> radioItem(Theme.LIGHT).getToggleGroup());
        assertNotNull(themeGroup, "主题三项必须属于一个 ToggleGroup");
        assertSame(themeGroup, onFxGet(() -> radioItem(Theme.LIGHT).getToggleGroup()));
        assertSame(themeGroup, onFxGet(() -> radioItem(Theme.DARK).getToggleGroup()));
        assertSame(themeGroup, onFxGet(() -> radioItem(Theme.SEPIA).getToggleGroup()));
        assertEquals(3, onFxGet(() -> themeGroup.getToggles().size()), "组成员应恰为三项");

        // 切到「非当前」的那一个，断言三处联动（当前值 / 状态栏标签 / 状态栏提示）后还原，
        // 避免把用户的主题偏好留在测试改过的状态上。
        //
        // 注：RadioMenuItem.fire() **不负责选中态**——选中由菜单的点击行为完成，程序化 fire
        // 不经过那条路径（实测切换生效但 isSelected() 仍为 false）。所以测试里按点击后的
        // 语义显式补一次 setSelected，顺便验证该成员确实属于这个组。
        Theme original = currentTheme();
        Theme target = original == Theme.DARK ? Theme.LIGHT : Theme.DARK;
        try {
            RadioMenuItem targetItem = radioItem(target);
            onFx(() -> targetItem.setSelected(true));
            assertSame(targetItem, onFxGet(themeGroup::getSelectedToggle),
                    "主题项应成为该组的当前选中项（守卫 FXML 的 toggleGroup 接线）");

            onFx(targetItem::fire);
            assertEquals(target, currentTheme(), "主题应已切换");
            assertEquals(target.displayName(), themeStatusLabelText(), "状态栏主题标签应联动");
            assertEquals("已切换到" + target.displayName() + "主题", statusText());
        } finally {
            RadioMenuItem originalItem = radioItem(original);
            onFx(() -> originalItem.setSelected(true));
            onFx(originalItem::fire);
            assertEquals(original, currentTheme(), "主题应已还原");
        }
    }

    @Test
    @Timeout(60)
    @DisplayName("「编辑」与「章节」菜单项点击都有效果：查找条唤出、添加章节/撤销/重做的往返")
    void editMenuCommandsTakeEffect() throws Exception {
        openBook();

        onFx(() -> item("查找替换…").fire());
        assertTrue(findBarVisible(), "「查找替换…」应唤出查找条");

        // 撤销 / 重做改用**真实动作自建历史**（章节 → 添加章节），不依赖别的用例留下的栈：
        // 之前的写法假设「历史是空的」，实际会被同类的其它用例污染，得到「已撤销」而不是
        // 「没有可撤销的操作」——那是测试的错，不是产品的错（干净流程下实测完全正常）。
        int before = onFxGet(() -> bookContext().book().spine().size());
        onFx(() -> item("添加章节").fire());
        assertEquals(before + 1, onFxGet(() -> bookContext().book().spine().size()),
                "「章节 → 添加章节」应真的加了一章");
        assertTrue(statusText().startsWith("已添加章节："), "应给出添加反馈，实际：" + statusText());

        onFx(() -> item("撤销").fire());
        assertEquals("已撤销", statusText(), "「编辑 → 撤销」必须给出反馈并回退");
        assertEquals(before, onFxGet(() -> bookContext().book().spine().size()),
                "撤销后章节数应回退");

        onFx(() -> item("重做").fire());
        assertEquals("已重做", statusText(), "「编辑 → 重做」必须给出反馈并恢复");
        assertEquals(before + 1, onFxGet(() -> bookContext().book().spine().size()),
                "重做后章节数应恢复");
    }

    @Test
    @Timeout(60)
    @DisplayName("章节命令「无选中」改走状态栏提示，不再弹模态框（S1 落地后这批路径变得可断言）")
    void chapterCommandsWithoutSelectionReportToStatusBar() throws Exception {
        openBook();
        // 「无选中」这个前置状态必须**显式构造**：同类其它用例可能刚加过章节并把它选中了。
        onFx(() -> tocViewController().clearSelection());

        onFx(() -> item("删除章节").fire());
        assertEquals("请先在目录中选择要删除的章节", statusText(),
                "无选中点「删除章节」应给状态栏提示——旧版走 Alert#showAndWait()，"
                        + "在无人值守测试里会挂死 FX 线程，所以这条路径此前完全没有守卫");

        // 「上移」走的是 moveChapter 里同一条守卫，一并纳入（提示文案不同，正好各验一条）
        onFx(() -> item("上移").fire());
        assertEquals("请先在目录中选择要移动的章节", statusText());
    }

    @Test
    @Timeout(60)
    @DisplayName("Esc 关闭查找条：面板上的 KEY_PRESSED filter 真的接上了（F1 类静默失效的直接守卫）")
    void findBarEscapeClosesIt() throws Exception {
        openBook();
        onFx(() -> item("查找替换…").fire());
        assertTrue(findBarVisible(), "前置条件：查找条应已唤出");

        // 直接把 Esc 派发到 findBar 这棵子树：Event#fireEvent 走的是该节点的完整派发链，
        // **捕获阶段注册的 filter 一定会被执行**——正是 bind() 里那条「Esc 关闭」走的路径。
        //
        // 为什么不用 Robot：项目里两个 Robot 用例（VisualEditorShortcutSinglePathTest /
        // VisualEditorTabFocusEscapeTest）依赖前台窗口焦点，无人值守时会随机假红。这里验的是
        // **节点上的 eventFilter**，不是 Scene 的 InputMap 快捷键，所以合成事件足够——
        // 「合成 MouseEvent 进不了 InputMap」那条教训不适用于本用例。
        onFx(() -> {
            HBox bar = field("findBar");
            bar.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ESCAPE,
                    false, false, false, false));
        });
        assertFalse(findBarVisible(),
                "Esc 应关闭查找条——findBar 上的 KEY_PRESSED filter 若没挂上（F1 那类漏接线），"
                        + "这里会红：面板开着却关不掉");
    }

    @Test
    @Timeout(60)
    @DisplayName("「工具」与「文件」在无副作用路径上不弹窗：清理无未引用资源、空书保存均只给状态提示")
    void toolsAndFileCommandsDegradeGracefully() throws Exception {
        // 显式退回「没有打开的图书」：本类的其它用例会建书，不能依赖执行顺序
        onFx(() -> bookContext().setBook(null));
        setEditorChrome(false);
        onFx(() -> item("保存").fire());
        assertEquals("当前没有打开的图书，无需保存", statusText(), "空书点「保存」应给明确提示");
        onFx(() -> item("另存为…").fire());
        assertEquals("当前没有打开的图书，无需保存", statusText(), "空书点「另存为」应给明确提示");

        // 有书但无未引用资源：清理命令应给状态提示，不弹确认框（弹了在测试里会挂死 FX 线程）
        openBook();
        onFx(() -> item("清理未引用资源").fire());
        assertEquals("没有未被引用的资源", statusText());
    }

    // ------------------------------------------------------------------ 夹具

    /**
     * 七个菜单的完整清单，形如 {@code 菜单|条目|加速键}；加速键为 null 时写 {@code -}。
     *
     * <p>加速键用 {@link KeyCombination#getName()} 的形式（修饰键按 JavaFX 规范序排列，
     * 如 {@code Shift+Ctrl+S}），而非界面上显示的 {@code Ctrl+Shift+S}——前者与语言环境无关。
     *
     * <p>「视图|并排预览」记的是它的**固定文字**。该条目是 {@code CheckMenuItem}，
     * 开关状态由勾选态而非文字表达，所以清单与当前是否开启无关（此前是翻转文字，
     * 比对前必须先归一化到关闭态）。
     */
    private static final List<String> EXPECTED_INVENTORY = List.of(
            "文件|新建|Ctrl+N",
            "文件|打开…|Ctrl+O",
            "文件|打开工作空间…|-",
            "文件|打开最近工作空间|-",
            "文件|保存|Ctrl+S",
            "文件|另存为…|Shift+Ctrl+S",
            "文件|退出|-",
            "编辑|撤销|Ctrl+Z",
            "编辑|重做|Shift+Ctrl+Z",
            "编辑|查找替换…|Ctrl+F",
            "章节|添加章节|-",
            "章节|重命名章节…|F2",
            "章节|章节拆分…|-",
            "章节|删除章节|-",
            "章节|上移|-",
            "章节|下移|-",
            "章节|降一级|-",
            "章节|升一级|-",
            "插入|导入资源…|-",
            "插入|插入图片|-",
            "视图|刷新预览|-",
            "视图|并排预览|-",
            "视图|浅色|-",
            "视图|深色|-",
            "视图|护眼米黄|-",
            "工具|运行校验|-",
            "工具|清理未引用资源|-",
            "帮助|关于|-");

    /** 首页（书架）应注册在 Scene 上的加速键：只有「文件」菜单的可见条目。 */
    private static final Set<String> HOME_ACCELERATORS =
            Set.of("F10", "Ctrl+N", "Ctrl+O", "Ctrl+S", "Shift+Ctrl+S");

    /** 打开图书后额外注册的加速键：来自「编辑」与「章节」两个菜单。 */
    private static final Set<String> EDITOR_ACCELERATORS =
            Set.of("Ctrl+Z", "Shift+Ctrl+Z", "Ctrl+F", "F2");

    private static List<String> inventory() {
        List<String> out = new ArrayList<>();
        for (Menu menu : menuBar().getMenus()) {
            for (MenuItem item : menu.getItems()) {
                if (item instanceof SeparatorMenuItem) {
                    continue;
                }
                out.add(menu.getText() + "|" + item.getText() + "|" + acceleratorOf(item));
            }
        }
        return out;
    }

    private static String acceleratorOf(MenuItem item) {
        KeyCombination acc = item.getAccelerator();
        return acc == null ? "-" : acc.getName();
    }

    /** 展开所有菜单（含子菜单）后的全部条目。 */
    private static List<MenuItem> collectItems() {
        List<MenuItem> out = new ArrayList<>();
        for (Menu menu : menuBar().getMenus()) {
            out.addAll(menu.getItems());
            for (MenuItem item : menu.getItems()) {
                if (item instanceof Menu sub) {
                    out.addAll(sub.getItems());
                }
            }
        }
        return out;
    }

    private static List<String> duplicateAccelerators() {
        Set<String> seen = new LinkedHashSet<>();
        List<String> duplicates = new ArrayList<>();
        for (MenuItem item : collectItems()) {
            String acc = acceleratorOf(item);
            if ("-".equals(acc)) {
                continue;
            }
            if (!seen.add(acc)) {
                duplicates.add(acc + " -> " + item.getText());
            }
        }
        return duplicates;
    }

    /**
     * 读 Scene 上已登记的加速键。
     *
     * <p><b>必须先强制一次 CSS + layout</b>：菜单加速键的登记挂在 MenuBar 的布局通道上，
     * {@code Menu} 的 {@code visible} 一变会先把集合清空，**下一次布局脉冲**才按当前可见
     * 菜单重建。不强制布局就读，会读到「清空后、重建前」的中间态——探针实测在
     * 切回书架后读到只剩 {@code F10}，强制布局后立刻恢复成正确的 5 个（2026-09-15）。
     * 这是 JavaFX 内部时序，不是产品缺陷；测试按「布局稳定后」的语义取值。
     */
    private static Set<String> sceneAccelerators() throws Exception {
        return onFxGet(() -> {
            Parent root = stage.getScene().getRoot();
            root.applyCss();
            root.layout();
            Set<String> out = new LinkedHashSet<>();
            stage.getScene().getAccelerators().keySet().forEach(k -> out.add(k.getName()));
            return out;
        });
    }

    private static MenuBar menuBar() {
        assertNotNull(stage.getScene(), "主窗口未挂 Scene");
        Parent root = stage.getScene().getRoot();
        assertTrue(root instanceof BorderPane, "main-window.fxml 根应为 BorderPane，实际：" + root);
        Object top = ((BorderPane) root).getTop();
        assertNotNull(top, "MenuBar 应挂在 BorderPane 的 top 上");
        return (MenuBar) top;
    }

    private static Menu recentWorkspaceMenu() {
        return (Menu) item("打开最近工作空间");
    }

    /** 按文字在整棵菜单树里找条目。 */
    private static MenuItem item(String text) {
        return collectItems().stream()
                .filter(i -> text.equals(i.getText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("菜单里找不到条目：" + text));
    }

    /** 主题菜单项按 fx:id 定位——比按显示名找更稳（也顺带守住三个 fx:id 的存在）。 */
    private static RadioMenuItem radioItem(Theme theme) throws Exception {
        return switch (theme) {
            case LIGHT -> field("themeLightItem");
            case DARK -> field("themeDarkItem");
            case SEPIA -> field("themeSepiaItem");
        };
    }

    private static ThemeActivity themeActivity() throws Exception {
        return field("themeActivity");
    }

    private static PreviewController previewController() throws Exception {
        return field("previewController");
    }

    private static TocController tocViewController() throws Exception {
        return field("tocViewController");
    }

    /** 读当前主题（在 FX 线程上取值）。 */
    private static Theme currentTheme() throws Exception {
        return onFxGet(() -> themeActivity().current());
    }

    /** 读「并排预览」开关（在 FX 线程上取值）。 */
    private static boolean splitEnabled() throws Exception {
        return onFxGet(() -> previewController().splitEnabled());
    }

    private static String statusText() throws Exception {
        return onFxGet(() -> MenuAuditTest.<javafx.scene.control.Label>field("statusLabel").getText());
    }

    private static String themeStatusLabelText() throws Exception {
        return onFxGet(() -> MenuAuditTest.<javafx.scene.control.Label>field("themeStatusLabel").getText());
    }

    private static boolean findBarVisible() throws Exception {
        return onFxGet(() -> MenuAuditTest.<HBox>field("findBar").isVisible());
    }

    @SuppressWarnings("unchecked")
    private static <T> T field(String name) throws Exception {
        var f = MainController.class.getDeclaredField(name);
        f.setAccessible(true);
        return (T) f.get(mainController);
    }

    private static BookContext bookContext() throws Exception {
        return field("ctx");
    }

    /** 把「并排预览」归一到关闭态，让依赖该条目文字的断言不依赖测试执行顺序。 */
    private static void ensureSplitOff() throws Exception {
        if (splitEnabled()) {
            // 用 fire()（走真实命令入口）而不是直接调 toggleSplit()：这里只是归一化夹具状态，
            // 顺带还能证明「菜单命令确实能关掉并排」。状态与勾选态的同源由上面专门的一条断言守。
            CheckMenuItem split = field("splitPreviewItem");
            onFx(split::fire);
        }
        assertFalse(splitEnabled(), "无法把「并排预览」归一到关闭态");
    }

    /** 模拟「从书架打开一本书」。重复调用是幂等的，测试之间无顺序依赖。 */
    private static void openBook() throws Exception {
        onFx(() -> {
            BookContext ctx = bookContext();
            if (ctx.book() == null) {
                ctx.setBook(BookFactory.createEmpty("菜单审计"));
            }
            ctx.bus().publish(new AppEventBus.BookLoadedEvent());
        });
    }

    /**
     * 切换编辑器外壳（活动栏 / 状态栏 / 四个编辑类菜单）的显隐，得到确定的首页态。
     *
     * <p>走反射调私有方法：真实入口是 {@code WorkspaceActivity.switchTo}，那需要真目录 +
     * 丢弃确认，在测试里代价过高；这里只要「菜单可见性」这一个副作用。
     */
    private static void setEditorChrome(boolean visible) throws Exception {
        var m = MainController.class.getDeclaredMethod("setEditorChromeVisible", boolean.class);
        m.setAccessible(true);
        onFx(() -> m.invoke(mainController, visible));
    }

    private static void onFx(FxTask r) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> err = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                r.runWithException();
            } catch (Throwable t) {
                err.set(t);
            } finally {
                done.countDown();
            }
        });
        assertTrue(done.await(15, TimeUnit.SECONDS), "FX 任务超时");
        if (err.get() != null) {
            throw new RuntimeException("FX task failed: " + err.get().getMessage(), err.get());
        }
    }

    private static <T> T onFxGet(Callable<T> c) throws Exception {
        AtomicReference<T> out = new AtomicReference<>();
        onFx(() -> out.set(c.call()));
        return out.get();
    }

    @FunctionalInterface
    private interface FxTask {
        void runWithException() throws Exception;
    }
}

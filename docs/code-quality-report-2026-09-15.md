# Epubra 全局代码质量检查报告

**日期**：2026-09-15
**范围**：`epubra-lib` + `epubra-app` 全量主源码与测试源码
**方式**：编译期告警（`-Xlint:all`）+ 自建静态审计（按行锚定正则；空 body 用花括号配对；CSS 反向核对）
**门禁基线**：`mvn clean test` → lib 75 + app 444 = **519 passed / 0 skipped / BUILD SUCCESS**
**性质**：全程**只读**，未改动任何生产代码。

---

## 0. 规模基线

| 项 | 数量 |
|---|---|
| 主源码文件 / 行数 | 94 / 17,554 |
| 测试源码文件 / 行数 | 67 / 13,386 |
| 最大文件 | `MainController.java` 1,523 行 |
| 次大 | `TocController` 693 / `DocumentActivity` 629 / `ResourceController` 570 / `EditorStyleControls` 522 |

---

## 1. 编译期告警（8 条，同一根因）

全部为 JDK 25 对 `netscape.javascript.JSObject` 的「已过时，且标记为待删除」：

| 文件 | 行 |
|---|---|
| `app/editor/VisualEditorSession.java` | 99, 197, 220（各 2 处） |
| `app/editor/VisualEditorTestSupport.java`（测试） | 148, 149 |

**判定：不处理，也无法处理。** `JSObject` 是 JavaFX WebView 暴露 JS 对象的唯一入口，无替代 API。**不建议**加 `-Xlint:-removal` 压掉——它是未来 JDK 升级的真实预警。

---

## 2. 死代码（2 处，确认为真）

### 2.1 `WelcomePageController.onExitAction()` 及其整条接线

```
epubra-app/.../controller/view/WelcomePageController.java:49   private Runnable onExit;
                                                         :55   构造参数 Runnable onExit
                                                         :59   this.onExit = onExit;
                                                         :71   private void onExitAction()
epubra-app/.../controller/MainController.java          :483   this::onExit           ← 实参仍在传
```

`onExitAction()` 标了 `@FXML`，但**没有任何 FXML 引用它**（`welcome-page.fxml` 当前只有标题行 + 书架，没有按钮）。推断是欢迎页上的「退出」按钮被删后，留下 `onExitAction` → `onExit` 字段 → 构造参数这条**端到端死链**。

> 注：`MainController.onExit()`（:665）与此无关，是菜单「退出」的入口，仍在使用。

**动作**：删 `onExitAction()` + `onExit` 字段 + 构造参数，并去掉 `MainController:483` 的 `this::onExit` 实参。

### 2.2 `MetadataOps.nullSafe()`

```
epubra-app/.../editor/MetadataOps.java:82   private static String nullSafe(String s)
```

全工程零调用。**注意别误删两个同名活方法**：

- `epubra-lib/.../validation/StructureSupport.nullSafe`（静态导入，被 `OpfSpineRules` / `NavigationRules` 大量使用）
- `epubra-lib/.../validation/ContainerRules.nullSafe`（:201 定义，:105 自用）

**动作**：删除。

---

## 3. 异常吞没（空 `catch`）——**含一次自纠**

**总计 15 处** catch 的 body 为空或仅含注释（全工程 catch 共 80 个）：

| 分类 | 数量 | 判定 |
|---|---|---|
| body 内有说明注释（且形如 `catch (X ignored)`） | **13** | **符合工程约定**，可接受 |
| body 内**什么都没有** | **2** | 一致性 nit，建议补一行注释 |

13 处有注释的（示例）：`ThemeManager:81`「无注册表 / 无家目录等环境下写不进去就算了」、`WorkspaceStore:51`「非法路径字符串直接跳过」、`PreviewMirror:265/293`、`AutosaveConfig:119`、`VisualEditorSession:165`、`PreferencesMigrator:86`、`WelcomePageController:155`、`CoverImageInfo:233/243`、`WorkspaceStore:185`、以及 lib 的 `EpubWriter:61`、`Xmls:67`。

2 处**无注释**的：

- `app/context/BookContext.java:190` — `catch (IOException ignored) {}`（tmp 目录 `createDirectories` 尽力而为）
- `app/platform/AppPaths.java:256` — `catch (IOException ignored) {}`（尽力而为删除）

> ⚠ **本节曾误报为「0」。** 首版脚本用 `\{[ \t]*\}` 匹配空 body，而 `[ \t]*` **跨不过换行**——本工程空 catch 全是 `{\n}` 写法，于是 0 命中、静默漏报。改用「catch 头定位 + 花括号配对」后报出 15 处。详见文末「自纠记录」。

---

## 4. 样式表与 styleClass 脱节（真问题，两组）

欢迎页显然经历过一次「大改小」：旧样式没删，新 UI 又没配样式，两头都脱节。

### 4.1 app.css 定义了、但全工程零引用的规则：11 条

`welcome-title` `welcome-subtitle` `welcome-tagline` `welcome-section-title` `welcome-section-caption` `welcome-empty-hint` `welcome-note` `welcome-primary-button` `welcome-secondary-button` `welcome-recent-project` `welcome-recent-workspace`

对应 app.css **750–865 行**，含 `:hover` / `:pressed` 变体约 **30 个规则块**，是一整段可**整块删除**的遗留区。

### 4.2 FXML / Java 在用、但 CSS 里没有定义的 styleClass：7 个

| styleClass | 使用位置 | 现状 |
|---|---|---|
| `welcome-kicker` | `welcome-page.fxml:22` | 无样式（继承父级） |
| `welcome-workspace-title` | `welcome-page.fxml:23` | 无样式 |
| `welcome-hint` | `welcome-page.fxml:25,28` | 无样式 |
| `book-shelf-scroll` | `welcome-page.fxml:30` | 无样式 |
| `main-center` | `main-window.fxml:202` | 无样式 |
| `new-book-card` | `WelcomePageController.java:172,192` | 无样式（有效的是 `book-card`） |
| `toc-view` | `toc-view.fxml:14` | 无样式（同元素 `side-view` 生效） |

**`toolbar-action` 是例外，不算问题**：它也查不到 CSS，但它是**纯 Java 侧标记类**——`updateToolbarState` 靠它跳过动作按钮的激活态刷新，属设计预期。

### 4.3 判定

- **4.1**：确认可删。
- **4.2**：**需要决策**——补样式（若当初想区分层级）或从 FXML/Java 摘掉类名（若本依赖继承）。目前「类名挂着但什么都不做」是最容易误导后来者的中间态。

---

## 5. 超长方法（原 9 个 → 现 8 个）

### 5.1 已修复：`MainController.initialize()` 176 行 → **13 行**（P4，见 §9）

### 5.2 剩余（8 个，可读性候选，非缺陷）

| 文件 | 行 | 方法 | 行数 |
|---|---|---|---|
| `epubra-lib/.../OpfSpineRules.java` | 119 | `checkSpine()` | 90 |
| `epubra-lib/.../OpfSpineRules.java` | 35 | `checkOpfBasics()` | 82 |
| `epubra-lib/.../NavigationRules.java` | 101 | `checkNav()` | 79 |
| `epubra-lib/.../NavigationRules.java` | 205 | `checkNcx()` | 70 |
| `MainController.java` | 1167 | `onInsertLink()` | 63 |
| `epubra-lib/.../ContainerRules.java` | 100 | `checkRawOpf()` | 63 |
| `epubra-lib/.../EpubWriter.java` | 194 | `generateOpf()` | 62 |
| `epubra-app/.../TocController.java` | 440 | `attachDragHandlers()` | 61 |

- lib 侧那 5 个是「规则逐条 `if` + `findings.add`」的天然形态，强拆反而降低对照规范的直观性，**建议维持**。
- `onInsertLink()` 63 行、`attachDragHandlers()` 61 行仅超线 1–3 行，收益低，暂不动。

---

## 6. 已确认干净

| 检查项 | 结果 |
|---|---|
| 未使用的私有字段 | **0** |
| FXML 重复 `fx:id` / `id` | **0**（上一轮刚修掉双份 `colorPicker` 声明块） |
| `TODO` / `FIXME` / `HACK` | **0** |
| `printStackTrace` | **0** |
| 越界 `showAndWait()`（会挂死 FX 线程） | **0**，现存调用均带说明注释 |
| `disableProperty().bind()` 误用（层级菜单禁区） | **0** |

---

## 7. 结论与建议优先级

| 优先级 | 事项 | 动作 |
|---|---|---|
| **P1** | §4.1 删 11 条孤立 `welcome-*` 规则；§4.2 对 7 个 styleClass 二选一（补样式 / 摘类名） | 需用户决策后者 |
| **P2** | §2 死代码（`onExitAction` 链路 + `MetadataOps.nullSafe`） | 直接删，零风险 |
| **P3** | §3 给 2 处无注释空 catch 补一行注释，对齐工程自身约定 | 1 分钟 |
| **P4** | §5 `MainController.initialize()` 拆分 | 列入后续重构 |
| — | §1 JSObject 过时告警 | 不动 |

---

## 附：自纠记录

| # | 首版结论 | 实际 | 根因 |
|---|---|---|---|
| 1 | 空 catch = **0** | **15**（13 有注释 / 2 真静默） | `\{[ \t]*\}` 的 `[ \t]*` 跨不过换行，Java 空 catch 惯写 `{\n}` → 静默漏报。改用「catch 头 + 花括号配对」 |
| 2 | CSS 未使用 = **68** | **11** | 正着查把 JavaFX 内置皮肤类（40+）、CSS 注释里的词、后代选择器自引用全算成未使用。改为**反查**：CSS 类选择器 → 在除 `.css` 外的全源码树（含 JS）搜引用 |

**两条通用教训**：① 正则匹配「空 body」`[ \t]*` vs `\s*` 是经典静默漏报点；② **凡是报「0」或「异常多」的项，都换一种写法复验一次**——报 0 通常是脚本 bug，报多通常是误报。

---

## 8. 修复执行记录（2026-09-15 08:40–08:45，已落地）

用户决策：**P1 走「摘类名」路线**，并修复 P2、P3。

### 8.1 P1 — 摘类名（4 个 FXML + 1 个 Java）

| 文件 | 改动 |
|---|---|
| `view/welcome-page.fxml` | 摘掉 `welcome-kicker`、`welcome-workspace-title`、`welcome-hint`(×2)、`book-shelf-scroll` |
| `view/main-window.fxml:202` | `<StackPane styleClass="main-center">` → `<StackPane>` |
| `view/toc-view.fxml:14` | `styleClass="side-view toc-view"` → `styleClass="side-view"` |
| `controller/view/WelcomePageController.java` | `addAll("book-card","new-book-card")` → `add("book-card")`（2 处） |
| `test/.../WelcomePageHideTest.java` | 文档注释里指向已删类名的 `main-center` 改为「中央编辑区」 |

**顺带删掉 11 条孤立 CSS 规则**（app.css 原 750–865 行整段，含 `:hover`/`:pressed` 约 30 个规则块）。理由：「摘类名」选定的方向就是当前 UI 不再区分这些层级，对应样式已无任何引用方。

> 前置校验：改前先确认这 7 个类名在 **app.css / theme.css / editor-paper.css 三张表里均无定义**（若有定义，摘掉就会丢样式），并确认**测试无任何断言引用**（仅 1 处注释提及）。

### 8.2 P2 — 死代码

- `WelcomePageController`：删 `onExitAction()`、`onExit` 字段、`bind(...)` 的第 4 个参数 `Runnable onExit`。
- `MainController:479-483`：`welcomePageController.bind(...)` 去掉 `this::onExit` 实参。
- `MetadataOps`：删 `private static String nullSafe(String)`。
- 已复核 `MainController.onExit()` **仍被菜单 `main-window.fxml:64 onAction="#onExit"` 使用**，未造出新的死代码。

### 8.3 P3 — 补注释

- `context/BookContext.java:190`、`platform/AppPaths.java:256` 两处空 catch 补上说明注释。

### 8.4 门禁与复验

| 项 | 结果 |
|---|---|
| `mvn -B clean test` | **lib 75 + app 444 = 519**，Failures 0 / Errors 0 / **Skipped 0**，BUILD SUCCESS，**EXIT=0** |
| 冒烟 `javafx:run`（45s） | EXIT=124（跑满被杀 = 健康）；日志**零** FXML / 样式 / 应用层告警 |
| 复跑审计：未使用私有方法 | **0**（原 2） |
| 复跑审计：无注释的空 catch | **0**（原 2；13 处全部带说明注释） |
| 复跑审计：CSS 孤儿规则 | **0**（原 11；选择器总数 114 → 103） |
| 复跑审计：FXML 重复 id | **0** |

**过程中被门禁抓到一次测试编译错误**：`WorkspaceShelfEmptyStateTest:62` 用 4 参调 `bind(...)`。这暴露了本次排查的一个盲区——**改方法签名时不能只 grep「类名」，必须一并 grep「方法名」（`bind(`）**。已修正为 3 参。

### 8.5 未处理（保持现状）

- §1 JSObject 过时告警 8 条：有意保留。
- lib 侧 5 个长方法：建议维持。
- 改动**尚未提交**，按约定需用户显式批准。

---

## 9. P4 拆分执行记录（2026-09-15 08:47–08:55，已落地）

用户决策：**执行 P4，拆分 `MainController.initialize()`**。

### 9.1 做法：纯 Extract Method，零行为变更

`initialize()` **176 行 → 13 行**，按「初始化阶段」切成 8 个私有方法；方法仍留在 `MainController` 内，**守住「跨面板编排有意留主控制器」的约定**（不是把逻辑搬去别的类）。

```java
@FXML
public void initialize() {
    configureWebViewCaches();     // 1) 两个 WebView 的缓存目录（必须早于任何 load）
    buildEditorPipeline();        // 2) 外壳 / 预览 / 工具条 / 样式控件 / 可视化会话
    createStatusCoordinator();    // 3) status（必须先于任何 bind）
    createEditingActivities();    // 4) InsertActivity / WorkspaceActivity
    wireChildControllers();       // 5) Sidebar 构造 + 全部子控制器 bind（最长的一段）
    wireSourceTextTracking();     // 6) 源码区脏标记 + 撤销快照
    wireEditorTabSwitching();     //    （既有方法，直接调用）
    subscribeAppEvents();         //    （既有方法，直接调用）
    createLateActivities();       // 7) 主题 / 自动暂存 / 文件拖放 / 草稿恢复
    finishStartup();              // 8) 建文档活动 / 隐藏外壳 / 刷新「最近」菜单
}
```

**调用顺序 = 原语句顺序**，逐条对齐，因此三条硬约束全部保留：

1. WebView `setUserDataDirectory` 早于任何 `loadContent`；
2. `editorToolbarController` / `editorStyleControls` 早于 `visualEditorSession`（会话持二者的方法引用，求值时须非空）；
3. `status` 早于**任何** `bind`（子控制器拿 `status::set`，晚建会 NPE）。

这些约束原本是散在方法体里的行内注释，现已提升为各方法的 javadoc，**并显式标注了「为什么不能换顺序」**。

### 9.2 顺带清理

- 去掉局部变量 `WebEngine visualEngine`（原本跨两处使用，拆分后无法跨方法共享）→ 两处改为直接 `xxx.getEngine()`；`WebView.getEngine()` 幂等，返回同一实例。
- 因此 `import javafx.scene.web.WebEngine;` 变为未使用，**一并删除**（否则会留下一条我从审计脚本里正好在查的那类垃圾）。

### 9.3 代价与取舍（如实记录）

- **`MainController` 文件总行数 1523 → 1566（+43）**：因为 8 个方法的 javadoc 比原来的行内注释更长。**方法内聚性提升，但文件变长**——这是把「隐式约束」写成「显式文档」的代价，判断为值得。
- 未做进一步拆分：`MainController` 仍是 1566 行的枢纽类，但按项目约定「跨面板编排有意留主控制器」，继续拆得先改约定。

### 9.4 门禁与复验

| 项 | 结果 |
|---|---|
| `mvn -B clean test` | **lib 75 + app 444 = 519**，Failures 0 / Errors 0 / **Skipped 0**，BUILD SUCCESS，**EXIT=0** |
| 冒烟 `javafx:run`（45s） | EXIT=124（跑满被杀 = 健康）；无 NPE、无应用层异常——**构造顺序约束实测守住** |
| 复跑审计：超长方法 | `initialize()` **已从列表消失**；MainController 仅剩 `onInsertLink()` 63 行 |
| 复跑审计：未使用私有方法 | **0**（8 个新方法全部被 `initialize()` 调用） |
| 复跑审计：无注释空 catch / CSS 孤儿 / FXML 重复 id | 均 **0** |

> 本节拆分属**纯结构变更**，测试用例数不变（519）即为最有力的等价性证据——所有 FXML 加载型 GUI 测试都会真实跑一遍 `initialize()`。

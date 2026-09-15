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

## 5. 超长方法（9 个 > 60 行，可读性候选，非缺陷）

| 文件 | 行 | 方法 | 行数 |
|---|---|---|---|
| `MainController.java` | 373 | `initialize()` | **176** |
| `epubra-lib/.../OpfSpineRules.java` | 119 | `checkSpine()` | 90 |
| `epubra-lib/.../OpfSpineRules.java` | 35 | `checkOpfBasics()` | 82 |
| `epubra-lib/.../NavigationRules.java` | 101 | `checkNav()` | 79 |
| `epubra-lib/.../NavigationRules.java` | 205 | `checkNcx()` | 70 |
| `MainController.java` | 1124 | `onInsertLink()` | 63 |
| `epubra-lib/.../ContainerRules.java` | 100 | `checkRawOpf()` | 63 |
| `epubra-lib/.../EpubWriter.java` | 194 | `generateOpf()` | 62 |
| `epubra-app/.../TocController.java` | 440 | `attachDragHandlers()` | 61 |

- `MainController.initialize()` 176 行是真该拆（FXML 注入 + 事件接线 + 初始化编排全堆一处），但要守「跨面板编排有意留主控制器」的约定。
- lib 侧那 5 个是「规则逐条 `if` + `findings.add`」的天然形态，强拆反而降低对照规范的直观性，**建议维持**。

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

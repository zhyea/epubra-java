# Epubra 菜单与接线审计 · 修改建议

**日期**：2026-09-15
**来源**：本轮菜单功能全量审计（「检查下菜单中的各项功能，看看是否都正常生效」）
**范围**：7 个顶层菜单 / 26 个动作项 + 6 处 `fx:include` 子面板接线 + 快捷键注册生命周期
**门禁基线**：`mvn -B clean test` → lib 75 + app 456 = **531 passed / 0 skipped / BUILD SUCCESS**
**性质**：第 1 节是本轮已落盘的修复；第 2 节是建议。**S2、S4~S7 与 S1、S3 均已按用户指示于同日下午落地**（结果见 §5、§6），第 2 节各条现全部处于「已实现」状态。

---

## 0. 摘要

| 编号 | 优先级 | 项 | 状态 |
|---|---|---|---|
| **F1** | **P1** | Ctrl+F 与菜单「查找替换…」完全没反应 | ✅ 已修 |
| **S1** | **P1** | 「用户可见失败」的反馈口径不统一：模态弹窗 vs 状态栏 | ✅ 已修 |
| **S2** | **P1** | `UndoActivity` 撤销 / 重做各有一处静默 `return` | ✅ 已修 |
| **S3** | **P2** | 「并排预览」用翻转文字代替勾选态 | ✅ 已修 |
| **S4** | **P2** | 查找条的 Esc 关闭缺守卫（接线曾整体失效） | ✅ 已补 |
| **S5** | **P3** | `TocController.onAddChapter` 缺 `book == null` 守卫 | ✅ 已修 |
| **S6** | **P3** | `PreviewController.applyMode` 提前返回时不更新菜单文字 | ✅ 已修 |
| **S7** | **P3** | 4 个 `AD` 脏索引仍在 git 里 | ✅ 已收（未提交） |
| **F2** | **P1** | 【落地过程中新发现】测试**读**盘未隔离 → 本类用例随开发者机器状态变红 | ✅ 已修 |

**审计结论先行**：26 个动作项**接线全部正常**（无死条目、加速键无重复、显隐生命周期无泄漏）；
真正的问题集中在「**失败时不说一声**」这一族，而不是「命令没绑上」。

---

## 1. F1（已修）：Ctrl+F 与菜单「查找替换…」完全没反应

### 现象
按 Ctrl+F、或点菜单「编辑 → 查找替换…」，**界面毫无变化**；Esc 也关不掉（因为压根没打开）。

### 根因
`find-bar.fxml` 的根 `<HBox>` **没有 `fx:id`**，于是 `FindController` 的
`@FXML private Pane findBar` **恒为 `null`**：

```java
// FindController.java:88-91
public void showBar() {
    if (findBar == null) { return; }     // ← 每次调用都从这里静默出去
    ...
}
```
`closeBar()`（:104-107）同样；挂在 `findBar` 上的 Esc `eventFilter`（`bind()` 内）也从未装上。

### 关键机制：`fx:include` 的 `fx:id` 是「双份注入，各管各的」

| 谁 | 由谁的 `fx:id` 注入 |
|---|---|
| **父控制器**的 `findBar` 字段 | **父 FXML 里 include 标签的** `fx:id="findBar"` |
| 父控制器的 `findBarController` | 同上（`<fx:id>Controller` 命名约定自动派生） |
| **子控制器**自己的 `findBar` 字段 | **子 FXML 根节点的** `fx:id` ← 漏的就是这一处 |

### 为什么能潜伏至今
同一个控制器的**逻辑**方法（`findNext()` / `replaceOne()`）走的是 `bind()` 注入的 `contentArea`，
全都正常；**只有「面板显隐」这一条路是死的**，且此前没有任何测试碰过它——
7 个菜单的 `fx:id` 在任何测试里都没出现过（见 §2 S4）。

### 修复
`find-bar.fxml` 根节点补 `fx:id="findBar"`（+6 行，含注释说明为什么两边都要写）。

### 验证
`MenuAuditTest.editMenuCommandsTakeEffect` 断言 `fire("查找替换…")` 后查找条可见——
修复前该断言为红（实测 `expected: <true> but was: <false>`），修复后转绿。

---

## 2. 建议修改

### S1【P1】统一「用户可见失败」的反馈口径

**现状**：同一类失败有两种出口。

```java
// MainController.java:1587-1594 —— 模态，阻塞 FX 线程直到用户点掉
private void warn(String message) {
    Alert alert = new Alert(Alert.AlertType.WARNING);
    ...
    alert.showAndWait();                 // :1593
}
```
```java
// TocController —— 同一批「不能执行」的边界情况走状态栏
status.setStatus("已经是同级中的第一个");    // :361
status.setStatus("已经是顶层章节，无法升级");  // :399
status.setStatus("无法移动到该位置");        // :495
```

`warner.warn(...)` 的调用点是 `TocController` 的 **6 处**（:162 / :208 / :351 / :356 / :386 / :391），
全部是「操作前置条件不满足」。而 `main-window.fxml:86-91` 的注释明确承诺：

> 这两项在菜单里不灰化：……点击后由 `TocController.changeLevel` 给出**状态提示**，
> 与上移/下移的容错方式一致。

**问题**
1. **口径不一致**：同样是「不能执行」，一边弹模态、一边写状态栏；
2. **与自己的文档不符**：注释承诺状态提示，实现是模态；
3. **不可测**：模态 `showAndWait()` 在无人值守测试里会把 FX 线程挂在嵌套事件循环上——
   这 6 处 + `onAbout` + 各 `FileChooser` 路径**永远无法被自动化覆盖**（本轮审计因此只断言其接线）。

**建议改法**：把**纯提示**与**需要用户决策**分开，只对后者保留模态。

```java
// 提示类：不阻塞、可测、口径统一。StatusCoordinator 已有 flash(String)（:97），
// 会写状态栏并加 status-flash 强调样式——正是「失败提示」需要的落点。
private void warn(String message) {
    status.flash(message);
}
```
`confirmWith(...)`（:1584，丢弃未保存改动的确认）**保持 `showAndWait()` 不变**——
它需要返回值，模态是正确的。

**影响面**：`warn` 的语义从「弹窗」变为「状态栏」；调用方包括 `TocController`（6 处）、
`WorkspaceActivity`（「工作空间不存在」）、`DocumentActivity`。
若认为「工作空间不存在」值得更强的提示，可让它单独走 `show()`（非阻塞）——但**不要回到 `showAndWait()`**。

**验证**：改完后 `MenuAuditTest` 可新增一条「章节菜单无选中时点『删除章节』→ 状态栏出现
『请先在目录中选择要删除的章节』」，把这 6 条路径纳入守卫。

---

### S2【P1】`UndoActivity` 撤销 / 重做各有一处静默返回

```java
// UndoActivity.java:77-88
public void undo() {
    if (!ctx.history().canUndo()) {
        status.setStatus("没有可撤销的操作");     // ← 有反馈
        return;
    }
    commitPendingEdits();
    BookHistory.Snapshot snapshot = ctx.history().undo(ctx.book(), ctx.dirty());
    if (snapshot == null) { return; }            // :84-86 ← 静默，同一方法内两种口径
    restore(snapshot, "已撤销");
}
```
`redo()` 的 `:98-100` 完全同形。

**实测证据**：在 `MenuAuditTest` 中曾观测到 `canRedo() == true` 的情况下点「重做」，
状态栏**毫无变化**（仍停留在上一条「已撤销」）。
**推测路径**：`canRedo()` 检查与 `history.redo()` 之间隔着 `commitPendingEdits()`，
若该 flush 触发了一次新的历史记录，`record()` 会清空重做栈（`BookHistory.java:59`），
于是 `redo()` 返回 `null`。
**同时说明**：干净流程下**未复现**（实测「添加章节 → 撤销 → 重做 → 再重做」四项状态与章节数全部正确），
所以这条按「口径不一致 + 潜在静默」而非「已证实的用户可见缺陷」对待。

**建议改法**：与上方守卫同口径，不再静默。

```java
if (snapshot == null) {
    status.setStatus("没有可撤销的操作");   // undo()；redo() 用「没有可重做的操作」
    return;
}
```

**收益**：把「按了没反应」变成「说清为什么」，同时让这条分支在测试里可断言。

---

### S3【P2】「并排预览」：翻转文字 → 勾选态（**需你决定**）

**现状**：`PreviewController.java:184-185`

```java
if (splitPreviewItem != null) {
    splitPreviewItem.setText(splitPreview ? "标签预览" : "并排预览");
}
```

**问题**
1. 菜单项**没有勾选态**，当前状态**只能靠文字反推**——而文字写的是「点了会切到哪」，不是「现在是什么」；
2. 文字成了**不稳定标识**：任何按文字定位的代码/测试在开关一次后失效
   （既有 `HomeViewMenuTest:107` 就断言了字面量 `"并排预览"`，只是它从不切换模式才没暴露）；
3. 与项目既有约定不一致：工具条按钮的「激活态」走**视觉强调**（`toolbar-action` / 实心强调底），
   而这里用改标题表达状态。

**建议改法**（三选一，倾向 A）
- **A**：`MenuItem` → `CheckMenuItem`，文字固定「并排预览」，`setSelected(splitPreview)`
  表示当前状态。改动最小，语义最清楚。
- **B**：保持翻转文字，但补 Tooltip「当前：标签模式 / 并排模式」。
- **C**：维持现状，接受文字翻转。

**影响面**：`HomeViewMenuTest`（断言字面量）与 `MenuAuditTest`（已用 `fx:id` 定位、
并把翻转行为固化为契约）需要按选择同步。选 A 之后 `MenuAuditTest` 里那条「文字会翻转」
的断言应改为「选中态跟随模式」，**更稳**。

---

### S4【P2】补齐查找条守卫，并做一次同类排查

F1 之所以能潜伏，是因为**「面板显隐」这一层零覆盖**。本轮已补：

- `MenuAuditTest`：`fire("查找替换…")` → 断言查找条可见（F1 的直接守卫）。

建议再补两项：
1. **Esc 关闭**：`findBar` 上的 `KEY_PRESSED` filter 生效性。
   由于没有公开 API 枚举 `eventFilter`，只能用 `Robot` 端到端——
   注意项目里两个 Robot 用例（`VisualEditorShortcutSinglePathTest` / `VisualEditorTabFocusEscapeTest`）
   **依赖前台窗口焦点，无人值守时会随机假红**，新用例请照抄它们的 `Assumptions` 兜底。
2. **同类排查**：给所有子控制器加一次「关键字段非空」自检，或直接把
   `@FXML` 注入字段的 `null` 视为启动期错误（早失败好过静默）。
   本轮已人工核对 6 处 include（`tocView` / `resourceView` / `metadataView` / `bottomPanel` /
   `welcomePage` / `findBar`），**只有 findBar 漏**；建议把这条检查固化，避免下次新增 include 重犯。

---

### S5【P3】`TocController.onAddChapter` 缺 `book == null` 守卫

```java
// TocController.java:149-157
public void onAddChapter() {
    beginChange.run();
    String title = "第 " + (ctx.book().spine().size() + 1) + " 章";   // ← 无书直接 NPE
    ...
}
```
**当前靠菜单显隐兜住**（「章节」菜单在书架态是收起的，`EditorShellActivity:68`），
所以用户碰不到。但这是**隐式耦合**：菜单可见性一旦调整、或该命令被别的入口
（快捷键、右键菜单、自动化）调到，就会把 NPE 抛到 FX 事件线程——正是 `DocumentActivity`
里已经踩过并写进 javadoc 的那类问题（「用户看到的是按了没反应」）。

**建议**：补一个与 `onDeleteChapter`（:161-164）同形的守卫。

---

### S6【P3】`PreviewController.applyMode` 提前返回时不更新菜单文字

```java
// PreviewController.java:165-190
private void applyMode() {
    if (splitPreviewPane == null || editorTabs == null
            || editorTabs.getTabs().size() <= previewTabIndex) {
        return;                                   // :166-169 ← 从这里出去，:184 的文字更新不执行
    }
    ...
    if (splitPreviewItem != null) {
        splitPreviewItem.setText(splitPreview ? "标签预览" : "并排预览");
    }
}
```
`toggleSplit()`（:146-151）会先把 `splitPreview` 取反再调 `applyMode()`，
所以提前返回时会出现「**内部状态已翻转、界面与文字未变**」的不一致。
实践中 tab 总是就绪，属**潜在**问题。
**建议**：把文字更新提到提前返回之前（或把两段拆开），保证状态与显示始终同源。

---

### S7【P3】仓库卫生

`git status` 里仍有 4 个 `AD`（已从磁盘删除、却仍暂存为新增）：

```
AD epubra-app/src/main/java/org/chobit/epubra/app/document/ProjectLayout.java
AD epubra-app/src/main/java/org/chobit/epubra/app/workspace/RecentProjectsStore.java
AD epubra-app/src/test/java/org/chobit/epubra/app/document/ProjectLayoutTest.java
AD epubra-app/src/test/java/org/chobit/epubra/app/workspace/RecentProjectsStoreTest.java
```
**建议**：`git add -A` 让索引与工作树一致（一次收干净，避免下次提交误带）。

---

## 3. 已核实为「设计如此 / 时序伪影」，**不建议改**

| 现象 | 核实结论 |
|---|---|
| 空书时点「保存 / 另存为」只给状态提示 | **正确**——`DocumentActivity.reportNothingToSave()` 有 javadoc 记录旧版 NPE 教训 |
| 「章节」菜单在书架态整栏收起、不做灰化 | **有意**（`main-window.fxml:75-92` + `EditorShellActivity`）；仅 S5 建议补内部守卫 |
| 切页面后读 `Scene.getAccelerators()` 只剩 `F10` | **布局中途态**：加速键登记挂在 MenuBar 布局通道上，可见性一变先清空、下一脉冲重建。强制 `applyCss()+layout()` 即恢复正确集合（探针实测）。**不是缺陷**，但测试必须在布局稳定后取值 |
| 探针里 `RadioMenuItem.fire()` 后 `isSelected()` 仍为 `false` | `fire()` **不负责选中态**（选中由菜单点击行为完成）。**不是缺陷**，测试应改验 `ToggleGroup` 接线 |
| 40+ 处 `if (x == null) return;` | 绝大多数是**对话框取消**（`FileChooser`/`TextInputDialog` 返回 null）与内部守卫，**正当**；真正需要改的只有 S2 / S5 |

---

## 4. 落地顺序与验证

| 序 | 项 | 落点 | 验证 |
|---|---|---|---|
| 1 | S2 | `UndoActivity.java:84-86`、`98-100` | `MenuAuditTest.editMenuCommandsTakeEffect`（撤销/重做往返）；可另加「空历史→提示」用例 |
| 2 | S1 | `MainController.java:1587-1594`（`warn`） | 新增「章节菜单无选中 → 状态栏提示」用例，把 6 条路径纳入守卫 |
| 3 | S3 | `PreviewController.java:184-185` + `main-window.fxml` | `HomeViewMenuTest`、`MenuAuditTest` 同步改断言 |
| 4 | S5 / S6 | `TocController.java:149-157`、`PreviewController.java:165-190` | 现有 GUI 用例即可覆盖 |
| 5 | S4 / S7 | 测试与索引 | `mvn -B clean test` → **531** |

**门禁命令**（沿用本机可用路径）：
```bash
export PATH="/d/Program Files/Git/usr/bin:$PATH" && cd "D:/MyDevelop/JDevelop/workspace/epubra" && \
"D:/Program Files/Java/jdk-25.0.3/bin/java.exe" --enable-native-access=ALL-UNNAMED \
  --add-opens java.base/java.lang=ALL-UNNAMED \
  -classpath "D:/MyDevelop/JDevelop/maven/apache-maven-3.9.15/boot/plexus-classworlds-2.9.0.jar" \
  "-Dclassworlds.conf=D:/MyDevelop/JDevelop/maven/apache-maven-3.9.15/bin/m2.conf" \
  "-Dmaven.home=D:/MyDevelop/JDevelop/maven/apache-maven-3.9.15" \
  "-Dlibrary.jansi.path=D:/MyDevelop/JDevelop/maven/apache-maven-3.9.15/lib/jansi-native" \
  "-Dmaven.multiModuleProjectDirectory=D:/MyDevelop/JDevelop/workspace/epubra" \
  org.codehaus.plexus.classworlds.launcher.Launcher -B clean test
```
**期望**：`Tests run: 531, Failures: 0, Errors: 0, Skipped: 0` + `BUILD SUCCESS`（含 S1/S4 新增用例后会递增）。

---

## 5. 落地结果（第一批：S2 / S4~S7，2026-09-15）

### 5.1 逐项落地

| 项 | 落点 | 实际改法 |
|---|---|---|
| **S2** | `UndoActivity.undo()` / `redo()` | 两处 `snapshot == null` 由静默 `return` 改为写状态栏「没有可撤销 / 可重做的操作」，与上方 `canUndo()/canRedo()` 守卫**同口径** |
| **S5** | `TocController.onAddChapter()` | 在 `beginChange.run()` **之前**补 `book == null` → `warner.warn("请先打开或新建一本图书")` + return（放前面才不会压一条空快照） |
| **S6** | `PreviewController.applyMode()` | 把 `splitPreviewItem.setText(...)` 提到早返回守卫**之前**——菜单文字只跟随 `splitPreview` 状态，不再出现「状态已翻转、文字停在旧值」 |
| **S4①** | `MenuAuditTest.findBarEscapeClosesIt()` | Esc 关闭查找条的确定性守卫（实现方式见 5.2） |
| **S4②** | `FxmlInjectionIntegrityTest`（新类） | 反射走遍整棵控制器树、断言每个 `@FXML` 字段非空（见 5.2） |
| **S7** | `git add -A` | 4 个 `AD` 脏索引收干净，索引与工作树一致。**未提交**（按长期约定需显式批准） |

### 5.2 S4 的两处实现选择（与原建议不同，理由）

**① Esc 关闭改用 `Event.fireEvent`，不用 `Robot`。**
原建议写「只能用 `Robot` 端到端 + `Assumptions` 兜底」，但项目里两个既有 Robot 用例依赖前台窗口焦点、
无人值守会随机假红；再加一个等于把不稳定的路走宽。实测向 `findBar` 直接派发合成 `KeyEvent` **有效**：
它走该节点的完整事件派发链，**捕获阶段注册的 `eventFilter` 必被执行**。
→ 本项目原有的「合成 `MouseEvent` 进不了 `InputMap`」教训**不适用于节点上的 eventFilter**
（那是 Scene 快捷键绑定层的问题），不要混用。

**② `@FXML` 自检做成测试，不做运行期抛异常。**
反射 + 真 `FXMLLoader` 加载，从 `MainController` 递归进每个 `controller` 包下的类型字段，
断言全部 `@FXML` 字段非空，失败时报绝对路径。实测本机 **97 个 `@FXML` 字段全部非空**——
即除 F1 外**没有其它 include 漏接线**。另加两条「防假绿」下限（控制器 ≥ 6、`@FXML` 字段 ≥ 80）。

**已做负向验证**：临时摘掉 `find-bar.fxml` 根节点的 `fx:id`，该测试精确变红并输出
`MainController.findBarController.findBar ==> expected: <[]> but was: <[MainController.findBarController.findBar]>`，
随后立即还原。「守卫写了」与「守卫真的会红」是两件事，这一步不能省。

### 5.3 落地过程中新发现并修掉的缺陷：F2

聚焦跑 S4 时 `MenuAuditTest` 两条用例先红（「暂无最近工作空间」占位项消失）。**与本次 S2/S5/S6 改动无关**：

- **根因**：该用例断言「最近工作空间为空 → 禁用占位项」，而菜单由 `WorkspaceActivity.refreshRecentMenu()`
  在 `initialize()` 阶段按 `WorkspaceStore`（`PreferenceNodes` → **真实注册表**
  `HKCU\Software\JavaSoft\Prefs`）构建。本机确有真实记录 `D:\Users\robin\Desktop\workspace`，
  目录存在 → 菜单是 1 条可点项 → 断言红。
- **性质**：**「测试写盘必须隔离」的镜像问题——读盘同样要隔离**。这是**只在开发者本机开过工作空间时**
  才复现的环境依赖型缺陷；此前那轮 531 绿只是碰巧（那时注册表还没这条）。
- **修法**：`@BeforeAll` 中**加载 FXML 之前** `PreferenceNodes.useInMemoryForTesting()`，
  `@AfterAll` 里 `resetForTesting()` 还原。
- **附带核实**：注册表里 `\Epubra\RecentProjectsStore` 等**已删除类的遗留节点仍在**（Preferences 不随类删除自动清理）——
  本次未动，留待做偏好迁移清理时一并处理。

### 5.4 门禁（第一批后）

```
mvn -B clean test  →  lib 75 + app 458 = 533
                      Failures: 0 / Errors: 0 / Skipped: 0
                      BUILD SUCCESS (Total time: 01:29 min)
```

531 → 533 = **+1**（`findBarEscapeClosesIt`）**+1**（`FxmlInjectionIntegrityTest`）。

---

## 6. 落地结果（第二批：S1 / S3，2026-09-15）

### 6.1 S1：`warn()` 由模态弹窗改为状态栏

```java
// MainController.java —— 改前
private void warn(String message) {
    Alert alert = new Alert(Alert.AlertType.WARNING);
    ...
    alert.showAndWait();                     // 阻塞 FX 线程，且永远测不了
}

// 改后
private void warn(String message) {
    status.flash(message);                   // 复用 StatusCoordinator.flash（写状态栏 + 1.2s 强调样式）
}
```

- 与 `TocController` 既有的「已经是同级中的第一个」「无法移动到该位置」等提示**口径统一**；
- `confirm(title, message)`（需要用户返回值的「丢弃未保存修改」）**保持 `showAndWait()` 不变**——
  那一类模态才是正确的，javadoc 里专门写了「不要顺手把它也改掉」；
- 影响面：`this::warn` 的 4 处注入点（`WorkspaceActivity` / `TocController` / `DocumentActivity` 等）
  ＋ 章节菜单 6 条「操作前置条件不满足」路径，共 10 个出口。

**收益（可测性）**：这 6 条路径此前因为模态而**完全无法自动化**，现在已纳入守卫——
新增 `MenuAuditTest.chapterCommandsWithoutSelectionReportToStatusBar`，显式
`tocViewController().clearSelection()` 构造「无选中」前置态，断言状态栏出现
「请先在目录中选择要删除的章节」/「请先在目录中选择要移动的章节」。

### 6.2 S3：并排预览改 `CheckMenuItem` 勾选态

| 层 | 改动 |
|---|---|
| `main-window.fxml` | `<?import ...CheckMenuItem?>`；`<MenuItem …>` → `<CheckMenuItem fx:id="splitPreviewItem" text="并排预览" …/>` |
| `MainController` | 字段类型 `MenuItem` → `CheckMenuItem`（`EditorShellActivity` 收的是 `List<MenuItem>`，无需改） |
| `PreviewController` | 字段 / 构造参数类型 → `CheckMenuItem`；`applyMode()` 里 `setText(split ? "标签预览" : "并排预览")` → **`setSelected(splitPreview)`** |

文字从此**固定**为「并排预览」，当前是否开启由勾选态表达，消除了两个既有问题：
文字写的是「点了会切到哪」而非「现在是什么」；以及文字成为**不稳定标识**（按文字定位的代码/测试
在开关一次后失效）。

### 6.3 ⚠ 一个被实测纠正的假设（值得记）

做 S3 的守卫时我原本假设「`CheckMenuItem.fire()` 自己会翻选中态，所以必须绕过 `fire()` 才验得到同步」。
**负向验证把这条假设推翻了**：注掉 `applyMode()` 里的 `setSelected(splitPreview)` 后，测试**在 `fire()` 那条断言上就红了**
（`并排模式下菜单项应处于勾选态 ==> expected: <true> but was: <false>`）——

> `CheckMenuItem.fire()` **不会**改变选中态。`MenuItem.fire()` 只发一个 `ActionEvent`，
> 选中态由菜单的点击行为（skin/behavior）完成。**`RadioMenuItem` 是同一机制**
> （此前本类里那条「`RadioMenuItem.fire()` 不负责选中态」的注释现在有了同源解释）。

所以「勾选态」**完全依赖** `applyMode()` 里那句 `setSelected()`——它既不能省，也不能挪进
`onToggleSplitPreview()`（那样只有菜单命令能同步）。
测试里保留**直接调 `previewController().toggleSplit()`** 的那两条断言，守的正是
「同步挂在 `applyMode()`（状态变更的必经之路）上」，与经由菜单的路径语义不同。
两处注释已按实测结论改写，避免把错误假设留在代码里误导后来者。

### 6.4 门禁（两批合计）

```
mvn -B clean test  →  lib 75 + app 459 = 534
                      Failures: 0 / Errors: 0 / Skipped: 0
                      BUILD SUCCESS (Total time: 01:44 min)
```

531 → 534 = **+3**：`findBarEscapeClosesIt`（S4）、`FxmlInjectionIntegrityTest`（S4）、
`chapterCommandsWithoutSelectionReportToStatusBar`（S1）。

**§2 的建议至此全部落地，无遗留项。** 除 S7 的暂存外，所有改动**均未提交**——
按长期约定需用户显式批准。


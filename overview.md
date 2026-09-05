# Epubra 重构专项收尾（2026-09-05 · #19 FXML 拆分完成）

20 项重构路线**全部闭环**。本轮完成最后一项 #19：main-window.fxml 由 378 行单文件拆为 1 主 + 5 子。

## #19 拆分结果

| 文件 | 行数 | fx:controller |
| --- | --- | --- |
| `main-window.fxml`（主） | 378 → **234** | MainController（菜单/活动栏/编辑区/状态栏） |
| `toc-view.fxml` | 21 | TocController |
| `find-bar.fxml` | 33 | FindController |
| `resource-view.fxml` | 54 | ResourceController |
| `metadata-view.fxml` | 56 | **MetadataViewController（新建，97 行）** |
| `problems-panel.fxml` | 58 | ValidationController |

## 新架构模式

1. **`fx:include` 双字段注入**：`<fx:include fx:id="tocView"/>` 注入父控制器「根节点 + 子控制器」两个字段（命名规则 `<fx:id>Controller`）；子 FXML 内控件只进子控制器。
2. **两段式绑定**：子控制器 = 无参构造 + `@FXML` 节点 + `bind(ctx, 回调...)`；禁止子控制器定义 initialize()（执行先于父，ctx 未注入）。父 initialize() 统一 bind。
3. **跨 FXML 边界**：SidebarController 横跨主 FXML 与多个 include，保持手动构造；editorTabs/contentArea 经 bind 注入给需要的子控制器。
4. **元数据逻辑整体迁出**：MainController 935 → **788 行**，onApplyMetadata 等 5 个方法迁 MetadataViewController。

## 验证门禁（真实退出码）

- `mvn -B clean test`：**BUILD SUCCESS**，epublib 56 + app 92 = **148 / 148 全绿**（零测试需改动）
- `mvn -B install -DskipTests`：BUILD SUCCESS
- `timeout 40 mvn javafx:run`：Exit 143（timeout 杀前台 GUI = 进程存活），零 LoadException / NullPointer，glass + webkit native 初始化成功

## 附带清理

- 根目录 `com/`（untracked 的 2 个散落 .class，昨天裸跑 javac 未指定 `-d` 的产物）已删除

## 20 项重构总览

#1–#12 早期清理与控制器拆分、#13 MetadataOps、#14 SidebarController、#15 MainController 瘦身（1245→935→788）、#16 StructureRules 拆分、#17 EventBus 替代 refreshAll、#18 Java 25 特性、#19 FXML 拆分、#20 验证总结——全部完成。

## Follow-up

- 目录树层级升降级；HTMLEditor 所见即所得；打包发布
- Git 未提交（按约定）
- 详细日志：`.workbuddy/memory/2026-09-04.md`、`2026-09-05.md`；FXML 拆分长期约定见 `.workbuddy/memory/MEMORY.md`
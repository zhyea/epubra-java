# 封面设置功能设计

> 状态：待评审（设计稿，未实现）
> 日期：2026-09-06
> 范围：Epubra JavaFX 版（epubra-epublib 内核 + epubra-app 前端）

---

## 1. 现状盘点

### 1.1 内核已有能力（epublib，无需重造）

| 能力 | 位置 | 说明 |
| --- | --- | --- |
| 设置封面 | `Book.setCover(Resource)`（Book.java:289） | 给资源补 `cover-image` 属性 + `metadata.setProperty("cover", id)` |
| 清除封面 | `Book.setCover(null)`（Book.java:297） | 清 id + 移除 `cover` 属性 |
| 读取封面 | `Book.coverResource()`（Book.java:100） | 先按 `cover-image` 属性找，退化为 `coverResourceId` |
| 自动清理 | `Book.removeResource`（Book.java:191） | 删到封面资源时自动 `setCover(null)` |
| OPF 写出 | `EpubWriter`（:195 / :206） | 写 `<meta name="cover">` + manifest item `properties="cover-image"`，EPUB 2/3 双写 |
| OPF 读回 | `EpubReader.readCover`（:195） | 从 `metadata.property("cover")` 恢复 `coverResourceId` |
| 校验规则 | `NavigationRules.checkCover`（:297） | D18 封面 id 解析不到 = ERROR；D19 封面非图片 = WARNING |

结论：**EPUB 2/3 双写已完备，内核 0 改动即可支撑 P0/P1**。

### 1.2 前端现状

- 唯一入口：资源侧栏按钮「设为封面」→ `ResourceController.setCoverFromSelected()`（:158）
- 交互：必须先在资源表选中图片 → 点按钮；非图片时弹警告框
- 反馈：状态栏一行「已设为封面：xxx」

### 1.3 缺口（本设计要补的）

| 编号 | 缺口 | 影响 |
| --- | --- | --- |
| G1 | 无封面预览 | 用户不知道当前封面是哪张、长什么样 |
| G2 | 无「移除封面」入口 | 想取消只能删除该图片资源，代价过大 |
| G3 | 无空态引导 | 新书无封面时界面上没有任何提示 |
| G4 | 资源表无封面标记 | 列表里分辨不出哪张是封面 |
| G5 | 无尺寸/格式建议 | 用户不知道该用什么规格的图 |
| G6 | 无封面页（cover.xhtml） | 多数商店与部分阅读器要求首屏为封面页 |
| G7 | 无异常态提示 | 封面 id 悬空时（D18）界面无任何提示 |

---

## 2. 设计目标与非目标

**目标**
1. 封面「看得见、换得掉、撤得回」——三态完整
2. 主路径 3 次点击内完成（打开元数据面板 → 封面卡 → 选择图片）
3. 封面与教育资源解耦：换封面不必在资源表里翻找
4. 内核零改动优先（P0/P1），进阶能力才动内核

**非目标（本期不做）**
- 封面图编辑（裁剪、滤镜、加文字）
- 封面模板生成 / AI 生成封面
- 多尺寸封面（thumbnail / full）管理
- 封面图自动压缩转码

---

## 3. 信息架构：封面应该在哪设置

封面在 EPUB 规范里属于**元数据**（`<meta name="cover">` + manifest `cover-image`），因此主落点放在**元数据面板**，资源侧栏保留为次级入口。

| 入口 | 层级 | 承担 | 阶段 |
| --- | --- | --- | --- |
| 元数据面板「封面卡」 | 主 | 查看 / 更换 / 移除 / 空态引导 | P0 |
| 资源侧栏「设为封面 / 取消封面」 | 次 | 浏览资源时顺手指定；列表标记当前封面 | P0 |
| 菜单「工具 → 封面设置…」对话框 | 进阶 | 大预览、尺寸信息、封面页生成 | P1 |

**为什么不在资源侧栏做主入口**：资源表按媒体类型混排（图片/字体/样式），封面是「书的属性」而非「某个资源的操作」，放在元数据更符合心智，也与 WPS/Office「属性面板带预览卡」的形态一致。

---

## 4. UI 设计

### 4.1 元数据面板封面卡（P0）

位置：`metadata-view.fxml` 顶部，「元数据」标题之下、书名之上。

```
┌─ 元数据 ─────────────────────────┐
│ ┌────────────────────────────┐   │
│ │ [封面卡]                    │   │
│ │  ┌────┐  cover.jpg          │   │
│ │  │缩略│  1600 × 2400         │   │
│ │  │ 图 │  1.2 MB · JPEG       │   │
│ │  └────┘  images/cover.jpg    │   │
│ │  [更换…]  [移除]             │   │
│ └────────────────────────────┘   │
│ 书名  [___________________]       │
│ 作者  [___________________]       │
└──────────────────────────────────┘
```

三态（见对话内草图）：

| 状态 | 条件 | 呈现 |
| --- | --- | --- |
| 已设置 | `coverResource()` 命中 | 缩略图 84×112 + 文件名 / 尺寸 / 大小 / href + 「更换…」「移除」 |
| 空态 | 无封面 | 虚线占位 + 「未设置」+ 一句规格建议 + 「选择图片…」 |
| 异常态 | 有 `coverResourceId` 但 manifest 无该 id（D18） | 红底警告图标 + 「封面引用失效」+ 规则号 + 「重新选择…」「清除引用」 |

**缩略图规格**：84×112（3:4 容器），`PreserveRatio` 居中，圆角 6；图片加载失败回落到空态图标而不抛异常。

**为什么同时显示 href**：EPUB 里同名的 `images/cover.jpg` 与 `cover.jpg` 是不同资源，href 是唯一可辨信息；校验报错时也靠它定位。

### 4.2 资源侧栏增强（P0）

- 表格新增逻辑列标记：当前封面行名称前缀加 `●`（或单独「封面」徽章），复用现有 `PropertyValueFactory` 需在 `ResourceRow` 加 `isCover()` 属性
- 按钮语义随状态切换：选中项已是封面 → 按钮变「取消封面」；否则「设为封面」
- 保留原「插入图片」「清理未引用」按钮不变

### 4.3 封面设置对话框（P1）

从「工具 → 封面设置…」打开（见对话内草图）：

- 左：2:3 大预览 + 比例标注
- 右上：文件 / 格式 / 最短边 / 链接方式四项只读信息
- 右中：书内图片缩略图网格（4 列），当前封面 2px 强调边框，末位「导入」虚线格
- 右下：「导入图片并设为封面…」「移除封面」
- 底部：复选框「生成封面页 cover.xhtml 并置于阅读顺序开头」

---

## 5. 数据模型与内核改动评估

### 5.1 P0 / P1：内核零改动

全部复用 `Book.setCover(Resource)` / `setCover(null)` / `coverResource()`。

新增前端纯逻辑类（符合项目「与控件无关的逻辑放 support」约定）：

```
com.epubra.app.support.CoverOps          // 封面状态判定 + 操作语义
  ├─ describe(Book)      -> CoverState    // EMPTY / SET / DANGLING
  ├─ pick(Book, Resource) -> boolean      // 资源可否作封面（是否图片）
  └─ clear(Book)                          // 清封面（含属性回退）

com.epubra.app.support.CoverImageInfo    // 纯 Java 解析 PNG/JPEG/GIF/WebP 头取宽高
  └─ read(byte[]) -> Optional<Dimension>
```

**为什么自己解析图片头而不是用 `javafx.scene.image.Image`**：`Image` 依赖 graphics 模块且与 toolkit 生命周期耦合，无法在无头单测里稳定断言；PNG/JPEG 头解析是几十行定长字节读取，可 100% 单测覆盖（GIF/WebP 同理，SVG 走 XML 解析 `width/height/viewBox`）。

`CoverState` 三态判定：

```
coverResourceId == null && 无 cover-image 属性        -> EMPTY
coverResource() 命中                                   -> SET
coverResourceId != null 但 getById 为空                -> DANGLING（对应校验 D18）
```

### 5.2 P2：封面页生成（内核小改）

生成封面页需要「插入 spine 首位且不进目录」，现有 API 不支持：

- `Book.addChapter` 是 append 到末尾并自动加目录项（Book.java:111）
- `Spine` 只有 `add` / `addResourceId` / `removeResourceId`，**没有 insert by index**（Spine.java:15-39）

建议内核新增：

```
Spine.insertResourceId(int index, String resourceId)   // 或 addFirst(String)
Book.addCoverPage(String title)  // 内部：建 cover.xhtml 资源 + spine 插首位 + 不加目录
```

封面页 XHTML 内容（内联 `<svg>` 保证尺寸自适应，避免外层 div 高度塌陷）：

```xml
<body><div class="cover">
  <svg xmlns="http://www.w3.org/2000/svg" version="1.1"
       width="100%" height="100%" viewBox="0 0 600 900" preserveAspectRatio="xMidYMid meet">
    <image width="600" height="900" xlink:href="../images/cover.jpg"/>
  </svg>
</div></body>
```

---

## 6. 交互流程与状态机

### 6.1 更换封面（主路径）

```
封面卡「更换…」
  → FileChooser（*.png *.jpg *.jpeg *.gif *.webp *.svg）
  → 用户选文件
      ├─ 书内已有同 href 资源 → 直接复用该 Resource，不重复导入
      └─ 无 → book.addResource(path) 导入到 images/
  → beginChange()          // 撤销快照（沿用现有 BookHistory 机制）
  → book.setCover(resource)
  → markDirty()            // 触发自动暂存节流
  → 刷新封面卡 + 资源表 + 状态栏 flashStatus("已设为封面：xxx")
```

### 6.2 移除封面

```
「移除」→ beginChange() → book.setCover(null) → markDirty() → 刷新
```

**注意**：移除封面**不删除图片资源**——它仍可能在正文里被引用。`Book.removeResource` 才有删除语义，两者不可混用。

### 6.3 状态迁移

```
        ┌──────────── 设置封面 ────────────┐
        ↓                                  │
     EMPTY ────设置封面───→ SET ────移除───→ EMPTY
        │                   │
        │                   └──删除封面资源──→ EMPTY（removeResource 自动清）
        │
        └──导入外部书（cover id 悬空）──→ DANGLING ──清除引用/重选──→ EMPTY / SET
```

### 6.4 撤销语义

所有封面操作走 `beginChange()`（现有机制：先写回正文与元数据，再拍快照），因此**撤销一次即可回到上一个封面**，与目录/正文操作共享同一撤销栈，无需额外设计。

---

## 7. 校验与边界规则

### 7.1 复用现有规则

- **D18** 封面 id 悬空 → ERROR（已实现，本设计把它从「只有跑校验才看到」前移到封面卡的常态提示）
- **D19** 封面非图片 → WARNING（已实现）

### 7.2 建议新增规则（P1/P2）

| 建议编号 | 规则 | 级别 | 理由 |
| --- | --- | --- | --- |
| D20 | 封面图最短边 < 600px | WARNING | 高分屏书架上缩略图发虚 |
| D21 | 封面图 > 5 MB | INFO | 商店上传限制与包体积 |

> 是否新增、级别如何定，取决于「Epubra 定位为严谨校验器还是轻编辑器」——见第 9 节待决策 Q3。

### 7.3 边界处理清单

- SVG 封面：允许（MediaTypes.SVG 已是合法图片），但部分老阅读器不渲染 → 封面卡给 INFO 提示
- 同名不同目录：`images/cover.jpg` 与 `cover.jpg` 视为两个资源，靠 href 区分
- 封面资源被删除：`Book.removeResource` 已自动 `setCover(null)`，前端只需刷新
- 导入超大图（>10 MB）：导入前提示，不阻断
- 无书打开（ctx.book() == null）：封面卡整体禁用，与现有元数据面板的 null 守卫一致

---

## 8. 分阶段交付计划

| 阶段 | 内容 | 内核改动 | 前端改动 | 预估新增测试 |
| --- | --- | --- | --- | --- |
| **P0** | 封面卡三态（查看/更换/移除/空态/异常态）+ 资源表封面标记 + 按钮语义切换 | 无 | `metadata-view.fxml` 加封面卡；`MetadataViewController` 接线；`ResourceRow.isCover()`；`support/CoverOps`、`support/CoverImageInfo` | CoverOps 3 态判定 + 边界 ≈ 12；CoverImageInfo 各格式解析 ≈ 10 |
| **P1** | 封面设置对话框（大预览 / 信息 / 图片网格 / 导入入口） | 无 | 新 `cover-dialog.fxml` + `CoverDialogController`；菜单「工具」加入口 | 对话状态构造 ≈ 6 |
| **P2** | 封面页生成 + 新校验规则 D20/D21 | `Spine.insertResourceId` + `Book.addCoverPage` | 对话底复选框；`IssueKind` 加两条 | 内核 round-trip ≈ 5；规则 ≈ 6 |

**验证门禁（每阶段）**
1. `mvn -B clean test` 全绿（P0 后基线 230 → 约 252）
2. `cd epubra-app && timeout 25 mvn -B javafx:run` 无 LoadException / NullPointer / ClassNotFound
3. 手工：新建书 → 设封面 → 移除 → 撤销 → 保存 → 重开，确认 OPF 里 `meta name="cover"` 与 `cover-image` 同步且往返一致

---

## 9. 待决策（需拍板后再实施）

| # | 问题 | 选项 A | 选项 B | 建议 |
| --- | --- | --- | --- | --- |
| Q1 | P0 是否包含资源表封面徽章 | 包含（列表可辨） | 不含（只做封面卡） | A —— 成本低，且解决 G4 |
| Q2 | 封面卡缩略图尺寸 | 84×112（偏小，省侧栏空间） | 120×160（更清楚但挤占表单） | A —— 侧栏仅 270px 宽，120 会挤压书名输入框 |
| Q3 | 是否新增 D20/D21 校验规则 | 新增（更严谨） | 不新增（避免噪音报警） | B 起步，等用户反馈再补——Epubra 目前定位偏轻编辑 |
| Q4 | 封面页生成（P2）是否进本期 | 进（商店兼容刚需） | 延后 | 延后——需动内核 spine 插入语义，与当前「内核稳定」节奏冲突 |
| Q5 | 无封面时是否主动提示 | 空态常驻一句建议 | 完全静默 | A —— 解决 G3，且只是一行 `panel-hint` |

---

## 10. 与既有约定的一致性检查

- **主题**：封面卡全部走 `-epubra-*` 变量，新增样式需给三套主题各配一次（`app.css`）
- **可测性**：`CoverOps` / `CoverImageInfo` 放 `support`，控制器只做事件转发
- **撤销**：所有写操作前 `beginChange()`
- **脏标记**：所有写操作后 `markDirty()`（自动暂存节流挂点）
- **FXML 拆分**：若 P1 做对话框，按四步流程（子 FXML + 自带 controller + bind + include）
- **不引入第三方库**：图片头解析自写，不引 ImageIO 之外的任何依赖（ImageIO 亦非必需）

# 一键整理：XP插件文件夹 + 钉选首页应用 设计

日期：2026-09-26
状态：已与用户确认

## 需求

1. 一键整理新增「XP插件」文件夹类别：XP 模块应用按现有收夹规则归入该类。
2. 一键整理时将指定应用固定到首页：MT管理器、QQ、微信、酷安、高德地图、RikkaHub、支付宝。应用不存在则跳过，按默认操作。

## 已确认的决策

| 决策点 | 结论 |
|--------|------|
| XP插件成员判定 | 用户映射表配置（assets/category_mapping.json），代码只加类别 |
| 首页应用匹配 | 包名精确匹配，硬编码；找不到就跳过 |
| 首页布局 | 所有文件夹在最前，然后按序铺钉选应用，再铺其余图标 |
| 已在文件夹/Dock 里的钉选项 | 强制抽出放首页；被抽空的夹剩 <2 成员则解散 |
| XP插件收夹阈值 | 与其它类别一致：≥4 才建夹 |

## 设计

### 1. 新类别 XP

- `Category`（domain/classifier/Category.kt）增加 `XP("xp", "XP插件", order=10)`；`OTHER` 的 order 由 10 改为 11，保持「未分类」永远最后。
- `Category.byLabel()` 无需改动（遍历 entries），自动识别「XP插件」「XP插件 2」。
- 收夹阈值、容量、续编号规则全部复用 `AutoArranger` 现有逻辑，无特判。
- 判定规则写在 `assets/category_mapping.json`（id `"xp"`），预置常见 Xposed 模块关键词（xposed/lsposed/edxposed/module/模块 等）与包名（如 org.lsposed.manager），用户后续改 JSON 即可增删成员，无需重编译。
- `CategoryMappingLoader` 无需改动（`Category.byId("xp")` 自动生效）。

### 2. 钉选首页应用

- 新增 `PinnedApps`（domain/classifier，object）：有序包名列表，硬编码：
  1. `bin.mt.plus`（MT管理器）
  2. `com.tencent.mobileqq`（QQ）
  3. `com.tencent.mm`（微信）
  4. `com.coolapk.market`（酷安）
  5. `com.autonavi.minimap`（高德地图）
  6. `cc.rikka.rikkahub`（RikkaHub）
  7. `com.eg.android.AlipayGphone`（支付宝）
- 匹配方式：`componentPackage()` 与包名列表精确比对（按列表顺序去重，一包多项取首个）。
- 匹配不到的跳过；钉选项：
  - 不参与 `categoryOf` 分组、不计入任何新夹（RikkaHub 即使命中 XP 规则也上首页）；
  - 从既有文件夹或 Dock 强制抽出（`container` 改回 `CONTAINER_DESKTOP`）；抽出后夹内剩余成员 <2 时解散该夹（成员全部回桌面、按类别重新参与排布；夹行本身删除，复用既有删夹机制）。

### 3. 槽位顺序与铺页

`AutoArranger.arrange()` 流程改为：

1. 计算 pinned 集合并执行抽取（含解散判定）。
2. 其余桌面项按类别分组建槽（现有逻辑，pinned 已排除）。
3. 槽位顺序：**所有文件夹槽位（新建 + 既有，按类别顺序）→ 钉选单项（按包名列表顺序）→ 其余槽位**。
4. 铺页逻辑不变（顺序铺、只读项占位、放不下回原位）。首张未删除页从左上角开始自然形成「夹 → 钉选 → 其它」。

### 4. 统计与预览

- `movedCount`：抽出的钉选成员原 container 非 `CONTAINER_DESKTOP`，走既有「originalContainer != CONTAINER_DESKTOP 即移位」分支，自动计入。
- 预览对话框无需改动；解散的既有夹与新建夹数互不干扰（解散夹不计入 newFolderCount）。

### 5. 测试（JVM 单测）

- XP 类别：映射表关键词/包名命中归 XP；≥4 建夹；<4 散放；续编号「XP插件 2」。
- 钉选：包名命中按序铺在夹后；缺失跳过；不参与分类与建夹。
- 抽取：从既有夹抽出钉选项；抽空后剩 1 成员解散夹、成员回桌面；Dock 中钉选项抽到首页。
- 顺序：槽位顺序「夹 → 钉选 → 其它」在铺页结果中体现。

## 非目标

- 钉选列表不做用户配置 UI（改代码即改列表）。
- 不改 Dock、不改抽屉页、不做按页配额。

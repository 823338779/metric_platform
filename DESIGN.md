---
name: Metric Platform
description: 以资源阅读、直接选择和连续上下文为核心的专业双工作台
colors:
  primary: "#2859c5"
  primary-hover: "#4b7ad1"
  primary-active: "#183e9e"
  ink: "#202d40"
  heading: "#18263d"
  muted: "#58677b"
  placeholder: "#66758b"
  rail: "#172638"
  rail-hover: "#24374e"
  rail-text: "#c5d0df"
  ground: "#f5f6f8"
  surface: "#ffffff"
  line: "#e2e7ee"
  control-border: "#d9d9d9"
  secondary-border: "#e7ecf3"
  inset: "#f8fafc"
  native-inset: "#f6f8fb"
  library: "#f1f3f7"
  rail-selected: "#2a4263"
  tree-selected: "#e8efff"
  tree-selected-text: "#315fd3"
  dependency-head: "#eef4fe"
  dependency-border: "#cbd9f1"
  dependency-hover: "#eaf0fc"
  type-bg: "#e6f4ff"
  type-text: "#0958d9"
  analysis-canvas: "#f8f9fc"
  analysis-selected: "#edf2ff"
  analysis-selected-text: "#244b9f"
  analysis-selected-border: "#c4d3f3"
  analysis-chip-text: "#4b5c76"
  analysis-field-border: "#d7dfeb"
  analysis-search-border: "#b7c4d7"
  analysis-metric-selected: "#e7eefc"
  analysis-chip-hover: "#f4f7fd"
typography:
  headline:
    fontFamily: "'Segoe UI', 'Microsoft YaHei', sans-serif"
    fontSize: "35px"
    fontWeight: 650
    lineHeight: 1.3
    letterSpacing: "-0.4px"
  headline-narrow:
    fontFamily: "'Segoe UI', 'Microsoft YaHei', sans-serif"
    fontSize: "24px"
    fontWeight: 650
    lineHeight: 1.3
    letterSpacing: "-0.4px"
  catalog-heading:
    fontSize: "28px"
    fontWeight: 650
    lineHeight: 1.35
  catalog-heading-narrow:
    fontSize: "22px"
    fontWeight: 650
    lineHeight: 1.35
  catalog-resource:
    fontSize: "13px"
    lineHeight: "22px"
  catalog-resource-meta:
    fontSize: "11px"
    lineHeight: "18px"
  section:
    fontSize: "20px"
  body:
    fontFamily: "'Segoe UI', 'Microsoft YaHei', sans-serif"
    fontSize: "14px"
    lineHeight: 1.5714285714285714
  description:
    fontSize: "16px"
    lineHeight: 1.7
  shell:
    fontFamily: "'Segoe UI', 'Microsoft YaHei', sans-serif"
    fontSize: "14px"
  label:
    fontSize: "12px"
  code:
    fontFamily: "Consolas, 'Microsoft YaHei', monospace"
    fontSize: "13px"
    lineHeight: 1.85
  management-heading:
    fontSize: "27px"
    fontWeight: 650
    lineHeight: 1.35
    letterSpacing: "-0.5px"
  analysis-heading:
    fontSize: "24px"
    fontWeight: 650
  analysis-section:
    fontSize: "16px"
    fontWeight: 600
  analysis-choice:
    fontSize: "13px"
  metric-name:
    fontSize: "14px"
    fontWeight: 550
    lineHeight: 1.5
rounded:
  action: "5px"
  inset: "6px"
  dependency: "7px"
  control: "6px"
  panel: "12px"
  legacy-card: "8px"
  lineage: "9px"
  login: "12px"
spacing:
  tight: "4px"
  compact: "8px"
  small: "12px"
  medium: "16px"
  section: "20px"
  group: "24px"
  content: "30px"
components:
  button-primary:
    backgroundColor: "{colors.primary}"
    textColor: "{colors.surface}"
    rounded: "{rounded.action}"
    height: "42px"
  button-primary-hover:
    backgroundColor: "{colors.primary-hover}"
  button-primary-active:
    backgroundColor: "{colors.primary-active}"
  catalog-button-primary:
    backgroundColor: "{colors.primary}"
    textColor: "{colors.surface}"
    rounded: "{rounded.action}"
    height: "34px"
  catalog-context:
    backgroundColor: "{colors.inset}"
    width: "232px"
    padding: "20px 18px"
  button-secondary:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.ink}"
    rounded: "{rounded.action}"
    height: "42px"
  input:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.ink}"
    rounded: "{rounded.control}"
    height: "36px"
  workspace-tab:
    textColor: "{colors.muted}"
    padding: "0"
  workspace-tab-selected:
    textColor: "{colors.primary}"
  type-chip:
    backgroundColor: "{colors.type-bg}"
    textColor: "{colors.type-text}"
    rounded: "{rounded.inset}"
  project-card:
    backgroundColor: "{colors.surface}"
    rounded: "{rounded.panel}"
  dependency-node:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.ink}"
    rounded: "{rounded.dependency}"
  native-disclosure:
    backgroundColor: "{colors.native-inset}"
    rounded: "{rounded.inset}"
  analysis-builder:
    backgroundColor: "{colors.surface}"
    rounded: "{rounded.panel}"
  analysis-chip:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.analysis-chip-text}"
    rounded: "{rounded.action}"
    padding: "6px 12px"
    typography: "{typography.analysis-choice}"
  analysis-chip-selected:
    backgroundColor: "{colors.analysis-selected}"
    textColor: "{colors.analysis-selected-text}"
  analysis-selected-metric:
    backgroundColor: "{colors.analysis-selected}"
    textColor: "{colors.analysis-selected-text}"
    rounded: "{rounded.action}"
    padding: "9px 12px"
  analysis-metric-item:
    textColor: "{colors.ink}"
    rounded: "{rounded.inset}"
    padding: "11px 8px"
  analysis-metric-item-selected:
    backgroundColor: "{colors.surface}"
    textColor: "#234c9f"
---

# Design System: Metric Platform

## Overview

**Creative North Star: "专业双工作台"**

深蓝可收起导航、浅灰目录与白色阅读面构成稳定的工作环境。资源与分析共享项目、版本和当前资源上下文；视觉层级服务于长时间阅读、查找和操作。用户委托本次设计兼顾数据开发与分析，保留环境和构建能力，并突出资源与分析；本规范以代码实现和桌面、手机成品审查为依据。

本规范记录公共外壳、登录、环境与部署管理、资源目录和分析探索。管理页以对齐记录和分组表单组织信息；分析页集中选择指标、共同维度、时间和高级条件，运行操作与真实结果位于连续画布中。

**Key Characteristics:**

- 冷灰工作底与白色内容面，通过边界和间距区分职责。
- 蓝色集中标识当前工作区、可执行操作与选中资源。
- 中文保持自然阅读；资源标识、SQL 和 YAML 保留原始拼写。
- 定义、来源和依赖形成阅读顺序，原始属性按需展开。
- 已发布目录以两级资源树、主体阅读面和可收起版本上下文分工，维持固定版本。
- 指标库直接可选，高级条件默认展开且可收起；结果明确区分当前、运行中与条件已变更。

## Colors

色彩以冷灰中性色为主，工作蓝承担操作和定位语义。机器 token 以上方 frontmatter 为准。

### Primary

- **工作蓝**（`primary`）：主要操作、活动工作区下划线、链接、焦点和资源图标。
- **交互蓝**（`primary-hover`、`primary-active`）：由当前 Ant Design 主题算法生成的按钮悬停与按下状态，不另造第二套主题算法。
- **选择浅蓝**（`tree-selected`、`dependency-head`）：资源树选择和依赖节点标题面的背景；两者保留现有不同用途。
- **类型浅蓝**（`type-bg`、`type-text`）：Ant Design 蓝色类型标签。分类文字必须与真实数据类型分别呈现。
- **分析选择蓝**（`analysis-selected`、`analysis-selected-text`）：已选指标与选项按钮使用同一浅蓝语义；旧查询页指标库条目使用 `analysis-metric-selected`；当前查询页采用白色选中面与蓝色边框，以文字、勾选标记共同传达状态。

### Neutral

- **深蓝导航**（`rail`）：全局可收起侧栏；正文深墨色使用 `ink`，不能把栏底色当作正文 token。
- **冷灰工作底**（`ground`）与 **白色阅读面**（`surface`）：分别容纳目录、工具及主体内容。
- **正文灰**（`muted`）与 **提示灰**（`placeholder`）：分别用于辅助说明与可用输入框占位文字；提示灰对白底的对比度约为 4.69:1。
- **边界灰**（`line`）：区域分隔；表单边框使用控件主题 token，避免凭装饰需要强化所有边线。
- **查询画布灰**（`analysis-canvas`）：衬托白色条件面；搜索与日期字段采用 `analysis-search-border`、`analysis-field-border`，保留清楚的可编辑边界。

**The 当前上下文 Rule.** 同一蓝色语义连续用于导航、选择和动作；项目及版本必须能被用户识别，不能靠颜色暗示未确认的状态。

## Typography

中文和界面控件统一使用 Segoe UI、Microsoft YaHei 与 sans-serif 系统字体栈，不声明未加载的 Inter。技术源码使用 Consolas 与等宽回退；资源源码区保留既有的 Microsoft YaHei 回退。

legacy 资源名采用 `headline`，窄屏切换 `headline-narrow`；已发布目录使用更紧凑的 `catalog-heading` 与 `catalog-heading-narrow`。两级资源树名称使用 `catalog-resource`，其来源或分类副行使用 `catalog-resource-meta`；上下文标题为（14px、600），字段标签为（12px），字段值为（13px、1.7 行高）。定义与依赖区标题使用 `section`。正文控件使用 `body`，资源说明使用较舒展的 `description`。外壳主要使用 `shell`，版本与辅助信息使用 `label`。源码采用 `code`，保持行号、缩进和可复制性。

分析页面标题采用 `analysis-heading`，条件与结果标题使用 `analysis-section`。选项按钮使用 `analysis-choice`，指标库名称采用 `metric-name`，说明以较小字号分两行呈现。当前查询页窄屏标题为（22px），条件标题保持（16px）；不把资源详情的大标题规格套入查询条件。

**The 原文可读 Rule.** 保留技术标识大小写；详情中的长名称可以换行，目录行可以省略，但仍须提供定位或完整阅读入口。

## Layout

桌面外壳为全高两列：全局导航展开（192px）、收起（64px），主内容可收缩；不超过 1199px 的初次加载默认收起，用户可手动切换。页头高（56px）；资源树初始宽（268px），分隔条（4px），拖动范围（220–480px），键盘左右调整步长（16px）。管理页留白（36px 40px），内容最大宽（1400px）；1199px 以下留白（28px），760px 以下为（24px 16px）。

legacy 资源阅读面以内容内边距（30px）展开：编辑页签、资源名与操作、定义、依赖与来源、原始属性。定义在宽屏使用两列；依赖摘要以两端节点和中间连线表达关系。宽度不超过 1100px 时，定义改为单列，标题动作允许换行，来源摘要纵向排列。

宽度不超过 760px 时，全局栏缩至（48px）；资源树和内容纵向排列，移除宽度拖动条。legacy 树区可滚动，高度上限（230px）；内容横向留白（16px），标题缩小，依赖摘要改为纵向，箭头随方向旋转。技术表格与完整血缘画布保留各自的横向滚动，不能把整个页面撑宽。

### 已发布资源目录：上下文侧栏

本节以当前 `catalog-page.css`、`DbtCatalogPage`、`PublishedCatalogPage` 与资源树、详情组件为准；本轮以代码直接构建成品，并检查桌面和手机截图。旧效果图及 `.impeccable/build/` 历史截图不作为当前尺寸或验收依据。上述 legacy 阅读面尺寸只适用于旧入口。

`PublishedCatalogPage` 的尺寸以本节为准。全局页头下方是仓库、分支、环境工具栏，最小高（54px）、内边距（10px 24px）；右侧集中刷新与版本上下文开关。桌面目录工作区高度为（100dvh − 56px），最小高（480px）。主体沿用可调目录和（4px）分隔条，右侧上下文宽（232px），关闭后空间归还阅读面。目录列表、阅读面和版本侧栏分别纵向滚动；上下文使用冷灰嵌入面与左侧细分隔线。

阅读面顶部保留当前文件与关闭入口，最小高（40px）；标题内边距（28px 32px 12px），内容区内边距（20px 28px），定义始终单列。标题与动作允许换行，避免目录和版本侧栏挤压正文。

视口宽度不超过（1199px）时，版本上下文默认收起；从桌面缩窄也会收起。用户展开后，上下文位于目录工作区上方，最大高（300px），四个主要字段按四列排列；工具栏信息可换行。重新加宽不会强制重新打开用户已收起的上下文。

宽度不超过（760px）时，页面恢复自然文档高度，目录与阅读面纵向排列；目录整体最高（440px），可滚动树列表高（100–190px）。工具栏操作换到下一行，目录筛选最小高（34px）；标题横向留白与正文内边距均为（16px），上下文字段改为两列。版本开关保持可用，展开的上下文仍在工作区上方。

间距使用 frontmatter 中的已有步长；详情的宽留白与目录的紧凑行距是不同阅读任务的需要，不应强行统一密度。

当前分析工作台使用指标库（236px）与查询画布两列；1100px 以下库宽（208px），画布留白降至（20px）。大于 1000px 时指标库保持在视口内，候选列表独立滚动。条件面按指标、维度、时间、高级条件和操作栏组织，结果位于其下；标签列（72px），行内边距（12px 22px）。

760px 以下分析区上下排列，指标列表最大高（220px），画布留白（20px 12px）；条件行变为单列，日期输入上下排列，操作栏自然换行。操作栏保持正常文档流，结果表在自身容器内横滚。旧查询入口保留 260/230px 指标库及 92/76px 条件标签列，不覆盖当前工作台。

登录页采用深蓝介绍与白色表单等分两列，表单最大宽（420px）；760px 以下纵向排列并精简介绍内容。环境和部署列表使用可独立横滚的记录表；配置表单按用途分组，桌面三列、手机单列；构建提交在 900px 以下改为单列。

## Elevation & Depth

主要层次来自冷灰底、白色面、浅蓝选中面和细分隔线。常规工作区不依靠大阴影悬浮。完整血缘画布的节点保留微弱阴影（`0 3px 8px #25334a08`），选中节点以浅蓝外环（`0 0 0 3px #e4edff`）突出；这些只属于画布节点。Ant Design 弹层延续组件库的默认层级。

**The 工作面稳定 Rule.** 工作区保持平面且位置稳定；依赖按钮的悬停只改变背景和边框，不进行位移。

## Shapes

全局工作区与目录内容拼接处采用直边；圆角用于独立控件、编辑页签和节点。资源标题动作使用 `action`，嵌入区域和页签上角使用 `inset`，依赖摘要使用 `dependency`，通用表单使用 `control`，管理面板、主题卡片和当前查询条件面使用 `panel`。完整血缘节点使用 `lineage`；旧卡片保留 `legacy-card`，登录表单卡片透明、无边框与阴影。

图标使用现有 Ant Design SVG 图标；连线和画布网格表达关系与空间，不替代真实依赖数据。

## Components

### Buttons

主操作为工作蓝实底，次操作为白底边框。legacy 资源标题区按钮高（42px），桌面字号（16px），主要动作最小宽（160px），次动作最小宽（142px）；窄屏最小宽（110px）、字号（14px）。已发布目录标题动作使用 `catalog-button-primary` 的高度、字号（13px），不设最小宽度。可查询物理资源的“预览数据”与可查询指标的“开始分析”分别承担主操作，“查看源码”为次操作；从源码或血缘点击预览会切回概览展示加载与结果。其他表单控件沿用主题高度（36px），不把 legacy 标题区规格扩散到所有按钮。

公共键盘焦点为工作蓝实线（2px），外偏移（3px）。组件库负责按钮 loading、disabled、hover 与 active 状态；不要用降低整页透明度表达局部等待。

### Inputs / Fields

白底、主题边框和通用圆角，输入文字使用正文墨色，占位使用提示灰。搜索区预留右侧工具空间；定位、展开和折叠控件保留可访问名称。输入和文本区光标采用工作蓝。

### Navigation

资源管理和分析探索是同级工作区入口。活动标签同时使用蓝字、字重和底边线；切换携带当前项目、版本与资源上下文。深蓝全局栏使用 SVG 图标、提示与可访问名称，当前项采用深蓝选中面。窄屏保留导航能力，减少辅助账号和版本文字。

### 资源目录 / 版本上下文

目录仅保留两级：按类型为“类型 → 资源”，按来源为“来源文件 → 资源”。来源分组以文件名为主行、目录路径为副行；资源副行在按类型时显示来源，在按来源时显示分类。两种分组共享当前资源与筛选范围；搜索或类型筛选期间保持分组偏好，列表骨架不卸载分组工具。分组默认展开，支持全部展开、全部折叠和定位当前资源；定位会展开所属分组并将选中行滚入可见区。目录名称可省略，悬停可读完整名称、来源与说明。

搜索、类型筛选、分组和树操作依次排列。类型筛选使用蓝色选中面、边框与字重，同时提供 tab 语义及方向键、Home、End 导航；筛选可改变目录范围，分组只改变层级组织。筛选按钮桌面最小高（30px）、字号（12px）；树缩进（12px），展开控件宽（18px），资源行内容内边距（6px 4px）。底部显示真实已加载数量并按需加载更多。

目录上下文在桌面默认展开，常显仓库、分支、环境和固定版本摘要，完整仓库地址、构建 ID、提交 SHA 与配置版本位于“版本详情”折叠项；完整仓库、构建和提交支持复制。构建及部署历史按需展开，分页独立追加。工具栏开关以 `aria-expanded` 表达状态；通过侧栏关闭按钮收起后，焦点返回工具栏开关。

目录、定义、血缘与源码始终指向当前固定构建。新部署只显示提示与明确切换动作；刷新、分组和打开上下文不得静默替换版本。切换构建后重新选择资源；查询不可用时目录与历史继续可读。

### Chips

资源标题中的类型标签为小号、无边框的蓝色标签，不能与数据库数据类型合并。颜色辅助分类，文本承担准确含义。

分析选项为有边框、可换行的按钮，最小高（34px），使用 `analysis-chip`；选中状态使用浅蓝面、蓝字、边框和 `aria-pressed`。维度支持多选，粒度和单一字段支持单选；再次点击已选项可以清除，调用页面可保留必需字段的默认值。候选超过 10 项时提供就地搜索，禁用项保留不可操作状态和语义。图表的指标、横轴与系列维度复用相同选择形式。

### Metric Library / Selected Metrics

指标库支持名称与描述搜索。每一指标行以 checkbox 语义表达加入状态，名称允许换行，描述最多显示两行；选中使用 `analysis-metric-item-selected`。旧查询条件面以 `analysis-selected-metric` 显示已选指标；当前查询页保留可编辑的选择标签与独立移除操作。移除按钮提供明确的可访问名称。展示名称与 API 身份分离，不能根据标签文字重新构造查询身份。

### Time / Advanced Conditions

时间提供不限时间、近 7 天、近 30 天和本月按钮，并保留本地日期时间输入。选择预设会更新输入值，手动编辑会清除预设高亮。当前查询页的筛选与排序使用原生 `details`，默认展开且可收起；旧查询入口默认折叠并在摘要显示配置状态。字段、操作符、值与排序方向根据选择呈现。候选和时间粒度以固定版本 API 能力为准。

查询条件底部的横向操作栏包含返回行数、查看 SQL 与运行查询；按钮高（36px），字号（13px）。运行结果在条件面下方呈现，未运行时用简洁 SVG 图标、任务说明和下一步提示，不填充示例结果。

### service-proxy 查询工作台

`PublishedWorkbench` 使用浅灰画布、白色圆角条件面和紧凑分区。标题栏保留刷新；查询版本详情默认收起，摘要显示分支和环境。维度值、查看 SQL 和蓝色运行按钮集中在条件区底部。身份读取失败、查询不可用、新部署提示和错误始终位于折叠区域之外。

已选项使用浅蓝灰边框标签，编辑与移除为独立操作。加号打开可搜索列表；首次分组提供可收起的候选列表。时间维度直接在标签内切换服务允许的粒度。维度列表展示当前固定版本声明或直接列映射得到的数据类型；类型缺失时明确标示待确认，不把业务分类当成数据库类型。排序允许添加多个当前已选指标或分组维度，点击标签箭头切换方向，按标签顺序提交优先级，字段失效时同步移除排序。

窄屏中标签换行、菜单保持在视口内；菜单支持搜索、键盘选择和 Escape 关闭。候选类型及当前时间粒度同时提供给辅助技术读取。

### Cards / Containers

管理面板和主题卡片为白底、细边框及 `panel` 圆角；环境与部署以对齐记录呈现，配置按用途分组，构建历史保留日志和操作入口。资源详情主体直接位于阅读面；定义以分隔线和字段行组织，不为每个值套一层卡片。

### Dependency / Source

依赖摘要节点分为浅蓝标题面和白色说明面，名称允许换行；可跳转节点悬停改变边框与背景（150ms ease-out）。完整血缘图在独立可滚动画布中支持缩放、拖动和选中检查。源码保留文件名、固定提交、行号与定义高亮。

### Disclosure / Feedback

原始属性使用原生折叠控件，默认收起，展开后代码区域独立滚动。加载、空内容、失败及历史版本使用真实状态文案和现有恢复操作；未查询时只显示任务提示，不展示默认纸箱插画或虚构结果。结果区以文字区分当前条件、查询运行中和条件已变更，并保持结果所属构建可见。系统减少动效偏好开启时关闭非必要动画与平滑滚动。

## Do's and Don'ts

### Do:

- **Do** 沿用深蓝导航、冷灰工具面和白色阅读面的职责分层。
- **Do** 保留同级工作区入口、清楚的当前版本和键盘可见焦点。
- **Do** 把业务说明、分类和真实数据类型分开，让长资源名有完整阅读路径。
- **Do** 以真实接口内容建立定义、依赖和来源，保留空状态与错误恢复操作。
- **Do** 让分析候选项和已选状态直接可见，使用就地搜索与可移除标签控制大量选项。

### Don't:

- **Don't** 把虚构数量、账号、精度或结果写入产品。
- **Don't** 为技术标识强制大写，或以装饰字体降低中文和代码的可读性。
- **Don't** 用层层卡片或长篇原始序列化属性取代定义摘要。
- **Don't** 用固定或悬浮动作条遮住字段，也不要让画布宽度造成页面横向溢出。
- **Don't** 把常用的指标、维度、粒度和图表字段重新藏进一串下拉框；复杂筛选与排序允许收起。

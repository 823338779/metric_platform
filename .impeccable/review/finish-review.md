disposition: fix

输入限制：未提供独立 QUALITY BAR 卡；未审查实时后端或未提供的页面状态。已手动加载 finish-reviewer 指令，由独立子代理审查；未启动浏览器、未编辑产品文件。

## persistence

pass：PRODUCT.md、方向与 surface brief、spec.json、state.json 均存在。comps/spec/plates/hero 均 closed，无 forced；批准记录指向 dual-balanced.png；hero 分数 0.7532，高于 0.72。FORM 种子 b685865b 与 state.direction 一致。spec 的全部区域为 semantic，无欠交栅格素材。DESIGN.md 尚不存在，属于本轮收尾文档工作。

七张必需截图完整有效，包含 desktop、mobile、user-1264、analysis-desktop、published-desktop、published-analysis、hero-repro；重新读取了 13:50:05 的两张 published 截图，查询与查看 SQL 已启用。补充读取 tmp/metric-redesign-lineage.png、metric-redesign-source.png、metric-redesign-mobile.png。均为明确说明的 Playwright 合成数据证据，不能据此宣称实时服务已验证。

responsive 仍 open，自动 gate 为 false。final report 时间 13:48:20 早于当前 desktop 13:48:32，修复后需同步证据；不得把独立目视裁定写成自动检查通过。

## fidelity

先读 comp 得到的构图清单：深蓝窄导航、浅灰资源树、项目与搜索工具、两个等权工作区入口、编辑页签与面包屑、大资源名与右侧动作、定义双列、横向依赖、来源链接及折叠属性。以下以该清单而非构建摘要评判。

| 元素 | 判定 | 证据与依据 |
| --- | --- | --- |
| rail | adaptation | 保留深蓝窄栏和当前工作区选中；仅显示产品已有导航，符合 FORM 不虚构能力。 |
| project | match | 项目位于树顶，文本可辨识。 |
| search | adaptation | 增加全部展开/折叠和定位控件，依据用户 IDEA 式目录要求。占位对比度另列修复。 |
| tree | adaptation | 来源文件层级、树线和类型筛选符合用户的目录导航要求；未复制效果图虚构数量。 |
| workspaces | match | 资源管理与分析探索同级、相同视觉权重、活动下划线。 |
| editor-tab | match | 当前资源编辑页签保留。 |
| breadcrumb | adaptation | final 报 missing，但 paired crop 右侧确有完整项目/类别/名称，仅基线下移和字号缩小；当前整图完整，属于视口转换，非缺失。 |
| title / description | adaptation | 保留大资源技术名、清楚的说明层级；系统正文和不改变标识大小写依据 FORM。 |
| actions | match | 蓝色开始分析与边框源码动作保留且可读。 |
| detail-tabs | match | 概览、血缘、YAML 源码顺序与结构保留。 |
| definition | adaptation | 数据类型与业务分类分开；不制造精度字段；依据 PRODUCT 的真实类型约束和 FORM 的接口为准。 |
| dependency-title | adaptation | final 报 missing；paired crop 显示标题下缘，整图显示完整标题和右侧入口，属于裁剪的纵向映射偏差。 |
| dependency | adaptation | final 报 missing；paired crop 清楚含两节点与箭头，当前整图完整；来源列类型未获证实时显示上游资源，依据 FORM。 |
| source | adaptation | final crop 命中节点底边，整图 y≈820 显示完整来源行；资源级来源替代未证实字段级来源，依据真实接口边界。 |
| native | adaptation | final crop 含前一来源行而未取到完整折叠属性；整图 y≈912 显示完整控件，且移动端也存在。 |
| TYPE | adaptation | 与 comp 相同的正常宽度无衬线资源名、明显主次级别；没有将标识转换为小型大写。系统字体符合已记录 FORM，不要求用 spec 推荐的 Alegreya Sans SC 改变技术名。 |
| MATERIAL | match | comp 为语义工作台界面；白面、灰树、浅蓝节点以真实界面构建。无应交付而被 CSS 替换的照片或有机素材。 |
| GROUND | match | final 调色板 comp/build 主场同为 #F7F8F8，窄栏分别 #283858/#283857；源码白内容面和 #F3F5F8 树面符合 FORM，未偏向暖奶油。 |
| 分析工作区 | adaptation | 独立设置列与结果区符合用户选择的等权双工作区；未运行时不虚构结果。底部遮盖与默认插画另列修复。 |
| 完整血缘与源码 | match | 4 个血缘节点不重叠，箭头方向清楚，长名省略；源码有文件名、固定提交、行号及定义高亮。窄屏放大截图是可平移画布状态，不能误判为页面横向溢出。 |

五条契约：THESIS、OWN-WORLD、STORY、FIRST VIEWPORT 与 FORM 的主体承诺可见；首屏可记住“左树、右定义、横向来源、同级分析入口”。不因 final 的 5 个裁剪误报要求移动已经完整存在的元素。

## ceiling

未提供独立 QUALITY BAR，无法声称已达到该卡上限。现有 IDE 原生手段包含导航层级、树线、分隔线、固定版本、语法高亮、可缩放血缘；本轮无需新增装饰、资产或动画。查询工作区的默认插画及操作条覆盖字段削弱了与资源工作区一致的完成度。

## material_fixes

1. [Floor/Contrast] 在 frontend/src/main.tsx 的主题 token 明确设置 colorTextPlaceholder，并覆盖需要的原生占位样式：desktop 搜索占位文字实测 #BFBFBF 对 #FFFFFF 约 1.84:1；使可用输入框占位达到 4.5:1，统一复核两个分析页的选择与筛选占位。
2. [Floor/Real illustration or none] 在 frontend/src/features/dbt/DbtQueryPage.tsx 与 PublishedWorkbench.tsx 的未查询 Empty 状态去掉默认带阴影纸箱插画，保留现有清楚的状态文字；使用 image={false} 或等效无图呈现，无需生产新资产。
3. [FIRST VIEWPORT/查询可读性] 在 frontend/src/workbench.css 及两个查询页将字段滚动区与底部动作区分开：现有 sticky 操作条把 legacy 的“最多返回行数”和 v2 的“结束时间/行数”输入覆盖成细边；让字段在动作条上方的明确边界内滚动，操作条独立占位，验证滚到底后各输入完整可见。
4. [Persistence/Evidence] 修复后覆盖同名截图并重做 final/hero 所依赖的差分，保留 responsive gate 的实际结果及本审查对五个 missing 的逐项裁定；当前不得宣称自动 responsive gate 已通过，也不要为了阈值改写真实界面或扩大缺失区域定义。

## keep

保留等权双工作区、深蓝窄栏/浅灰树/白内容面的组合、真实类型与业务分类分离、可读且可跳转的血缘、固定版本源码及原始属性折叠。

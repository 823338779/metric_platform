---
version: 1
slug: "frontend-src-routes-tsx"
primary_target: "frontend/src/routes.tsx"
related_targets: ["frontend/src/layout/PlatformLayout.tsx", "frontend/src/global.css", "frontend/src/features/dbt/PublishedWorkbench.tsx", "frontend/src/features/dbt/catalog-page.css"]
---

# 双工作台 2.0：资源与分析

模式：Operate。用户委托助手设计，明确兼顾数据开发与分析、保留环境和构建、突出资源与分析。依据本次实施计划和代码直接构建的成品，不沿用历史效果图作为当前视觉授权或验收基准。

## THESIS
让执行环境、构建和部署管理支撑资源浏览与分析；在固定构建内保留连续上下文，让用户清楚当前资源、条件与结果分别属于哪个版本。

## OWN-WORLD
深蓝带文字的可收起导航、浅灰工具面、白色阅读与操作面。环境和部署采用对齐记录，配置按用途分组；资源与分析保留同级入口。

## STORY
选择环境并提交构建，查看部署版本，进入资源目录阅读定义和来源，携带固定版本进入分析，再根据结果回查口径。已有发布版本可直接进入资源与分析。

## FIRST VIEWPORT
全局导航展开 192px、收起 64px、手机 48px，页头 56px。目录初始树宽 268px；桌面版本侧栏宽 232px，窄屏默认收起。分析页指标库宽 236px（1100px 以下为 208px），条件、运行操作与结果连续排列；查询版本详情默认收起，高级条件默认展开且可收起。身份读取失败、查询不可用和错误不藏在折叠区内。

## FORM
工作蓝 #2859c5、正文墨色 #202d40、辅助灰 #58677b、工作底 #f5f6f8、导航深蓝 #172638。统一使用 Segoe UI、Microsoft YaHei 与 sans-serif 系统字体栈；技术名称保持原文。页面使用 React、Ant Design、语义 HTML、CSS 和现有 SVG 图标，无新增栅格图片或字体资产。数量、身份、依赖和结果取自接口，协议测试数据仅用于验证。

## FINISH
本轮独立审查覆盖 14 张桌面与手机截图，身份读取警告的折叠可见性问题已修复并复核，最终结论为 ship。全量 159 项前端测试和 TypeScript/Vite 生产构建通过；这些验证不替代真实服务联调。当前系统规范与组件预览同步至 DESIGN.md 和 .impeccable/design.json。

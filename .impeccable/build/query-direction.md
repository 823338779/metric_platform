# 分析工作台交互改造

## THESIS
用户最新明确要求“查数页面好看并且好交互，不要使用很多下拉框”。沿用已批准双工作台 B 的视觉系统，让选择过程直接可见。

## OWN-WORLD
同一套深蓝窄导航、冷灰指标库和白色查询画布。左侧搜索与多选指标，右侧配置问题并阅读真实结果。

## STORY
搜索指标、点击加入、点选共同维度、选择时间范围、按需配置筛选排序、运行查询、调整图表或查看 SQL。

## FIRST VIEWPORT
260px 指标库与灵活查询画布并列；已选标签可以移除，分组维度和粒度直接点击，常用时间范围一键选择，筛选排序默认折叠。结果区域在查询条件下方；移动端上下排列，数据表独立横向滚动。

## FORM
这是已批准双工作台的交互改造，不另建视觉世界。资源管理的 B 效果图仍是公共配色与导航参考，不作为查数页逐像素布局目标。全部候选项、资源身份、粒度、类型和结果来自发布 API；截图为标注演示账号的测试数据。固定 releaseId、资源和选项身份、精确数值字符串及 SQL 解释能力保持。

## FINISH
unreviewed and undocumented is unfinished; this build ends with the finish review, the verdict, DESIGN.md, and every shipping raster carrying its provenance

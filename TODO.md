# 10 项产品优化 — 任务跟踪

> Plan: `/home/dev/.claude/plans/refactored-splashing-locket.md`
> 每个 PR 完成后按 feedback 推 origin main。

## PR 列表

- [x] **PR-0** 共同前置：异步执行 + Pinia + 草稿 + 网络重试 + DB 迁移 + TaskCenterDrawer + ExecuteDrawer 骨架
- [ ] **PR-1** 租户：合并配置 + Keytab + 认证测试为 1 个抽屉（含 authName）
- [ ] **PR-2** 任务中心 + 异步执行接入（含 INTERRUPTED 状态）
- [ ] **PR-3** 参数草稿 + 来源徽章（4 来源切换）
- [ ] **PR-4** ExecuteDrawer 完整实现（参数+结果同屏、log-tail、executionId 切换）
- [ ] **PR-5** 快捷操作（新表 + 首页区块 + ExecuteDrawer 集成）
- [ ] **PR-6** 脚本编辑：实时预览 + 就地试运行
- [ ] **PR-7** 结果页：摘要 + 失败行动
- [ ] **PR-8** 批量：Excel 粘贴 + 逐行校验 + 失败行重试
- [ ] **PR-9** 历史重跑：双模式 + 版本差异
- [ ] **PR-10** ⌘K 全局查找

## 实施顺序

PR-0 → 1 → 2 → 4 → 5 → 3 → 6 → 7 → 8 → 9 → 10
（PR-4 是依赖 2/3 的，后续按用户顺序 3/6/7/8/9/10）

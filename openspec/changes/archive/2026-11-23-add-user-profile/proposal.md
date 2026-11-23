## Why

LiveFlow 的已登录页面目前只展示账号信息。用户需要一个可以查看和编辑本人资料的个人主页，作为后续「我的直播间」的入口。

## What Changes

- 将 `/` 的已登录视图提取为个人主页，支持查看、编辑、保存和取消，保留现有登录、恢复与退出流程。
- 增加本人资料的读取和更新接口，允许修改昵称、选择预设头像和填写简介，沿用当前账号身份和会话校验。
- 增加 `user_profiles` 表。已有账号可以直接读取默认资料，首次保存创建资料记录。
- 在本次变更中交付 Vue 页面的完整交互、后端接口和 MySQL 迁移。产品范围见 `docs/design/profile/design.md`。

## Capabilities

### New Capabilities

- `user-profile` 提供登录后的个人主页、本人资料读写、默认资料及更新失败时的页面行为。

### Modified Capabilities

## Impact

- `liveflow-core` 增加资料业务、Mapper 和 Flyway V2 迁移。
- `liveflow-api` 增加 `GET` 和 `PUT /api/v1/users/me/profile`，通过现有 Spring Security 过滤链接入。
- Vue 增加个人主页、预设头像与资料编辑，复用现有认证状态和同源请求规则。

## Why

LiveFlow 已支持创建和查看本人房间。独立的房间地址让房主与观众可以进入同一页面，并为后续播放和弹幕提供稳定入口。

## What Changes

- **BREAKING** 创建和查询本人房间响应中的 `roomId` 从十进制字符串改为标准小写随机 UUID v4。已有房间回填 UUID，接口与 Vue 同步切换。
- 增加需要登录的房间详情接口和可直接打开、分享或刷新的房间地址。未登录用户在原地址完成登录后进入。
- 在个人主页增加进入按钮。房间页提供标题、返回、复制链接，以及响应式画面区和弹幕区。播放和实时互动归入后续阶段。

## Capabilities

### New Capabilities

### Modified Capabilities

- `room-creation` 增加稳定的对外 UUID、房间详情查询、个人主页入口和房间页行为规则。

## Impact

- `liveflow-core` 增加房间 UUID 的 Flyway 增列、回填和约束迁移，调整创建事务及查询。
- `liveflow-api` 调整已有房间响应，增加受保护的详情路由。
- Vue 调整房间客户端与认证后的页面分流，增加房间详情页面及分享入口。 [已确认的房间设计](../../../../docs/design/room/design.md)

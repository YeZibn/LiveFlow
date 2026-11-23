## Why

LiveFlow 已支持通过 UUID 打开直播间。需要让同一房间的多个已登录用户能在现有页面发送并实时看到纯文本弹幕，同时明确断线、重复提交和会话切换的行为。

## What Changes

- 增加房间消息提交和短期 WebSocket Ticket 两条 HTTP 接口。接收连接使用 `/ws/rooms`，绑定登录会话和房间。
- 在单实例内按房间广播并隔离慢连接，按账号限制发送频率，在短窗口内对相同提交返回原结果。
- 完善 Vue 房间页弹幕面板，支持连接状态、纯文本输入、发送结果、重连和响应式消息列表。

## Capabilities

### New Capabilities

- `room-messages` 定义弹幕提交、连接鉴权、同房间分发、短期去重和页面交互。

### Modified Capabilities

- `room-creation` 在既有房间页以可收发的弹幕面板替换弹幕占位区，保留房间加载和分享流程。

## Impact

- `liveflow-api` 增加 MVC 接口、WebSocket 入口及本机连接管理，复用现有 HTTP 安全边界。
- `liveflow-core` 实现房间和正文规则、账号额度、接受结果和广播端口。 `liveflow-infrastructure` 提供 Redis Ticket 仓储，复用当前会话校验。
- Vue 增加弹幕面板及请求逻辑，覆盖桌面和手机。
- 首版消息状态保存在单个实例内。滚动弹幕、历史查询、跨实例广播和消息持久化归入后续阶段。 [弹幕 V1 设计](../../../../docs/design/room-messages/design.md)

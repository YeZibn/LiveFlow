## Why

LiveFlow 已提供账号认证和个人主页。创建并持久化自己的直播间是用户使用房间功能的第一步，也为后续直播与弹幕提供稳定的房间标识。

## What Changes

- 登录用户可在个人主页创建和查看自己的直播间。每个账号最多一个房间，创建表单只要求房间名称，不同账号可使用相同名称。
- 提供创建与本人查询接口。服务端从认证身份取得房主，通过 MySQL 唯一索引裁决并发创建。
- Vue 在个人主页独立展示房间区，处理刷新恢复、重复创建、读取失败和提交结果不明，页面继续隐藏用户 ID。

## Capabilities

### New Capabilities

- `room-creation` 定义本人房间的创建、查询、唯一性和个人主页交互。

### Modified Capabilities

## Impact

- `liveflow-core` 新增房间 Service、Mapper 和 `V4__create_rooms.sql`。
- `liveflow-api` 新增房间 MVC 接口及错误映射，将两条路由接入现有认证规则。
- Vue 新增房间请求模块和「我的直播间」组件，复用现有认证与样式。
- 开播、房间公开访问、编辑删除、WebSocket 和弹幕归入后续阶段。 [直播间 V1 设计](../../../../docs/design/room/design.md)

## Why

弹幕面板目前只保留最近 100 条。活跃房间中的旧弹幕即使仍在 MySQL 保留期内，观众也无法从页面翻看。向上分页让观众能回看进房前或暂时离开时错过的弹幕。

## What Changes

- 进房先显示最近 100 条。用户接近列表顶部时加载上一页，每页最多 100 条，插入后保留阅读位置。
- 回看期间继续接收实时弹幕。用户可返回最新，重连补拉也会保留当前阅读位置和分页进度。
- 在现有历史 GET 增加 `beforeSeq` 和 `nextBeforeSeq`，使用户能逐页读取仍在 24 小时保留期内的已落库弹幕。

## Capabilities

### New Capabilities

### Modified Capabilities

- **`room-messages`** 增加历史向上分页契约，并扩展弹幕面板、实时合并和恢复规则。

## Impact

- `liveflow-api` 扩展历史请求校验和响应字段。 `liveflow-core` 增加历史倒序范围读取，复用 MySQL 主键和现有快照读取规则。
- Vue 弹幕面板处理向上加载、滚动锚点、失败重试和返回最新。发布顺序为后端在先，前端在后。
- [弹幕 V2 设计](../../../../docs/design/room-messages/v2/design.md#进房历史回看与重连) · [历史接口](../../../../docs/design/room-messages/v2/api.md#查询历史)

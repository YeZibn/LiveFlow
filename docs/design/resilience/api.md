# 单实例限流接口设计

## 接口与身份

三个入口沿用现有路由、请求字段和成功响应。来源 IP 取服务端可信连接地址，反向代理部署时按受信代理边界解析。弹幕账号和会话来自已验证的认证上下文，房间来自路径参数。 [认证接口](../auth/api.md) · [弹幕 V2 接口](../room-messages/v2/api.md)

| 方法和路径 | 请求 | 成功响应 | 超限 |
| --- | --- | --- | --- |
| `POST /api/v1/auth/login` | `username`、`password`，校验 CSRF | `200`，TokenResponse 和 Refresh Cookie | `429 AUTH_RATE_LIMITED`，新增 `Retry-After` |
| `POST /api/v1/auth/register` | `username`、`password`，校验 CSRF | `201`，`userId`、`username` | `429 AUTH_RATE_LIMITED`，带 `Retry-After` |
| `POST /api/v1/rooms/{roomId}/messages` | `clientMessageId`、`content`，校验 Bearer、当前会话和 CSRF | Broker 确认后返回 `202`、`Location`、`submissionId`、`status`、`retryUntil` 和 `receiptExpiresAt` | `429 MESSAGE_RATE_LIMITED`，带 `Retry-After` |

## 超限响应

`Retry-After` 是剩余毫秒向上取整所得的整数秒，最小为 1。登录的两个维度同时达到上限时，取两者中较长的剩余时间。限流错误体沿用 `code` 和 `message`，响应不可缓存。

```http
HTTP/1.1 429 Too Many Requests
Cache-Control: no-store
Retry-After: 7
Content-Type: application/json

{"code":"MESSAGE_RATE_LIMITED","message":"发送过快，请稍后再试"}
```

| 场景 | 响应 |
| --- | --- |
| 认证限流状态无法读取或确认 | `503 AUTH_SERVICE_UNAVAILABLE` |
| 弹幕冷却或预约状态无法读取或确认，尚未开始生产 | `503 ROOM_MESSAGES_UNAVAILABLE` |
| 同标识绑定异文 | `409 MESSAGE_ID_CONFLICT`，在冷却判定前裁决 |
| 已知预约仍在途，或已开始生产但写入结果未知 | 沿用 `503 MESSAGE_SUBMISSION_UNKNOWN` 及现有重试规则 |

## 页面交互

登录和注册按认证限流响应提示等待。弹幕收到 `429 MESSAGE_RATE_LIMITED` 后保留草稿，按 `Retry-After` 显示剩余时间。倒计时期间禁用新发送，原提交的手动重试和回执查询沿用原入口。页面禁用状态用于反馈，服务端按账号冷却做最终裁决。

## Purpose

让 LiveFlow 的已登录用户在个人主页创建和查询自己的直播间，确保归属稳定、每账号最多一间，并在刷新、并发操作或请求结果不明确时恢复正确的房间展示。

## ADDED Requirements

### Requirement: 登录用户创建本人房间

系统 MUST 提供 `POST /api/v1/rooms`，要求有效 Bearer 会话和 CSRF，按认证账号创建房间。请求 MUST 为只含字符串 `title` 的 JSON 对象。名称先拒绝 C0、C1 控制字符及 Unicode 行段分隔符，再按 Unicode White_Space 去除首尾空白，结果 MUST 为 1–40 个 Unicode 码点，保留内部空白与大小写。提交成功 MUST 返回 `201`、`Cache-Control: no-store` 以及房间对象，其中 `roomId` 为十进制字符串，`createdAt` 为带三位小数秒的 UTC ISO 8601 字符串，`title` 为规范化后的名称。

#### Scenario: 创建成功

- **WHEN** 当前账号没有房间，用户携带有效会话及 CSRF 提交合法名称
- **THEN** 系统返回 `201` 和已创建的房间，后续本人查询返回相同房间

#### Scenario: 拒绝无效创建请求

- **WHEN** 请求缺少必填字段、包含额外字段、字段类型错误、JSON 无效或名称不符合规则
- **THEN** 接口返回 `400 INVALID_REQUEST`，且请求没有创建房间

#### Scenario: 拒绝无效凭证

- **WHEN** 写请求的 Bearer 或当前会话无效，或认证用户的请求未通过 CSRF
- **THEN** 系统按现有契约返回对应的认证或 CSRF 错误，且请求没有创建房间

### Requirement: 一人一房与本人查询

系统 MUST 保证每账号最多一个房间。名称允许重复。重复创建 MUST 返回 `409 ROOM_ALREADY_EXISTS` 并保留原房间。系统 MUST 提供 `GET /api/v1/users/me/room`，仅查询当前有效会话对应账号的房间，返回不可缓存的 `200` 和 `room` 字段。房间存储不可用时 MUST 返回 `503 ROOM_SERVICE_UNAVAILABLE`，账号不存在时 MUST 按会话失效处理。

#### Scenario: 同账号并发创建

- **WHEN** 同一个尚无房间的账号用有效凭证并发提交两次合法创建
- **THEN** 正常存储条件下一个请求返回 `201`，另一个返回 `409 ROOM_ALREADY_EXISTS`，本人查询只返回获胜请求的房间且名称未被覆盖

#### Scenario: 不同账号同名与归属隔离

- **WHEN** 两个没有房间的账号分别提交相同名称并各自查询
- **THEN** 各自获得不同的房间标识，并仅查询到自己的房间

#### Scenario: 尚未创建及读取故障

- **WHEN** 当前账号没有房间，或者系统无法读取房间
- **THEN** 前者返回 `200` 和 `{"room": null}`，后者返回 `503 ROOM_SERVICE_UNAVAILABLE`

### Requirement: 个人主页房间操作与恢复

Vue MUST 在个人主页独立显示「我的直播间」，区分加载、空结果、已有房间和读取失败。空结果时提供仅含房间名称的创建表单，提交期间禁止重复操作，成功时显示返回的名称，页面隐藏用户 ID。切换会话后，旧请求的结果 MUST 无法更新当前页面。

#### Scenario: 成功后刷新或重新登录

- **WHEN** 用户创建成功并刷新页面或重新登录
- **THEN** 页面重新从本人查询结果展示已创建的房间

#### Scenario: 并发或未知结果后恢复

- **WHEN** 创建返回房间已存在，或提交超时、响应丢失而无法确认结果
- **THEN** 页面保留输入并先查询本人房间，查到时展示房间，明确查空时允许手动重试，查询失败时提供重新读取入口

#### Scenario: 读取与资料状态独立

- **WHEN** 进入个人主页后房间读取失败但个人资料加载成功
- **THEN** 页面展示个人资料，房间区显示读取失败与重试入口

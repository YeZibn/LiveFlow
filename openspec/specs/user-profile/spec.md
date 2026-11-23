# user-profile Specification

## Purpose

为 LiveFlow 已登录用户提供个人主页，让用户查看和管理自己的昵称、头像和个人简介，在不同登录会话间保留展示资料，并为后续直播间功能提供稳定的账号归属。

## Requirements

### Requirement: 查看本人资料与默认值

系统 MUST 通过 `GET /api/v1/users/me/profile` 向当前有效会话返回本人资料。响应包含字符串 `userId`、`username`、`nickname`、`displayName`、`avatarKey` 和 `bio`，并带有 `Cache-Control: no-store`。`displayName` MUST 在昵称为空时使用登录用户名。

#### Scenario: 已有账号首次进入主页

- **WHEN** 已登录用户首次读取本人资料，从未保存过昵称、头像或简介
- **THEN** 接口返回 `200`、当前账号 ID 与用户名、空昵称、空简介、`default` 头像及以用户名为值的 `displayName`，该次读取不产生持久化资料

#### Scenario: 查看已保存资料

- **WHEN** 当前用户重新加载主页或重新登录后读取已经保存的资料
- **THEN** 接口返回已保存的昵称、头像和简介，主页显示响应中的 `displayName`

### Requirement: 更新本人资料

系统 MUST 通过 `PUT /api/v1/users/me/profile` 接收含 `nickname`、`avatarKey`、`bio` 三个字符串的完整 JSON 对象，以认证身份确定归属。昵称去除首尾空白后 MUST 为空字符串或 2–24 个 Unicode 码点，允许重名，拒绝换行和控制字符。头像标识 MUST 精确匹配 `default`、`blue`、`green` 或 `orange`。简介将换行统一为 LF 并去除首尾空白，规范化后的值 MUST 至多为 160 个 Unicode 码点。整份资料成功提交后，接口 MUST 返回 `200`、保存后的资料和 `Cache-Control: no-store`。

#### Scenario: 首次保存并恢复默认展示

- **WHEN** 用户提交合法资料并保存，之后将昵称和简介清空并选择 `default` 头像再次保存
- **THEN** 首次保存后的资料重新读取仍可见，第二次保存后昵称和简介为空，显示名回退到用户名且头像恢复默认

#### Scenario: 输入无效

- **WHEN** 更新缺少字段、含未知字段或非字符串值，或昵称、头像、简介不符合字段规则
- **THEN** 接口返回 `400 INVALID_REQUEST`，本次请求未改写已有资料

#### Scenario: 同账号并发首次保存

- **WHEN** 同一个账号同时提交两份不同的合法资料，两次保存均成功
- **THEN** 后续读取返回最后一次成功提交的完整资料，三个字段来自同一次提交

### Requirement: 资料身份、请求校验与故障响应

系统 MUST 验证两个接口的 Access Token 及当前会话，并为保存请求验证 CSRF。接口 MUST 使用约定的错误结构区分身份、CSRF、认证依赖和资料存储失败。

#### Scenario: 旧会话提交保存

- **WHEN** 旧会话已被新登录替换，并用该旧会话的 Access Token 和有效 CSRF 提交资料
- **THEN** 接口返回 `401 AUTH_SESSION_REPLACED`，资料保持原值

#### Scenario: 保存缺少有效 CSRF

- **WHEN** 当前会话有效但保存请求缺少有效 CSRF 请求头或 Cookie
- **THEN** 接口返回 `403 AUTH_CSRF_INVALID`，资料保持原值

#### Scenario: 资料存储暂不可用

- **WHEN** 当前会话有效，但系统无法完成资料的读取或保存
- **THEN** 接口返回 `503 PROFILE_SERVICE_UNAVAILABLE`，客户端按错误处理该操作

### Requirement: 个人主页编辑与会话切换

Vue MUST 在已认证的 `/profile` 展示本人资料、退出入口和返回直播间列表的入口，并提供编辑、保存和取消。个人主页可从目录进入，也可直接访问；需要登录时保留个人主页地址，完成登录后继续。页面 MUST 以纯文本显示昵称和简介，保存处理中限制重复提交。读取失败时 MUST 提供重试，保存失败时 MUST 保留输入。

#### Scenario: 编辑取消与保存失败

- **WHEN** 用户取消编辑，或提交后遇到字段错误、服务故障或网络超时
- **THEN** 取消时显示最近成功加载的资料，保存失败时保留输入并提示原因，结果不明确时可以重新读取确认

#### Scenario: 资料响应属于已结束的会话

- **WHEN** 资料请求发出后，用户退出或切换到另一个会话，随后原请求返回
- **THEN** 旧资料与编辑状态被清除，迟到响应不更新当前页面

#### Scenario: 登录后继续访问个人主页

- **WHEN** 用户直接打开 `/profile` 时尚未登录，随后完成登录
- **THEN** 页面继续打开 `/profile`，加载当前用户的资料和本人房间，并提供返回直播间列表的入口

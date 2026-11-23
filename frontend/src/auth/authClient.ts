import { requestJson } from '../api/requestJson'

const base = '/api/v1/auth'

export interface TokenResponse {
  accessToken: string
  tokenType: 'Bearer'
  expiresIn: number
  sessionId: string
  sessionExpiresAt: string
}

export interface CurrentUser {
  userId: string
  username: string
}

/**
 * 初始化 CSRF 后提交注册表单。
 * @param username 保留大小写的账号名
 * @param password 用户设置的原始密码
 * @returns 提交成功的新账号摘要
 * @throws ApiRequestError 校验、重复账号、限流、依赖或传输失败。超限时提供等待秒数。
 */
async function register(username: string, password: string): Promise<CurrentUser> {
  await authClient.csrf()
  return requestJson<CurrentUser>(`${base}/register`, { method: 'POST', body: { username, password } })
}

export const authClient = {
  /**
   * 取得供后续写请求使用的 CSRF Cookie。
   * @returns 初始化完成后的空结果
   * @throws ApiRequestError 初始化失败
   */
  csrf: () => requestJson<void>(`${base}/csrf`),
  register,
  /**
   * 以原样账号名和密码建立当前浏览器的登录会话。
   * @param username 保留大小写的账号名
   * @param password 用户输入的原始密码
   * @returns 新的访问凭证和会话元数据
   * @throws ApiRequestError 凭证错误、限流、依赖或传输失败
   */
  login: (username: string, password: string) =>
    requestJson<TokenResponse>(`${base}/login`, { method: 'POST', body: { username, password } }),
  /**
   * 使用浏览器附带的 Refresh Cookie 轮换会话凭证。
   * @returns 新的访问凭证和原会话元数据
   * @throws ApiRequestError 凭证不可用或轮换结果无法确认
   */
  refresh: () => requestJson<TokenResponse>(`${base}/refresh`, { method: 'POST' }),
  /**
   * 撤销页面明确指定的登录会话。
   * @param sessionId 页面发起退出时持有的会话 ID
   * @returns 撤销成功后的空结果
   * @throws ApiRequestError 目标已变化、凭证无效或请求失败
   */
  logout: (sessionId: string) => requestJson<void>(`${base}/logout`, { method: 'POST', body: { sessionId } }),
  /**
   * 读取当前登录账号的基本资料。
   * @param access 已签发的 Access Token
   * @returns 当前账号的字符串 ID 和原样用户名
   * @throws ApiRequestError 鉴权或读取失败
   */
  me: (access: string) => requestJson<CurrentUser>(`${base}/me`, { access }),
}

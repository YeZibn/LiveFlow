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
  csrf: () => requestJson<void>(`${base}/csrf`),
  register,
  login: (username: string, password: string) =>
    requestJson<TokenResponse>(`${base}/login`, { method: 'POST', body: { username, password } }),
  refresh: () => requestJson<TokenResponse>(`${base}/refresh`, { method: 'POST' }),
  logout: (sessionId: string) => requestJson<void>(`${base}/logout`, { method: 'POST', body: { sessionId } }),
  me: (access: string) => requestJson<CurrentUser>(`${base}/me`, { access }),
}

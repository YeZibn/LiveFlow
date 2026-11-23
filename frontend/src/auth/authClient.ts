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

export const authClient = {
  csrf: () => requestJson<void>(`${base}/csrf`),
  login: (username: string, password: string) =>
    requestJson<TokenResponse>(`${base}/login`, { method: 'POST', body: { username, password } }),
  refresh: () => requestJson<TokenResponse>(`${base}/refresh`, { method: 'POST' }),
  logout: (sessionId: string) => requestJson<void>(`${base}/logout`, { method: 'POST', body: { sessionId } }),
  me: (access: string) => requestJson<CurrentUser>(`${base}/me`, { access }),
}

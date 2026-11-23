import { requestJson } from '../api/requestJson'

const profilePath = '/api/v1/users/me/profile'

export const avatarOptions = [
  { key: 'default', label: '默认' },
  { key: 'blue', label: '蓝色' },
  { key: 'green', label: '绿色' },
  { key: 'orange', label: '橙色' },
] as const

type AvatarKey = typeof avatarOptions[number]['key']

export interface ProfileForm {
  nickname: string
  avatarKey: AvatarKey
  bio: string
}

export interface UserProfile extends ProfileForm {
  userId: string
  username: string
  displayName: string
}

/**
 * 读取当前会话对应账号的个人资料。
 * @param access 当前会话的 Access Token
 * @returns 已保存的个人资料，首次读取时返回默认值
 * @throws ApiRequestError 认证、存储或传输失败
 */
export function readProfile(access: string) {
  return requestJson<UserProfile>(profilePath, { access })
}

/**
 * 提交完整表单，由请求层携带 Bearer 凭证和 CSRF 请求头。
 * @param access 当前会话的 Access Token
 * @param form 此次提交的资料快照
 * @returns 服务端保存并规范化后的完整资料
 * @throws ApiRequestError 认证、校验、存储或传输失败
 */
export function saveProfile(access: string, form: ProfileForm) {
  return requestJson<UserProfile>(profilePath, { method: 'PUT', access, body: form })
}

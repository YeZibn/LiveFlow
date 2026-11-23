import { requestJson } from '../api/requestJson'

export interface RoomDetails {
  roomId: string
  title: string
}

export interface Room extends RoomDetails {
  createdAt: string
}

/**
 * 查询当前登录账号的房间。
 * @param access 当前会话的 Access Token
 * @returns 房间或表示尚未创建的 null
 * @throws ApiRequestError 认证、读取或传输失败
 */
export async function readRoom(access: string): Promise<Room | null> {
  const response = await requestJson<{ room: Room | null }>('/api/v1/users/me/room', { access })
  return response.room
}

/**
 * 为当前登录账号创建房间，由请求层附加 CSRF 请求头。
 * @param access 当前会话的 Access Token
 * @param title 用户输入的原始房间名称
 * @returns 已提交的房间
 * @throws ApiRequestError 输入、认证、创建冲突、存储或传输失败
 */
export function createRoom(access: string, title: string): Promise<Room> {
  return requestJson<Room>('/api/v1/rooms', { method: 'POST', body: { title }, access })
}

/**
 * 获取房间页所需信息。
 * @param access 当前会话的 Access Token
 * @param roomId 已验证格式的房间 UUID
 * @returns 房间对外标识及标题
 * @throws ApiRequestError 鉴权、房间缺失、读取或传输失败
 */
export function readRoomDetails(access: string, roomId: string): Promise<RoomDetails> {
  return requestJson<RoomDetails>(`/api/v1/rooms/${encodeURIComponent(roomId)}`, { access })
}

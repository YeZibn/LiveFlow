import { ApiRequestError, requestJson } from '../api/requestJson'

export interface RoomMessage {
  type: 'message'
  messageId: string
  senderName: string
  content: string
  sentAt: string
}

type RoomFrame =
  | RoomMessage
  | { type: 'ready'; roomId: string }
  | { type: 'error'; code: string; message: string }

interface TicketResponse {
  ticket: string
  expiresIn: number
}

interface MessageReceipt {
  messageId: string
  sentAt: string
}

export interface MessageSubmission {
  clientMessageId: string
  content: string
}

function roomUrl(roomId: string) {
  return `/api/v1/rooms/${encodeURIComponent(roomId)}`
}

/**
 * 申请当前房间的一次性 WebSocket 票据。
 * @param access 当前会话的 Access Token
 * @param roomId 已加载房间的 UUID
 * @returns 原始票据及其有效期
 * @throws ApiRequestError 认证、房间或存储故障
 */
export async function requestRoomTicket(access: string, roomId: string): Promise<TicketResponse> {
  const result = await requestJson<TicketResponse>(`${roomUrl(roomId)}/ws-ticket`, { method: 'POST', access })
  if (typeof result.ticket !== 'string' || !/^[A-Za-z0-9_-]{43}$/.test(result.ticket)
    || typeof result.expiresIn !== 'number' || !Number.isInteger(result.expiresIn) || result.expiresIn <= 0) {
    throw new ApiRequestError(0, 'RESPONSE_INVALID', '连接票据响应无效。')
  }
  return result
}

/**
 * 提交一次弹幕。相同提交的用户重试继续使用原始标识。
 * @param access 当前会话的 Access Token
 * @param roomId 已加载房间的 UUID
 * @param body 当前提交的输入快照及客户端标识
 * @returns 服务端的受理结果，列表由 WebSocket 独立更新
 * @throws ApiRequestError 提交被拒绝，或响应结果无法确认
 */
export async function sendRoomMessage(access: string, roomId: string, body: MessageSubmission): Promise<MessageReceipt> {
  const result = await requestJson<MessageReceipt>(`${roomUrl(roomId)}/messages`, { method: 'POST', access, body })
  if (!isUuid(result.messageId) || !isSentAt(result.sentAt)) {
    throw new ApiRequestError(0, 'RESPONSE_INVALID', '发送结果无法确认。')
  }
  return result
}

/**
 * 创建同源房间接收连接。凭证随后通过应用首帧提交。
 * @returns 尚未完成房间认证的浏览器 WebSocket
 * @throws DOMException 浏览器无法建立连接
 */
export function openRoomSocket(): WebSocket {
  const url = new URL('/ws/rooms', window.location.origin)
  url.protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
  return new WebSocket(url)
}

/**
 * 解析并校验 WebSocket 下行帧，调用方据此维护连接与列表状态。
 * @param data 浏览器收到的 WebSocket 载荷
 * @returns 受支持的帧，格式无效时返回 undefined
 */
export function decodeRoomFrame(data: unknown): RoomFrame | undefined {
  if (typeof data !== 'string') return undefined
  let result: unknown
  try {
    result = JSON.parse(data)
  } catch {
    return undefined
  }
  if (!result || typeof result !== 'object') return undefined
  const frame = result as Record<string, unknown>
  if (frame.type === 'ready' && isUuid(frame.roomId)) {
    return { type: 'ready', roomId: frame.roomId }
  }
  if (frame.type === 'error' && typeof frame.code === 'string' && typeof frame.message === 'string') {
    return { type: 'error', code: frame.code, message: frame.message }
  }
  if (frame.type === 'message' && isUuid(frame.messageId) && typeof frame.senderName === 'string'
    && frame.senderName.length > 0 && typeof frame.content === 'string' && isSentAt(frame.sentAt)) {
    return { type: 'message', messageId: frame.messageId, senderName: frame.senderName,
      content: frame.content, sentAt: frame.sentAt }
  }
  return undefined
}

function isUuid(value: unknown): value is string {
  return typeof value === 'string' && /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/.test(value)
}

function isSentAt(value: unknown): value is string {
  return typeof value === 'string' && /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z$/.test(value)
    && Number.isFinite(Date.parse(value))
}

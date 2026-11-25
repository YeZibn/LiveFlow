import { ApiRequestError, requestJson } from '../api/requestJson'

/** 历史和 WebSocket 共用的公开消息。序号在线上传输时始终为字符串。 */
export interface RoomMessage {
  roomSeq: string
  messageId: string
  senderName: string
  content: string
  sentAt: string
}

type RoomFrame =
  | (RoomMessage & { type: 'message' })
  | { type: 'ready' | 'resync_required'; roomId: string }
  | { type: 'history_progress'; latestStoredSeq: string }
  | { type: 'error'; code: string; message: string }

interface TicketResponse { ticket: string; expiresIn: number }

/** 可用于读取终态的原预约及期限。 */
export interface SubmissionReceiptReference {
  submissionId: string
  receiptExpiresAt: string
}

export interface QueuedSubmission extends SubmissionReceiptReference { status: 'queued' }

export type MessageReceipt =
  | { submissionId: string; status: 'unconfirmed' }
  | { submissionId: string; status: 'accepted'; messageId: string; roomSeq: string; sentAt: string }
  | { submissionId: string; status: 'rejected'; code: 'MESSAGE_ID_CONFLICT' | 'MESSAGE_SUBMISSION_EXPIRED' }

export interface MessageSubmission { clientMessageId: string; content: string }

export interface HistoryPage {
  items: RoomMessage[]
  latestStoredSeq: string
  throughSeq: string
  nextAfterSeq: string | null
}

/**
 * @param roomId 对外房间 UUID
 * @returns 同源房间 API 前缀
 */
function roomUrl(roomId: string) {
  return `/api/v1/rooms/${encodeURIComponent(roomId)}`
}

/**
 * @param access 当前会话的 Access Token
 * @param roomId 已加载房间的 UUID
 * @returns 一次性连接票据和有效期
 * @throws ApiRequestError 会话、房间、存储或响应无效
 */
export async function requestRoomTicket(access: string, roomId: string): Promise<TicketResponse> {
  const result = await requestJson<TicketResponse>(`${roomUrl(roomId)}/ws-ticket`, { method: 'POST', access })
  if (typeof result.ticket !== 'string' || !/^[A-Za-z0-9_-]{43}$/.test(result.ticket)
    || !Number.isInteger(result.expiresIn) || result.expiresIn <= 0) {
    throw new ApiRequestError(0, 'RESPONSE_INVALID', '连接票据响应无效。')
  }
  return result
}

/**
 * @param access 当前会话的 Access Token
 * @param roomId 已加载房间的 UUID
 * @param body 单次发送的输入快照及客户端标识
 * @returns Broker 确认的预约及可查询的截止时间
 * @throws ApiRequestError 提交遭拒或发送结果无法确认
 */
export async function sendRoomMessage(access: string, roomId: string, body: MessageSubmission): Promise<QueuedSubmission> {
  const result = await requestJson<QueuedSubmission>(`${roomUrl(roomId)}/messages`, { method: 'POST', access, body })
  if (result.status !== 'queued' || !isUuid(result.submissionId) || !isTimestamp(result.receiptExpiresAt)) {
    throw new ApiRequestError(0, 'RESPONSE_INVALID', '发送结果无法确认。')
  }
  return result
}

/**
 * @param failure 发送响应携带的错误信息
 * @returns 结果未知且查询信息有效时返回回执引用，否则返回 undefined
 */
export function unknownSubmissionReceipt(failure: unknown): SubmissionReceiptReference | undefined {
  if (!(failure instanceof ApiRequestError) || failure.status !== 503 || failure.code !== 'MESSAGE_SUBMISSION_UNKNOWN') return
  const details = failure.details
  if (typeof details === 'object' && details !== null && 'submissionId' in details && 'receiptExpiresAt' in details
    && isUuid(details.submissionId) && isTimestamp(details.receiptExpiresAt)) {
    return { submissionId: details.submissionId, receiptExpiresAt: details.receiptExpiresAt }
  }
}

/**
 * @param access 当前会话的 Access Token
 * @param roomId 房间 UUID
 * @param id 从提交响应取得的预约标识
 * @returns 该会话可见的预约结果
 * @throws ApiRequestError 查询失败或回执无效
 */
export async function requestMessageReceipt(access: string, roomId: string, id: string): Promise<MessageReceipt> {
  const result = await requestJson<MessageReceipt>(
    `${roomUrl(roomId)}/messages/submissions/${encodeURIComponent(id)}`, { access })
  if (result.submissionId === id && (result.status === 'unconfirmed'
    || (result.status === 'accepted' && isUuid(result.messageId) && isSequence(result.roomSeq) && result.roomSeq !== '0'
      && isTimestamp(result.sentAt))
    || (result.status === 'rejected' && ['MESSAGE_ID_CONFLICT', 'MESSAGE_SUBMISSION_EXPIRED'].includes(result.code)))) {
    return result
  }
  throw new ApiRequestError(0, 'RESPONSE_INVALID', '回执响应无效。')
}

/**
 * 获取最近 100 条历史。响应可以为空或有序号缺口。
 * @param access 当前会话的 Access Token
 * @param roomId 当前房间 UUID
 * @returns 同一数据库快照中的消息和最大落库序号
 * @throws ApiRequestError 历史无法读取或响应无效
 */
export async function requestRoomHistory(access: string, roomId: string): Promise<HistoryPage> {
  const page = await requestJson<HistoryPage>(`${roomUrl(roomId)}/messages`, { access })
  if (!Array.isArray(page.items) || page.items.length > 100 || !page.items.every(isMessage)
    || !isSequence(page.latestStoredSeq) || page.throughSeq !== page.latestStoredSeq || page.nextAfterSeq !== null) {
    throw new ApiRequestError(0, 'RESPONSE_INVALID', '历史响应无效。')
  }
  // 最近页要求升序、无重复且不超过当页上界，允许中间缺号和最后一条已过期。
  const latest = BigInt(page.latestStoredSeq)
  let previous = 0n
  const ids = new Set<string>()
  for (const item of page.items) {
    const sequence = BigInt(item.roomSeq)
    if (sequence <= previous || sequence > latest || ids.has(item.messageId)) {
      throw new ApiRequestError(0, 'RESPONSE_INVALID', '历史顺序无法确认。')
    }
    previous = sequence
    ids.add(item.messageId)
  }
  return page
}

/** @returns 同源且尚未发出票据首帧的浏览器连接 */
export function openRoomSocket(): WebSocket {
  const url = new URL('/ws/rooms', window.location.origin)
  url.protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
  return new WebSocket(url)
}

/**
 * @param data 浏览器收到的载荷
 * @returns 合法的协议帧，非法时为空
 */
export function decodeRoomFrame(data: unknown): RoomFrame | undefined {
  if (typeof data !== 'string') return undefined
  let frame: unknown
  try {
    frame = JSON.parse(data)
  } catch {
    return undefined
  }
  if (!isObject(frame)) return undefined
  if ((frame.type === 'ready' || frame.type === 'resync_required') && isUuid(frame.roomId)) {
    return { type: frame.type, roomId: frame.roomId }
  }
  if (frame.type === 'history_progress' && isSequence(frame.latestStoredSeq)) {
    return { type: 'history_progress', latestStoredSeq: frame.latestStoredSeq }
  }
  if (frame.type === 'error' && typeof frame.code === 'string' && typeof frame.message === 'string') {
    return { type: 'error', code: frame.code, message: frame.message }
  }
  if (frame.type === 'message' && isMessage(frame)) return { ...frame, type: 'message' }
  return undefined
}

function isObject(value: unknown): value is Record<string, unknown> {
  return !!value && typeof value === 'object' && !Array.isArray(value)
}

function isMessage(value: unknown): value is RoomMessage {
  return isObject(value) && isSequence(value.roomSeq) && value.roomSeq !== '0' && isUuid(value.messageId)
    && typeof value.senderName === 'string' && value.senderName.length > 0
    && typeof value.content === 'string' && isTimestamp(value.sentAt)
}

/**
 * @param value 原始序号
 * @returns 格式正确且可精确比较
 */
function isSequence(value: unknown): value is string {
  return typeof value === 'string' && /^(0|[1-9]\d{0,18})$/.test(value) && BigInt(value) <= 9223372036854775806n
}

function isUuid(value: unknown): value is string {
  return typeof value === 'string' && /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/.test(value)
}

function isTimestamp(value: unknown): value is string {
  return typeof value === 'string' && /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z$/.test(value)
    && Number.isFinite(Date.parse(value))
}

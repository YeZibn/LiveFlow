<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { ApiRequestError } from '../api/requestJson'
import { readRoomDetails, type RoomDetails } from './roomClient'

const props = defineProps<{
  roomId: string
  withAccess: <T>(call: (access: string) => Promise<T>) => Promise<T>
}>()

const uuidV4 = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/
const room = ref<RoomDetails>()
const loading = ref(true)
const error = ref('')
const retryable = ref(false)
const copyFeedback = ref('')
let active = true

onMounted(() => void loadRoom())
onBeforeUnmount(() => { active = false })

/**
 * 从当前会话读取目标房间，并呈现成功或失败状态。
 * @returns 更新当前页面或放弃已离开页面的结果后完成
 */
async function loadRoom() {
  error.value = ''
  retryable.value = false
  if (!uuidV4.test(props.roomId)) {
    error.value = '房间链接无效'
    loading.value = false
    return
  }

  loading.value = true
  try {
    const id = props.roomId
    const details = await props.withAccess(access => readRoomDetails(access, id))
    // withAccess 验证会话，组件以房间和会话为键，卸载后忽略迟到结果。
    if (active) room.value = details
  } catch (cause) {
    if (!active) return
    const code = cause instanceof ApiRequestError ? cause.code : undefined
    retryable.value = code !== 'ROOM_NOT_FOUND' && code !== 'INVALID_REQUEST'
    if (code === 'ROOM_NOT_FOUND') {
      error.value = '房间不存在'
    } else if (code === 'INVALID_REQUEST') {
      error.value = '房间链接无效'
    } else {
      error.value = cause instanceof ApiRequestError ? cause.message : '读取直播间失败，请重试。'
    }
  } finally {
    loading.value = false
  }
}

/**
 * 复制当前房间的同源链接。
 * @returns 写入剪贴板或显示失败提示后完成
 */
async function copyLink() {
  if (!room.value) return
  copyFeedback.value = ''
  try {
    const url = new URL(`/rooms/${room.value.roomId}`, window.location.origin)
    await navigator.clipboard.writeText(url.href)
    if (active) copyFeedback.value = '链接已复制'
  } catch {
    if (active) copyFeedback.value = '复制失败，请从地址栏复制。'
  }
}
</script>

<template>
  <div class="room-page">
    <header class="room-header">
      <div>
        <a class="back-link" href="/">← 返回个人主页</a>
        <h1>{{ room?.title ?? '直播间' }}</h1>
      </div>
      <div v-if="room" class="share">
        <button type="button" class="ui-button" @click="copyLink">复制链接</button>
        <p v-if="copyFeedback" class="copy-feedback" role="status">{{ copyFeedback }}</p>
      </div>
    </header>

    <p v-if="loading" class="ui-feedback" role="status">正在读取直播间…</p>
    <div v-else-if="error" class="room-error">
      <p class="ui-feedback ui-feedback--error" role="alert">{{ error }}</p>
      <button v-if="retryable" type="button" class="ui-button" @click="loadRoom">重新读取直播间</button>
    </div>
    <div v-else-if="room" class="room-layout">
      <section class="video" aria-label="直播画面">
        <p>直播画面尚未接入</p>
      </section>
      <section class="messages" aria-labelledby="messages-title">
        <h2 id="messages-title">弹幕</h2>
        <p>弹幕暂未开放</p>
      </section>
    </div>
  </div>
</template>

<style scoped>
.room-page {
  width: min(100%, 90rem);
  margin-inline: auto;
  padding: clamp(1rem, 3vw, 2.5rem);
}
.room-header {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 1rem;
  margin-bottom: 1.25rem;
}
.room-header > div { min-width: 0; }
.back-link { font-size: 0.875rem; text-decoration: none; }
.back-link:hover { text-decoration: underline; }
h1 { margin: 0.65rem 0 0; font-size: clamp(1.6rem, 4vw, 2.5rem); overflow-wrap: anywhere; }
.share { display: flex; align-items: center; flex-wrap: wrap; gap: 0.75rem; }
.copy-feedback { margin: 0; color: var(--muted); font-size: 0.875rem; }
.room-error { display: grid; gap: 0.85rem; justify-items: start; }
.room-layout { display: grid; grid-template-columns: minmax(0, 1fr) minmax(15rem, 24%); gap: 1rem; }
.video {
  display: grid;
  place-items: center;
  aspect-ratio: 16 / 9;
  padding: 1rem;
  border-radius: 1.15rem;
  background: #172922;
  color: #e1eae3;
  text-align: center;
}
.video p, .messages p { margin: 0; }
.messages {
  padding: 1rem;
  border: 1px solid var(--line);
  border-radius: 1.15rem;
  background: var(--surface);
}
.messages h2 { margin: 0 0 0.85rem; font-size: 1rem; }
.messages p { color: var(--muted); font-size: 0.875rem; }

@media (max-width: 760px) {
  .room-layout { grid-template-columns: minmax(0, 1fr); }
  .messages { min-height: 8rem; }
}
</style>

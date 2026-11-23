<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { ApiRequestError } from '../api/requestJson'
import RoomMessages from './RoomMessages.vue'
import { readRoomDetails, type RoomDetails } from './roomClient'

const props = defineProps<{
  roomId: string
  listReturn: string
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
    <header class="room-toolbar">
      <a class="back-link" :href="listReturn">← 返回直播间列表</a>
      <div v-if="room" class="share">
        <button type="button" class="ui-button" @click="copyLink">复制链接</button>
        <p v-if="copyFeedback" class="copy-feedback" role="status">{{ copyFeedback }}</p>
      </div>
    </header>

    <div class="room-heading">
      <h1>{{ room?.title ?? '直播间' }}</h1>
      <p v-if="room" class="owner">房主 <span>{{ room.ownerName }}</span></p>
    </div>
    <p v-if="loading" class="ui-feedback" role="status">正在读取直播间…</p>
    <div v-else-if="error" class="room-error">
      <p class="ui-feedback ui-feedback--error" role="alert">{{ error }}</p>
      <button v-if="retryable" type="button" class="ui-button" @click="loadRoom">重新读取直播间</button>
    </div>
    <div v-else-if="room" class="room-layout">
      <section class="video" aria-label="直播画面">
        <p>直播画面尚未接入</p>
      </section>
      <RoomMessages :room-id="room.roomId" :with-access="withAccess" />
    </div>
  </div>
</template>

<style scoped>
.room-page {
  --text: #eaf1ed;
  --muted: #a8b7ae;
  --surface: #1b2320;
  --line: #34413b;
  --field-line: #506057;
  --tint: #26382f;
  --accent: #22684e;
  --accent-hover: #2a7759;
  --danger: #ffaca6;
  --room-highlight: #a5dfba;
  width: 100%;
  min-height: calc(100svh - 4.75rem);
  background: #111715;
  color: var(--text);
  padding: clamp(1rem, 3vw, 2.5rem) max(clamp(1rem, 3vw, 2.5rem), calc((100% - 88rem) / 2));
  margin-inline: auto;
}
.room-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 1rem;
  margin-bottom: 1.25rem;
}
.room-heading { margin-bottom: 1rem; }
.back-link { color: var(--room-highlight); font-size: 0.875rem; text-decoration: none; }
.back-link:hover { text-decoration: underline; }
.room-page :deep(:focus-visible) { outline-color: var(--room-highlight); }
h1 { margin: 0; font-size: clamp(1.6rem, 4vw, 2.5rem); line-height: 1.2; overflow-wrap: anywhere; }
.owner { margin: 0.45rem 0 0; color: var(--muted); overflow-wrap: anywhere; }
.owner span { margin-inline-start: 0.25rem; color: var(--text); }
.share { display: flex; align-items: center; flex-wrap: wrap; gap: 0.75rem; }
.copy-feedback { margin: 0; color: var(--muted); font-size: 0.875rem; }
.room-error { display: grid; gap: 0.85rem; justify-items: start; }
.room-page > .ui-feedback, .room-error .ui-feedback { color: var(--text); background: var(--tint); }
.room-error .ui-feedback--error { color: var(--danger); }
.room-layout {
  display: grid;
  grid-template-columns: minmax(0, 1fr) clamp(320px, 28vw, 380px);
  gap: 1rem;
  align-items: start;
}
.video {
  display: grid;
  place-items: center;
  aspect-ratio: 16 / 9;
  padding: 1rem;
  border: 1px solid var(--line);
  border-radius: 0.85rem;
  background: #0b100e;
  color: var(--muted);
  text-align: center;
}
.video p { margin: 0; }

@media (max-width: 50rem) {
  .room-layout { grid-template-columns: minmax(0, 1fr); }
}
</style>

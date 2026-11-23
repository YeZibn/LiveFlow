<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { ApiRequestError } from '../api/requestJson'
import { authClient } from '../auth/authClient'
import { createRoom, readRoom, type Room } from './roomClient'

const props = defineProps<{
  withAccess: <T>(call: (access: string) => Promise<T>) => Promise<T>
  authBusy: boolean
}>()

// undefined 表示尚未读到结果，null 表示服务端确认该账号还没有房间。
const room = ref<Room | null>()
const loading = ref(true)
const submitting = ref(false)
const editing = ref(false)
const title = ref('')
const titleInput = ref<HTMLInputElement>()
const readError = ref('')
const createError = ref('')
const titleError = ref('')
const notice = ref('')
const confirming = ref(false)
let active = true

onMounted(() => void loadRoom())
onBeforeUnmount(() => { active = false })

/**
 * 读取本人房间，并在确认创建结果时保留未提交成功的名称。
 * @returns 更新读取状态后完成
 */
async function loadRoom() {
  loading.value = true
  readError.value = ''
  try {
    const current = await props.withAccess(readRoom)
    // 认证层已校验会话归属，卸载后的组件也无需再显示请求结果。
    if (!active) return
    room.value = current
    if (current) {
      if (confirming.value) notice.value = '已读取到当前直播间。'
      closeForm()
    } else if (confirming.value) {
      createError.value = '尚未查到直播间。可以再次提交。'
    }
    confirming.value = false
  } catch (cause) {
    if (active) {
      readError.value = cause instanceof ApiRequestError ? cause.message : '读取直播间失败，请重试。'
    }
  } finally {
    loading.value = false
  }
}

/** 展开创建表单并把焦点移到名称输入框。 */
function openForm() {
  editing.value = true
  void nextTick(() => titleInput.value?.focus())
}

/** 关闭表单并清除当前输入与字段反馈。 */
function closeForm() {
  editing.value = false
  title.value = ''
  titleError.value = ''
  createError.value = ''
}

/**
 * 创建房间。结果不明确时查询服务端结果，保留输入供用户手动重试。
 * @returns 创建成功或完成本次错误反馈后结束
 */
async function submit() {
  if (submitting.value || loading.value || props.authBusy || readError.value || room.value !== null) return
  // 如果 Access Token 在写入前被明确拒绝，续期后的重发仍使用同一标题快照。
  const submittedTitle = title.value
  submitting.value = true
  createError.value = ''
  titleError.value = ''
  try {
    const created = await props.withAccess(access => createRoom(access, submittedTitle))
    if (active) {
      room.value = created
      closeForm()
      notice.value = '直播间已创建。'
    }
  } catch (cause) {
    if (!active) return
    if (cause instanceof ApiRequestError) {
      if (cause.code === 'ROOM_ALREADY_EXISTS' || cause.status === 0 || cause.status >= 500) {
        // POST 可能已经写入，先查当前房间，避免把重试当成一次新的自动创建。
        confirming.value = true
        await loadRoom()
      } else {
        titleError.value = cause.fieldErrors?.title ?? ''
        createError.value = cause.message
        if (cause.code === 'AUTH_CSRF_INVALID') {
          // 只重新获取 CSRF，保留用户输入，由用户决定何时再提交。
          await authClient.csrf().catch(() => undefined)
        }
      }
    } else {
      createError.value = '创建直播间失败，请重试。'
    }
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <section class="my-room" aria-labelledby="my-room-heading">
    <h2 id="my-room-heading">我的直播间</h2>
    <p v-if="loading" class="muted" role="status">
      {{ confirming ? '正在确认创建结果…' : '正在读取直播间…' }}
    </p>
    <template v-else-if="readError">
      <p class="ui-feedback ui-feedback--error" role="alert">{{ readError }}</p>
      <button class="ui-button" type="button" :disabled="authBusy || submitting" @click="loadRoom">重新读取直播间</button>
    </template>
    <template v-else-if="room">
      <p class="room-title">{{ room.title }}</p>
      <a class="ui-button ui-button--primary" :href="`/rooms/${room.roomId}`">进入直播间</a>
      <p v-if="notice" class="ui-feedback" role="status">{{ notice }}</p>
    </template>
    <template v-else-if="room === null && !editing">
      <button class="ui-button ui-button--primary" type="button" :disabled="authBusy" @click="openForm">创建直播间</button>
    </template>

    <form v-if="editing" class="ui-form" :aria-busy="submitting || loading" @submit.prevent="submit">
      <div class="ui-field">
        <label for="room-title">房间名称</label>
        <input id="room-title" ref="titleInput" v-model="title" class="ui-input" required
          :aria-describedby="titleError ? 'room-title-hint room-create-error' : 'room-title-hint'"
          :aria-invalid="Boolean(titleError)" :disabled="authBusy || submitting || loading" />
        <small id="room-title-hint">1–40 个字符，使用单行文本。</small>
      </div>
      <p v-if="createError" id="room-create-error" class="ui-feedback ui-feedback--error" role="alert">
        {{ titleError || createError }}
      </p>
      <div class="actions">
        <button class="ui-button ui-button--primary" type="submit" :disabled="authBusy || submitting || loading || !!readError">
          {{ submitting ? '正在创建…' : '创建' }}
        </button>
        <button class="ui-button" type="button" :disabled="submitting || loading" @click="closeForm">取消创建</button>
      </div>
    </form>
  </section>
</template>

<style scoped>
.my-room {
  display: grid;
  justify-items: start;
  gap: 0.8rem;
  margin-top: 1.5rem;
  padding-top: 1.25rem;
  border-top: 1px solid var(--line);
}
h2, p { margin: 0; }
h2 { font-size: 1.2rem; }
.muted { color: var(--muted); }
.room-title { font-weight: 650; white-space: pre-wrap; overflow-wrap: anywhere; min-width: 0; max-width: 100%; }
form { width: 100%; }
.actions { display: flex; flex-wrap: wrap; gap: 0.5rem; }
</style>

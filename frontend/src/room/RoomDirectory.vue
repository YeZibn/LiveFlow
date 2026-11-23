<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import { ApiRequestError } from '../api/requestJson'
import { readRoomDirectory, type DirectoryItem } from './roomClient'

const props = defineProps<{
  keyword: string
  withAccess: <T>(call: (access: string) => Promise<T>) => Promise<T>
}>()
const emit = defineEmits<{ search: [keyword: string] }>()
const draft = ref('')
const inputError = ref('')
const rooms = ref<DirectoryItem[]>([])
const nextCursor = ref<string | null>(null)
const loading = ref(false)
const failure = ref('')
const dateFormatter = new Intl.DateTimeFormat('zh-CN', {
  year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hourCycle: 'h23',
})

let generation = 0
onBeforeUnmount(() => { generation++ })

watch(() => props.keyword, keyword => {
  draft.value = keyword
  inputError.value = ''
  void loadFirstPage()
}, { immediate: true })

/** 校验用户提交的原始关键词，并让 URL 成为已提交搜索的来源。 */
function submitSearch() {
  const raw = draft.value
  const query = raw.replace(/^\p{White_Space}+|\p{White_Space}+$/gu, '')
  if (/[\u0000-\u001f\u007f-\u009f\u2028\u2029\uD800-\uDFFF]/u.test(raw) || [...query].length > 40) {
    inputError.value = '请输入最多 40 个字符的单行关键词。'
    return
  }
  inputError.value = ''
  draft.value = query
  emit('search', query)
  if (query === props.keyword) void loadFirstPage()
}

/** 清除搜索并从首页重新读取全部房间。 */
function clearSearch() {
  draft.value = ''
  submitSearch()
}

/**
 * 使旧请求立即过期，并从当前关键词的起点读取。
 * @returns 新查询的首屏或失败状态就绪后完成
 */
function loadFirstPage() {
  generation++
  rooms.value = []
  nextCursor.value = null
  return loadPage()
}

/** 同一时刻只允许一个请求使用当前的页边界。 */
function loadMore() {
  if (!loading.value && nextCursor.value !== null) void loadPage(nextCursor.value)
}

/**
 * 读取当前查询的一页。只有当前页面与查询代次能够使用结果。
 * @param cursor 下一页边界，省略时读取首页
 * @returns 列表或失败状态更新后完成
 */
async function loadPage(cursor?: string) {
  const current = generation
  const keyword = props.keyword
  loading.value = true
  failure.value = ''
  try {
    const page = await props.withAccess(access => readRoomDirectory(access, keyword, cursor))
    // 新搜索、卸载及会话切换都会使此请求过期，旧页不能追加到新列表。
    if (current !== generation) return
    rooms.value = cursor ? [...rooms.value, ...page.items] : page.items
    nextCursor.value = page.nextCursor
  } catch (cause) {
    if (current !== generation) return
    if (cursor && cause instanceof ApiRequestError && cause.code === 'INVALID_REQUEST') {
      await loadFirstPage()
      return
    }
    failure.value = cause instanceof ApiRequestError ? cause.message : '无法读取直播间，请重试。'
  } finally {
    if (current === generation) loading.value = false
  }
}
</script>

<template>
  <section class="directory" aria-labelledby="directory-title">
    <header class="directory-header">
      <h1 id="directory-title">直播间</h1>
      <a class="ui-button" href="/profile">个人主页</a>
    </header>

    <form class="search" role="search" @submit.prevent="submitSearch">
      <input v-model="draft" type="search" class="ui-input" aria-label="按房间名称搜索"
        :aria-invalid="inputError ? 'true' : undefined" placeholder="搜索房间名称" @input="inputError = ''" />
      <button class="ui-button ui-button--primary" type="submit">搜索</button>
      <button v-if="draft || keyword" class="ui-button" type="button" @click="clearSearch">清空</button>
    </form>
    <p v-if="inputError" class="search-error" role="alert">{{ inputError }}</p>

    <div class="results" :aria-busy="loading">
      <div v-if="rooms.length === 0" class="directory-state">
        <p v-if="loading" role="status">正在读取直播间…</p>
        <template v-else-if="failure">
          <p role="alert">{{ failure }}</p>
          <button class="ui-button" type="button" @click="loadFirstPage">重试</button>
        </template>
        <template v-else-if="keyword">
          <p>未找到匹配的直播间</p>
          <button class="ui-button" type="button" @click="clearSearch">清空搜索</button>
        </template>
        <template v-else>
          <p>暂无直播间</p>
          <a class="ui-button" href="/profile">前往个人主页创建</a>
        </template>
      </div>

      <template v-else>
        <ul class="room-list">
          <li v-for="room in rooms" :key="room.roomId" class="room-row">
            <div class="room-description">
              <div class="room-name">
                <h2>{{ room.title }}</h2>
                <span v-if="room.isMine" class="mine">我的房间</span>
              </div>
              <p>{{ room.ownerName }}</p>
            </div>
            <time :datetime="room.createdAt">{{ dateFormatter.format(new Date(room.createdAt)) }}</time>
            <a class="ui-button room-entry" :href="`/rooms/${room.roomId}`" :aria-label="`进入直播间 ${room.title}`">
              进入直播间
            </a>
          </li>
        </ul>
        <div v-if="nextCursor" class="pagination">
          <p v-if="failure" role="alert">{{ failure }}</p>
          <button class="ui-button" type="button" :disabled="loading" @click="loadMore">
            {{ loading ? '加载中…' : failure ? '重试加载更多' : '加载更多' }}
          </button>
        </div>
      </template>
    </div>
  </section>
</template>

<style scoped>
.directory {
  width: min(100%, 65rem);
  padding: clamp(1rem, 3vw, 2.5rem);
  margin-inline: auto;
}
.directory-header { display: flex; align-items: center; justify-content: space-between; gap: 1rem; }
h1 { margin: 0; font-size: clamp(1.9rem, 5vw, 3.15rem); letter-spacing: -0.045em; }
.search { display: flex; gap: 0.55rem; margin-top: 1.4rem; }
.search input { flex: 1; min-width: 0; }
.search button { flex-shrink: 0; }
.search-error { margin: 0.55rem 0 0; color: var(--danger); }
.results {
  margin-top: 1.4rem;
  border: 1px solid var(--line);
  border-radius: 1rem;
  background: var(--surface);
}
.room-list { list-style: none; margin: 0; padding: 0; }
.room-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto auto;
  align-items: center;
  gap: clamp(0.65rem, 2vw, 1.5rem);
  padding: 1rem clamp(0.75rem, 2vw, 1.35rem);
}
.room-row + .room-row { border-top: 1px solid var(--line); }
.room-name { display: flex; align-items: baseline; flex-wrap: wrap; gap: 0.35rem 0.7rem; }
h2 { margin: 0; font-size: 1rem; overflow-wrap: anywhere; }
.room-description p { margin: 0.3rem 0 0; color: var(--muted); overflow-wrap: anywhere; }
.mine { padding: 0.15rem 0.45rem; border-radius: 0.35rem; color: var(--accent); background: var(--tint); font-size: 0.74rem; }
time { color: var(--muted); font-size: 0.85rem; font-variant-numeric: tabular-nums; }
.directory-state, .pagination { display: grid; justify-items: center; gap: 0.65rem; padding: 1.25rem; }
.directory-state { padding-block: 2.5rem; }
.directory-state p, .pagination p { margin: 0; text-align: center; }
.pagination { border-top: 1px solid var(--line); }

@media (max-width: 35rem) {
  .room-row { grid-template-columns: minmax(0, 1fr) auto; gap: 0.35rem 0.55rem; }
  .room-description { grid-column: 1 / -1; }
  .room-entry { grid-column: 2; }
}
</style>

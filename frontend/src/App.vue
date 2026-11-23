<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import AuthView from './auth/AuthView.vue'
import DevelopmentCheck from './DevelopmentCheck.vue'

const location = ref(new URL(window.location.href))
const listReturn = ref('/')
const showDevelopmentCheck = computed(() => location.value.pathname === '/dev')
const roomId = computed(() => location.value.pathname.startsWith('/rooms/')
  ? location.value.pathname.slice('/rooms/'.length) : undefined)
const showProfile = computed(() => location.value.pathname === '/profile')
const keyword = computed(() => location.value.searchParams.get('q') ?? '')
const directoryHref = computed(() => location.value.pathname === '/' ? '/' + location.value.search : '/')
const showDevelopmentLink = computed(() => import.meta.env.DEV && !showDevelopmentCheck.value)

/** 从浏览器恢复路由及此历史记录的目录返回地址。 */
function readLocation() {
  location.value = new URL(window.location.href)
  const previous = window.history.state?.roomList
  listReturn.value = typeof previous === 'string' && previous.startsWith('/?') ? previous : '/'
}

readLocation()
onMounted(() => window.addEventListener('popstate', readLocation))
onUnmounted(() => window.removeEventListener('popstate', readLocation))

/**
 * 切换同源页面并保留房间入口的来源。
 * @param url 目标同源页面地址
 */
function navigate(url: URL) {
  if (url.href === location.value.href) return
  const fromDirectory = location.value.pathname === '/' && url.pathname.startsWith('/rooms/')
  window.history.pushState(fromDirectory ? { roomList: directoryHref.value } : null, '', url)
  readLocation()
  window.scrollTo(0, 0)
}

/**
 * 只接管同标签的普通站内链接。复制链接和浏览器新标签仍由真实 href 支持。
 * @param event 冒泡到应用根节点的点击事件
 */
function followLink(event: MouseEvent) {
  if (event.defaultPrevented || event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey
    || !(event.target instanceof Element)) return
  const link = event.target.closest<HTMLAnchorElement>('a[href]')
  if (!link || link.target || link.hasAttribute('download')) return
  const url = new URL(link.href)
  if (url.origin !== window.location.origin) return
  if (url.pathname !== '/' && url.pathname !== '/profile' && url.pathname !== '/dev'
    && !url.pathname.startsWith('/rooms/')) return
  event.preventDefault()
  navigate(url)
}

/**
 * 把已提交的关键词写入目录 URL，使刷新和前进后退恢复同一查询。
 * @param query 规范化后的搜索词，空字符串表示浏览全部
 */
function search(query: string) {
  const url = new URL('/', window.location.origin)
  if (query) url.searchParams.set('q', query)
  navigate(url)
}
</script>

<template>
  <div class="app-shell" @click="followLink">
    <header class="site-header">
      <a class="wordmark" href="/" aria-label="LiveFlow 首页">
        <span class="brand-mark" aria-hidden="true"><i></i><i></i><i></i></span>
        LiveFlow
      </a>
      <a v-if="showDevelopmentLink" class="ui-button" href="/dev">开发检查</a>
      <a v-else-if="showDevelopmentCheck" class="ui-button" href="/">返回首页</a>
    </header>
    <DevelopmentCheck v-if="showDevelopmentCheck" />
    <AuthView v-else :room-id="roomId" :show-profile="showProfile" :keyword="keyword"
      :list-return="listReturn" @search="search" />
  </div>
</template>

<style scoped>
.site-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 1rem;
  min-height: 4.75rem;
  padding: 0.75rem clamp(1rem, 3.5vw, 3.5rem);
}

.app-shell:has(.room-page) .site-header {
  background: #111715;
  border-bottom: 1px solid #2a3631;
}

.app-shell:has(.room-page) .wordmark { color: #eef3ef; }
.app-shell:has(.room-page) .site-header .ui-button {
  --text: #eef3ef;
  --surface: #1c2521;
  --line: #3a4942;
  --tint: #26362e;
}

.wordmark {
  display: inline-flex;
  align-items: center;
  gap: 0.65rem;
  color: var(--text);
  font-size: 1.25rem;
  font-weight: 750;
  letter-spacing: -0.045em;
  text-decoration: none;
}

.brand-mark {
  display: grid;
  align-content: center;
  justify-items: center;
  gap: 0.18rem;
  inline-size: 2.15rem;
  block-size: 2.15rem;
  border-radius: 0.7rem;
  background: var(--accent);
}

.brand-mark i { width: 0.8rem; height: 0.13rem; border-radius: 1rem; background: #dcf1db; }
.brand-mark i:first-child { translate: -0.12rem 0; }
.brand-mark i:last-child { translate: 0.12rem 0; }
</style>

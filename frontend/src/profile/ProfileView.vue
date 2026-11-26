<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { ApiRequestError } from '../api/requestJson'
import MyRoom from '../room/MyRoom.vue'
import { avatarOptions, readProfile, saveProfile, type ProfileForm, type UserProfile } from './profileClient'

const props = defineProps<{
  withAccess: <T>(call: (access: string) => Promise<T>) => Promise<T>
  authBusy: boolean
  authError: string
}>()
const emit = defineEmits<{ logout: [] }>()

const profile = ref<UserProfile>()
// 草稿独立于最近一次读取的资料，取消和读取确认结果时各自维护。
const draft = ref<ProfileForm>()
const loading = ref(false)
const saving = ref(false)
const error = ref('')
const fieldErrors = ref<Record<string, string>>({})
const uncertain = ref(false)
const confirmed = ref(false)
let active = true

onMounted(() => void loadProfile())
onBeforeUnmount(() => { active = false })

/**
 * 读取当前会话的资料。确认超时的保存结果时保留编辑草稿。
 * @returns 成功、失败或会话结束后的处理结果
 */
async function loadProfile() {
  loading.value = true
  error.value = ''
  try {
    const current = await props.withAccess(readProfile)
    // 同会话校验由认证层完成。这里只为仍挂载的主页更新状态。
    if (active) {
      profile.value = current
      // 结果不明确时重新读取数据库值，但保留用户的编辑草稿供比较。
      confirmed.value = uncertain.value && Boolean(draft.value)
      uncertain.value = false
    }
  } catch (cause) {
    if (active) {
      error.value = cause instanceof ApiRequestError ? cause.message : '读取资料失败，请重试。'
    }
  } finally {
    loading.value = false
  }
}

/** 从最近读取的资料生成独立草稿，取消编辑时可直接丢弃。 */
function startEditing() {
  if (!profile.value) return
  const { nickname, avatarKey, bio } = profile.value
  draft.value = { nickname, avatarKey, bio }
}

/** 清除草稿和编辑反馈，并保留最近一次确认的服务端资料。 */
function closeEditor() {
  draft.value = undefined
  error.value = ''
  fieldErrors.value = {}
  uncertain.value = false
  confirmed.value = false
}

/**
 * 保存当前草稿，使用同一个输入快照完成可能发生的令牌续期重发。
 * @returns 成功更新展示或保留草稿和失败提示后完成
 */
async function submitProfile() {
  if (!draft.value || saving.value || loading.value || props.authBusy) return
  const submitted = { ...draft.value }
  saving.value = true
  error.value = ''
  fieldErrors.value = {}
  uncertain.value = false
  confirmed.value = false
  try {
    const saved = await props.withAccess(access => saveProfile(access, submitted))
    if (active) {
      profile.value = saved
      closeEditor()
    }
  } catch (cause) {
    if (!active) return
    if (cause instanceof ApiRequestError) {
      fieldErrors.value = cause.fieldErrors ?? {}
      uncertain.value = cause.status === 0 || cause.code === 'PROFILE_SERVICE_UNAVAILABLE' || cause.code === 'HTTP_ERROR'
      error.value = uncertain.value ? '无法确认资料是否已保存，可以先读取当前资料。' : cause.message
    } else {
      error.value = '保存资料失败，请重试。'
    }
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <section class="profile-panel" aria-labelledby="profile-title">
    <a class="directory-link" href="/">← 返回直播间列表</a>
    <header class="profile-header">
      <h1 id="profile-title">个人主页</h1>
      <button class="ui-button" type="button" :disabled="authBusy" @click="emit('logout')">
        {{ authBusy ? '正在退出…' : '退出登录' }}
      </button>
    </header>

    <p v-if="authError" class="ui-feedback ui-feedback--error" role="alert">{{ authError }}</p>
    <p v-if="loading && !profile" role="status">正在读取资料…</p>
    <template v-if="profile">
      <div class="identity">
        <span class="avatar" :data-avatar="profile.avatarKey" aria-hidden="true">{{ [...profile.displayName][0] }}</span>
        <div>
          <h2>{{ profile.displayName }}</h2>
          <p class="username">@{{ profile.username }}</p>
        </div>
      </div>

      <template v-if="!draft">
        <dl class="profile-details">
          <div><dt>个人简介</dt><dd class="bio">{{ profile.bio || '尚未填写简介' }}</dd></div>
        </dl>
        <button type="button" class="ui-button ui-button--primary" :disabled="authBusy" @click="startEditing">编辑资料</button>
      </template>

      <form v-else class="ui-form" :aria-busy="saving || loading" @submit.prevent="submitProfile">
        <fieldset class="edit-fields" :disabled="saving || loading || authBusy">
          <div class="ui-field">
            <label for="nickname">昵称</label>
            <input class="ui-input" id="nickname" v-model="draft.nickname" placeholder="留空使用登录用户名"
              :aria-invalid="Boolean(fieldErrors.nickname)" :aria-describedby="fieldErrors.nickname ? 'nickname-error' : undefined" />
            <p v-if="fieldErrors.nickname" id="nickname-error" class="field-error">{{ fieldErrors.nickname }}</p>
          </div>
          <fieldset class="ui-field avatars" :aria-describedby="fieldErrors.avatarKey ? 'avatar-error' : undefined">
            <legend>头像</legend>
            <div class="avatar-options">
              <label v-for="option in avatarOptions" :key="option.key" class="avatar-option">
                <input v-model="draft.avatarKey" type="radio" name="profile-avatar" :value="option.key" />
                <span class="avatar swatch" :data-avatar="option.key" aria-hidden="true"></span>
                {{ option.label }}
              </label>
            </div>
            <p v-if="fieldErrors.avatarKey" id="avatar-error" class="field-error">{{ fieldErrors.avatarKey }}</p>
          </fieldset>
          <div class="ui-field">
            <label for="bio">个人简介</label>
            <textarea class="ui-input" id="bio" v-model="draft.bio" rows="4" placeholder="最多 160 个字符"
              :aria-invalid="Boolean(fieldErrors.bio)" :aria-describedby="fieldErrors.bio ? 'bio-error' : undefined" />
            <p v-if="fieldErrors.bio" id="bio-error" class="field-error">{{ fieldErrors.bio }}</p>
          </div>
        </fieldset>
        <p v-if="error" class="ui-feedback ui-feedback--error" role="alert">{{ error }}</p>
        <div v-if="confirmed" class="ui-feedback confirmed" role="status">
          <strong>已重新读取当前资料</strong>
          <p>{{ profile.displayName }} · {{ avatarOptions.find(option => option.key === profile?.avatarKey)?.label }}头像</p>
          <p class="bio">{{ profile.bio || '尚未填写简介' }}</p>
        </div>
        <div class="actions">
          <button type="submit" class="ui-button ui-button--primary" :disabled="saving || loading || authBusy">
            {{ saving ? '正在保存…' : '保存资料' }}
          </button>
          <button class="ui-button" type="button" :disabled="saving || loading || authBusy" @click="closeEditor">取消</button>
          <button v-if="uncertain" class="ui-button" type="button" :disabled="loading || saving || authBusy" @click="loadProfile">
            {{ loading ? '正在读取…' : '重新读取资料' }}
          </button>
        </div>
      </form>
    </template>
    <template v-else-if="error">
      <p class="ui-feedback ui-feedback--error" role="alert">{{ error }}</p>
      <button class="ui-button" type="button" :disabled="loading || authBusy" @click="loadProfile">重试读取</button>
    </template>
    <MyRoom :with-access="withAccess" :auth-busy="authBusy" />
  </section>
</template>

<style scoped>
.directory-link { display: inline-block; margin-bottom: 0.75rem; font-size: 0.875rem; text-decoration: none; }
.directory-link:hover { text-decoration: underline; }
.profile-panel {
  width: min(100%, 44rem);
  margin: 0 auto;
  padding: clamp(1rem, 4vw, 2rem);
  border: 1px solid var(--line);
  border-radius: 1.5rem;
  background: var(--surface);
}
.profile-header, .identity { display: flex; align-items: center; gap: 1rem; }
.profile-header { justify-content: space-between; margin-bottom: 1.35rem; }
h1, h2, p, dd { margin: 0; }
h1 { font-size: clamp(1.45rem, 4vw, 2rem); letter-spacing: -0.035em; }
h2 { font-size: clamp(1.25rem, 3vw, 1.75rem); }
h2, dd, .username { overflow-wrap: anywhere; }

.identity {
  padding: clamp(1rem, 4vw, 1.6rem);
  border-radius: 1rem;
  background: var(--tint);
}
.identity > div { min-width: 0; }
.username { color: var(--muted); margin-top: 0.25rem; }
.avatar {
  display: grid;
  flex: none;
  place-items: center;
  width: 3.5rem;
  height: 3.5rem;
  border-radius: 50%;
  background: #d4e2d8;
  color: #244939;
  font-size: 1.3rem;
  font-weight: 700;
}
.avatar[data-avatar="blue"] { background: #dbeaff; color: #184c9c; }
.avatar[data-avatar="green"] { background: #cfecdc; color: #145741; }
.avatar[data-avatar="orange"] { background: #ffe7cb; color: #824714; }

.profile-details { margin: 0 0 1.25rem; }
.profile-details > div { padding: 1rem 0; border-bottom: 1px solid var(--line); }
dt { margin-bottom: 0.35rem; color: var(--muted); font-size: 0.85rem; font-weight: 650; }
.bio { white-space: pre-wrap; overflow-wrap: anywhere; }

form { margin-top: 1.25rem; }
fieldset { min-inline-size: 0; margin: 0; padding: 0; border: 0; }
.edit-fields { display: grid; gap: 1rem; }
.avatars legend { margin-bottom: 0.4rem; padding: 0; }
textarea { resize: vertical; }
.avatar-options, .actions { display: flex; flex-wrap: wrap; gap: 0.5rem; }
.avatar-option {
  display: inline-flex;
  min-height: 2.75rem;
  align-items: center;
  gap: 0.4rem;
  padding: 0.45rem 0.7rem;
  border: 1px solid var(--line);
  border-radius: 999px;
  cursor: pointer;
}
.avatar-option:has(input:checked) { border-color: var(--accent); background: var(--tint); }
.avatar-option input { margin: 0; accent-color: var(--accent); }
.swatch { width: 1rem; height: 1rem; }
.field-error { color: var(--danger); font-size: 0.875rem; }
.confirmed p { margin-top: 0.3rem; }
.profile-panel > .ui-feedback { margin-bottom: 0.8rem; }
</style>

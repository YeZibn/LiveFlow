<script setup lang="ts">
import { onBeforeUnmount, ref } from 'vue'
import { ApiRequestError } from '../api/requestJson'
import { authClient } from './authClient'

const emit = defineEmits<{ registered: [username: string]; back: [] }>()
const username = ref('')
const password = ref('')
const confirmation = ref('')
const showPassword = ref(false)
const showConfirmation = ref(false)
const submitting = ref(false)
const error = ref('')
let active = true

onBeforeUnmount(() => {
  active = false
  password.value = ''
  confirmation.value = ''
})

/**
 * 校验当前注册表单。
 * @returns 第一条可操作的输入提示，空字符串表示可以提交
 */
function validationMessage(): string {
  if (!/^[A-Za-z0-9][A-Za-z0-9_]{2,31}$/.test(username.value)) {
    return '账号需为 3–32 位字母、数字或下划线，首位为字母或数字。'
  }
  if (password.value.length < 10 || password.value.length > 25 || /[^!-~]/.test(password.value)
    || !/[A-Z]/.test(password.value) || !/[a-z]/.test(password.value)
    || !/[0-9]/.test(password.value) || !/[^A-Za-z0-9]/.test(password.value)) {
    return '密码需为 10–25 位，包含大小写字母、数字和符号。'
  }
  if (password.value !== confirmation.value) {
    return '两次输入的密码不一致。'
  }
  return ''
}

/**
 * 提交一次注册。成功后清除密码，失败时保留用户的输入与错误反馈。
 * @returns 完成当前提交及页面反馈
 */
async function submit() {
  if (submitting.value) return
  error.value = validationMessage()
  if (error.value) return

  submitting.value = true
  try {
    const account = await authClient.register(username.value, password.value)
    if (active) {
      // 注册接口只创建账号，回到登录页后才建立会话。
      password.value = ''
      confirmation.value = ''
      emit('registered', account.username)
    }
  } catch (cause) {
    if (!active) return
    if (cause instanceof ApiRequestError && cause.retryAfterSeconds !== undefined) {
      error.value = `注册请求过多，请在 ${cause.retryAfterSeconds} 秒后重试。`
    } else if (cause instanceof ApiRequestError && cause.status === 0) {
      error.value = '无法确认注册结果。你可以先返回登录，或稍后再试。'
    } else {
      error.value = cause instanceof ApiRequestError ? cause.message : '注册暂不可用，请稍后再试。'
    }
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <h1 id="auth-title">注册 LiveFlow</h1>
  <form :aria-busy="submitting" novalidate @submit.prevent="submit">
    <div class="field">
      <label for="register-username">账号</label>
      <input id="register-username" v-model="username" autocomplete="username" autocapitalize="none"
        spellcheck="false" :disabled="submitting" required />
    </div>
    <div class="field">
      <label for="register-password">密码</label>
      <div class="password-field">
        <input id="register-password" v-model="password" :type="showPassword ? 'text' : 'password'"
          autocomplete="new-password" aria-describedby="password-rules" :disabled="submitting" required />
        <button type="button" aria-controls="register-password" :aria-label="showPassword ? '隐藏密码' : '显示密码'"
          :disabled="submitting" @click="showPassword = !showPassword">
          {{ showPassword ? '隐藏' : '显示' }}
        </button>
      </div>
      <small id="password-rules">10–25 位，包含大小写字母、数字和符号，不能有空格。</small>
    </div>
    <div class="field">
      <label for="register-confirmation">确认密码</label>
      <div class="password-field">
        <input id="register-confirmation" v-model="confirmation" :type="showConfirmation ? 'text' : 'password'"
          autocomplete="new-password" :disabled="submitting" required />
        <button type="button" aria-controls="register-confirmation"
          :aria-label="showConfirmation ? '隐藏确认密码' : '显示确认密码'"
          :disabled="submitting" @click="showConfirmation = !showConfirmation">
          {{ showConfirmation ? '隐藏' : '显示' }}
        </button>
      </div>
    </div>
    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <button class="primary" :disabled="submitting" type="submit">{{ submitting ? '正在注册…' : '注册' }}</button>
    <button type="button" :disabled="submitting" @click="emit('back')">返回登录</button>
  </form>
</template>

<style scoped>
h1 { margin: 0 0 1.6rem; font-size: 1.85rem; line-height: 1.25; }
form, .field { display: grid; gap: 0.6rem; }
form { gap: 1.1rem; }
label { font-size: 0.9rem; font-weight: 650; }
small { color: #536781; }
.password-field { display: flex; gap: 0.5rem; }
input {
  box-sizing: border-box;
  min-width: 0;
  width: 100%;
  padding: 0.68rem 0.75rem;
  border: 1px solid #b7c6d9;
  border-radius: 0.45rem;
  color: inherit;
  font: inherit;
}
button {
  min-height: 2.65rem;
  padding: 0.55rem 0.85rem;
  border: 1px solid #b7c6d9;
  border-radius: 0.45rem;
  color: #264876;
  background: #fff;
  font: inherit;
  font-weight: 650;
  cursor: pointer;
  white-space: nowrap;
}
.primary { color: #fff; background: #245cbd; border-color: #245cbd; }
button:disabled { opacity: 0.65; cursor: wait; }
:focus-visible { outline: 2px solid #2464d1; outline-offset: 3px; }
.error { margin: 0; padding: 0.65rem; border-radius: 0.4rem; background: #fff1ef; color: #97281f; }
</style>

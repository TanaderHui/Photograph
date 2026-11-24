<script setup lang="ts">
import { computed, ref } from 'vue'

interface Tokens { accessToken: string; refreshToken: string }
interface Applicant { id: string; nickname: string; city: string; bio: string; version: number }
interface Page { items: Applicant[]; nextCursor: string | null }

const phone = ref('')
const code = ref('')
const challengeId = ref('')
const tokens = ref<Tokens | null>(null)
const operator = ref(false)
const items = ref<Applicant[]>([])
const nextCursor = ref<string | null>(null)
const reasons = ref<Record<string, string>>({})
const busy = ref(false)
const message = ref('')
const signedIn = computed(() => tokens.value !== null && operator.value)

async function request(path: string, method = 'GET', data?: unknown, authenticated = false, retry = true): Promise<Response> {
  const headers: Record<string, string> = {}
  if (data !== undefined) headers['Content-Type'] = 'application/json'
  if (authenticated && tokens.value) headers.Authorization = `Bearer ${tokens.value.accessToken}`
  const response = await fetch(`/api/v1${path}`, {
    method, headers, body: data === undefined ? undefined : JSON.stringify(data), signal: AbortSignal.timeout(10000),
  })
  if (response.status === 401 && authenticated && tokens.value && retry) {
    try {
      const refreshed = await request('/auth/sessions/refresh', 'POST', { refreshToken: tokens.value.refreshToken })
      tokens.value = await refreshed.json() as Tokens
    } catch {
      clearSession()
      throw new Error('登录已过期，请重新登录。')
    }
    return request(path, method, data, true, false)
  }
  if (!response.ok) {
    const problem = await response.json().catch(() => null) as { message?: string } | null
    throw new Error(problem?.message ?? `请求失败（${response.status}）。`)
  }
  return response
}

async function run(work: () => Promise<void>) {
  if (busy.value) return
  busy.value = true
  message.value = ''
  try { await work() }
  catch (error) { message.value = error instanceof Error ? error.message : '请求失败，请重试。' }
  finally { busy.value = false }
}

function sendCode() {
  return run(async () => {
    const response = await request('/auth/otp/challenges', 'POST', { phone: phone.value })
    challengeId.value = (await response.json() as { challengeId: string }).challengeId
    code.value = ''
    message.value = '验证码已发出。'
  })
}

function login() {
  return run(async () => {
    const response = await request('/auth/sessions', 'POST', { challengeId: challengeId.value, code: code.value })
    tokens.value = await response.json() as Tokens
    const profile = await request('/me/profile', 'GET', undefined, true)
    operator.value = (await profile.json() as { operator: boolean }).operator
    if (!operator.value) {
      await request('/auth/sessions/logout', 'POST', undefined, true)
      clearSession()
      throw new Error('当前账号没有运营权限。')
    }
    code.value = ''
    challengeId.value = ''
    await loadQueue()
  })
}

function clearSession() {
  tokens.value = null
  operator.value = false
  items.value = []
  nextCursor.value = null
  reasons.value = {}
}

function logout() {
  return run(async () => {
    await request('/auth/sessions/logout', 'POST', undefined, true)
    clearSession()
  })
}

async function loadQueue(append = false) {
  const cursor = append && nextCursor.value ? `?cursor=${encodeURIComponent(nextCursor.value)}` : ''
  const response = await request(`/admin/photographers${cursor}`, 'GET', undefined, true)
  const page = await response.json() as Page
  items.value = append ? [...items.value, ...page.items] : page.items
  nextCursor.value = page.nextCursor
}

function decide(item: Applicant, decision: 'APPROVED' | 'REJECTED') {
  return run(async () => {
    await request(`/admin/photographers/${item.id}/decision`, 'POST', {
      decision, reason: reasons.value[item.id]?.trim(), expectedVersion: item.version,
    }, true)
    items.value = items.value.filter(candidate => candidate.id !== item.id)
    delete reasons.value[item.id]
    message.value = '审核结果已保存。'
  })
}
</script>

<template>
  <section aria-labelledby="review-heading" class="review">
    <h2 id="review-heading">摄影师入驻审核</h2>
    <form v-if="!signedIn" class="operator-login" @submit.prevent="login">
      <label>运营手机号
        <input v-model="phone" type="tel" autocomplete="tel" required :disabled="busy"
               @input="challengeId = ''; code = ''">
      </label>
      <button type="button" :disabled="busy || !phone.trim()" @click="sendCode">
        {{ challengeId ? '重新发送' : '发送验证码' }}
      </button>
      <label v-if="challengeId">验证码
        <input v-model="code" inputmode="numeric" autocomplete="one-time-code" pattern="[0-9]{6}" required>
      </label>
      <button v-if="challengeId" type="submit" :disabled="busy || !/^[0-9]{6}$/.test(code)">登录</button>
    </form>
    <template v-else>
      <div class="review-actions">
        <button type="button" :disabled="busy" @click="run(() => loadQueue())">刷新申请</button>
        <button type="button" :disabled="busy" @click="logout">退出登录</button>
      </div>
      <p v-if="items.length === 0 && !busy">当前页暂无待审核申请。</p>
      <article v-for="item in items" :key="item.id">
        <h3>{{ item.nickname }} · {{ item.city }}</h3>
        <p>{{ item.bio || '暂无个人介绍' }}</p>
        <label>审核理由
          <textarea v-model="reasons[item.id]" maxlength="500" rows="2" :disabled="busy"></textarea>
        </label>
        <div class="review-actions">
          <button type="button" :disabled="busy || !reasons[item.id]?.trim()" @click="decide(item, 'APPROVED')">通过</button>
          <button type="button" :disabled="busy || !reasons[item.id]?.trim()" @click="decide(item, 'REJECTED')">驳回</button>
        </div>
      </article>
      <button v-if="nextCursor" type="button" :disabled="busy" @click="run(() => loadQueue(true))">加载更多</button>
    </template>
    <p v-if="message" role="status">{{ message }}</p>
  </section>
</template>

<style scoped>
.operator-login, .review-actions {
  display: flex;
  flex-wrap: wrap;
  align-items: end;
  gap: .65rem;
  margin-top: 1rem;
}
label { display: grid; gap: .35rem; }
input, textarea {
  box-sizing: border-box;
  font: inherit;
  color: inherit;
  background: #fffdf9;
  border: 1px solid #b2a399;
  border-radius: .375rem;
  padding: .5rem .65rem;
}
article {
  margin: 1.2rem 0;
  padding-top: 1rem;
  border-top: 1px solid #e5dbcf;
}
article h3 { margin: 0; }
article p { margin: .5rem 0 1rem; white-space: pre-wrap; overflow-wrap: anywhere; }
</style>

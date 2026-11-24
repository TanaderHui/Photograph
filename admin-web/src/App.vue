<script setup lang="ts">
import { onMounted, ref } from 'vue'
import PhotographerReview from './PhotographerReview.vue'

interface Style {
  id: string
  name: string
}

const checking = ref(true)
const apiReady = ref<boolean | null>(null)
const styles = ref<Style[] | null>(null)

async function fetchJson<T>(path: string): Promise<T> {
  const response = await fetch(path, { signal: AbortSignal.timeout(5000) })
  if (!response.ok) {
    throw new Error(`HTTP ${response.status}`)
  }
  return await response.json() as T
}

async function refresh() {
  checking.value = true
  const [health, catalogue] = await Promise.allSettled([
    fetchJson<{ status: string }>('/actuator/health/readiness'),
    fetchJson<Style[]>('/api/v1/styles'),
  ])
  apiReady.value = health.status === 'fulfilled' && health.value.status === 'UP'
  styles.value = catalogue.status === 'fulfilled' ? catalogue.value : null
  checking.value = false
}

onMounted(refresh)
</script>

<template>
  <main>
    <header>
      <h1>拾光永恒运营端</h1>
      <button type="button" :disabled="checking" @click="refresh">刷新</button>
    </header>
    <section aria-labelledby="service-heading">
      <h2 id="service-heading">服务状态</h2>
      <p role="status">{{ checking ? '正在检查' : apiReady ? '服务可用' : '服务暂不可用' }}</p>
    </section>
    <section aria-labelledby="styles-heading" :aria-busy="checking">
      <h2 id="styles-heading">拍摄风格</h2>
      <p v-if="checking">正在加载风格词表</p>
      <p v-else-if="styles === null">暂时无法加载风格词表。</p>
      <p v-else-if="styles.length === 0">暂无拍摄风格。</p>
      <ul v-else>
        <li v-for="style in styles" :key="style.id">{{ style.name }}</li>
      </ul>
    </section>
    <PhotographerReview />
  </main>
</template>

<style>
:root {
  font-family: system-ui, "Microsoft YaHei", sans-serif;
  color: #2b2520;
  background: #f7f3ed;
}
body {
  margin: 0;
}
main {
  max-width: 52rem;
  margin: 0 auto;
  padding: 3rem 1.25rem;
}
header {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 1rem;
  margin-bottom: 2rem;
}
h1 {
  font-size: clamp(1.65rem, 4vw, 2.25rem);
  margin: 0;
}
section {
  background: #fffdf9;
  border: 1px solid #e5dbcf;
  border-radius: 0.75rem;
  padding: 1.25rem 1.5rem;
  margin-bottom: 1rem;
}
h2 {
  font-size: 1.125rem;
  margin: 0;
}
section p {
  margin-bottom: 0;
}
ul {
  display: flex;
  flex-wrap: wrap;
  gap: 0.75rem;
  padding: 0;
  margin: 1rem 0 0;
  list-style: none;
}
li {
  padding: 0.4rem 0.75rem;
  background: #f1e8dc;
  border-radius: 0.375rem;
}
button {
  font: inherit;
  color: white;
  background: #754935;
  border: 0;
  border-radius: 0.5rem;
  padding: 0.55rem 1.1rem;
  cursor: pointer;
}
button:disabled {
  cursor: default;
  background: #6f655d;
}
</style>

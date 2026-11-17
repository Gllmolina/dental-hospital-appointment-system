<script setup>
import {onMounted, reactive, ref} from 'vue'
import {api} from '../api.js'

const sessions = ref([]), active = ref(null), messages = ref([]), question = ref('')
const recommendations = ref([]), departments = ref([]), departmentId = ref(''), tag = ref('')
const busy = ref(false), message = ref(''), failed = ref(false)

async function refresh() {
  try {
    const [s, d] = await Promise.all([api.get('/ai/sessions'), api.get('/departments')])
    sessions.value = s.items
    departments.value = d.items
    if (active.value) messages.value = (await api.get(`/ai/sessions/${active.value}`)).messages
  } catch (error) {
    failed.value = true
    message.value = error.message
  }
}

async function run(task) {
  busy.value = true
  failed.value = false
  try {
    await task()
    await refresh()
  } catch (error) {
    failed.value = true
    message.value = error.message
  } finally {
    busy.value = false
  }
}

function createSession() {
  run(async () => {
    active.value = (await api.post('/ai/sessions', {title: '新会话'})).id
    messages.value = []
  })
}

function openSession(id) {
  run(async () => {
    active.value = id
    messages.value = (await api.get(`/ai/sessions/${id}`)).messages
  })
}

function send() {
  if (!active.value || !question.value.trim()) return
  run(async () => {
    const result = await api.post('/ai/chat', {sessionId: String(active.value), question: question.value.trim()})
    message.value = `回答来源：${result.source}。${result.disclaimer}`
    question.value = ''
  })
}

function recommend() {
  run(async () => {
    const params = {limit: 5}
    if (departmentId.value) params.departmentId = departmentId.value
    if (tag.value.trim()) params.tag = tag.value.trim()
    recommendations.value = (await api.get('/recommend/doctors', {params})).items
  })
}

onMounted(refresh)
</script>
<template>
  <p class="status" :class="{error:failed}" role="status" aria-live="polite">{{ message }}</p>
  <section class="panel">
    <h2>知识库问答</h2>
    <p>回答仅供就医参考，不能替代医生诊断。</p>
    <button class="quiet" type="button" :disabled="busy" @click="createSession">新建会话</button>
    <div class="row"><button v-for="s in sessions" :key="s.id" class="quiet" type="button" :disabled="busy"
                             :aria-pressed="active === s.id" @click="openSession(s.id)">{{ s.title }} #{{ s.id }}</button></div>
    <div v-if="active">
      <article v-for="item in messages" :key="item.id" class="record">
        <strong>{{ item.role === 'USER' ? '我' : `助手 · ${item.source}` }}</strong><p>{{ item.content }}</p>
        <div v-if="item.role === 'ASSISTANT'" class="row">
          <button class="quiet" type="button" :disabled="busy" @click="run(() => api.post(`/ai/messages/${item.id}/feedback`, {helpful: true}))">有帮助</button>
          <button class="quiet" type="button" :disabled="busy" @click="run(() => api.post(`/ai/messages/${item.id}/feedback`, {helpful: false}))">无帮助</button>
        </div>
      </article>
      <form action="/hospital/api/ai/chat" method="post" aria-labelledby="ask-title" @submit.prevent="send">
        <h3 id="ask-title">提问</h3><label for="question">问题（最多 500 字）</label>
        <textarea id="question" v-model.trim="question" name="question" maxlength="500" required></textarea>
        <button class="primary" type="submit" :disabled="busy">发送问题</button>
      </form>
    </div>
  </section>
  <section class="panel">
    <form action="/hospital/api/recommend/doctors" method="get" aria-labelledby="recommend-title" @submit.prevent="recommend">
      <h2 id="recommend-title">医生推荐</h2>
      <label for="recommend-department">科室</label><select id="recommend-department" v-model="departmentId" name="departmentId">
        <option value="">不限</option><option v-for="d in departments" :key="d.id" :value="d.id">{{ d.name }}</option>
      </select>
      <label for="recommend-tag">擅长方向（可选）</label><input id="recommend-tag" v-model.trim="tag" name="tag" maxlength="60">
      <button class="primary" type="submit" :disabled="busy">查看推荐</button>
    </form>
    <p v-if="!recommendations.length">暂无推荐结果，请查询可约医生。</p>
    <article v-for="item in recommendations" :key="item.doctorId" class="record">
      <strong>{{ item.name }} · {{ item.departmentName }} · 推荐分 {{ item.score }}</strong>
      <p>{{ item.reasons.join('；') }}</p><small>最近可约：{{ item.nextAvailableAt }}</small>
    </article>
  </section>
</template>

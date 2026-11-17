<script setup>
import {onMounted, reactive, ref} from 'vue'
import {api} from '../api.js'

const items = ref([]), form = reactive({category: '', question: '', answer: '', keywords: ''})
const busy = ref(false), message = ref(''), failed = ref(false)

async function refresh() {
  try { items.value = (await api.get('/admin/knowledge-items')).items }
  catch (error) { failed.value = true; message.value = error.message }
}

async function run(task) {
  busy.value = true
  failed.value = false
  try { await task(); message.value = '操作成功'; await refresh() }
  catch (error) { failed.value = true; message.value = error.message }
  finally { busy.value = false }
}

function edit(item) {
  const category = prompt('分类', item.category)
  if (category === null) return
  const question = prompt('问题', item.question)
  if (question === null) return
  const answer = prompt('审核答案', item.answer)
  if (answer === null) return
  const keywords = prompt('关键词（逗号分隔）', item.keywords)
  if (keywords === null) return
  run(() => api.put(`/admin/knowledge-items/${item.id}`, {category, question, answer, keywords}))
}

onMounted(refresh)
</script>
<template>
  <section class="panel">
    <h2>知识库管理</h2>
    <p class="status" :class="{error:failed}" role="status" aria-live="polite">{{ message }}</p>
    <form action="/hospital/api/admin/knowledge-items" method="post" aria-labelledby="knowledge-title"
          @submit.prevent="run(() => api.post('/admin/knowledge-items', {...form}))">
      <h3 id="knowledge-title">录入审核内容</h3>
      <label for="knowledge-category">分类</label><input id="knowledge-category" v-model.trim="form.category" name="category" maxlength="80" required>
      <label for="knowledge-question">问题</label><textarea id="knowledge-question" v-model.trim="form.question" name="question" maxlength="500" required></textarea>
      <label for="knowledge-answer">答案</label><textarea id="knowledge-answer" v-model.trim="form.answer" name="answer" maxlength="2000" required></textarea>
      <label for="knowledge-keywords">关键词（逗号分隔）</label><input id="knowledge-keywords" v-model.trim="form.keywords" name="keywords" maxlength="500">
      <button class="primary" type="submit" :disabled="busy">保存草稿</button>
    </form>
    <article v-for="item in items" :key="item.id" class="record">
      <strong>#{{ item.id }} · {{ item.category }} · {{ item.status }} · 版本 {{ item.version }}</strong>
      <p>{{ item.question }}</p><p>{{ item.answer }}</p>
      <div class="row"><button class="quiet" type="button" :disabled="busy" @click="edit(item)">修订</button>
        <button v-if="item.status === 'DRAFT'" class="primary" type="button" :disabled="busy"
                @click="run(() => api.put(`/admin/knowledge-items/${item.id}/publication`))">审核发布</button></div>
    </article>
  </section>
</template>

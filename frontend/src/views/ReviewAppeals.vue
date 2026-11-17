<script setup>
import {onMounted, ref} from 'vue'
import {api} from '../api.js'

const items = ref([]), notes = ref({}), busy = ref(false), message = ref(''), failed = ref(false)

async function refresh() {
  try { items.value = (await api.get('/admin/review-appeals')).items }
  catch (error) { failed.value = true; message.value = error.message }
}

async function run(task) {
  busy.value = true
  failed.value = false
  try { await task(); message.value = '处理成功'; await refresh() }
  catch (error) { failed.value = true; message.value = error.message }
  finally { busy.value = false }
}

onMounted(refresh)
</script>
<template>
  <section class="panel">
    <h2>评价申诉</h2>
    <p class="status" :class="{error:failed}" role="status" aria-live="polite">{{ message }}</p>
    <p v-if="!items.length">暂无申诉。</p>
    <article v-for="item in items" :key="item.id" class="record">
      <strong>#{{ item.id }} · {{ item.doctor_name }} · {{ item.status }}</strong>
      <p>评价：{{ item.content || '无文字评价' }}（{{ item.overall_score }} 分）</p>
      <p>申诉原因：{{ item.reason }}</p><p v-if="item.decision_note">处理说明：{{ item.decision_note }}</p>
      <div v-if="item.status === 'PENDING'">
        <label :for="`appeal-note-${item.id}`">处理说明（必填）</label>
        <textarea :id="`appeal-note-${item.id}`" v-model.trim="notes[item.id]" name="decisionNote" maxlength="500" required></textarea>
        <div class="row">
          <button class="primary" type="button" :disabled="busy || !notes[item.id]"
                  @click="run(() => api.put(`/admin/review-appeals/${item.id}/decision`, {decision: 'APPROVED', decisionNote: notes[item.id]}))">批准并隐藏评价</button>
          <button class="quiet" type="button" :disabled="busy || !notes[item.id]"
                  @click="run(() => api.put(`/admin/review-appeals/${item.id}/decision`, {decision: 'REJECTED', decisionNote: notes[item.id]}))">驳回</button>
        </div>
      </div>
    </article>
  </section>
</template>

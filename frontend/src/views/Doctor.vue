<script setup>
import {onMounted, onUnmounted, reactive, ref} from 'vue'
import {api, key} from '../api.js'
import LeaveRequests from './LeaveRequests.vue'

const appointments = ref([]), catalog = ref([]), reviews = ref([]), selected = ref(null), busy = ref(false), message = ref(''),
    failed = ref(false)
const reply = ref({})
const appealReason = ref({})
const profile = reactive({specialty: '', intro: ''}), context = ref(null)
const avatarUrl = ref('')
async function loadAvatar() {
  const blob = await api.get('/doctor/avatar', {responseType: 'blob'})
  if (avatarUrl.value) URL.revokeObjectURL(avatarUrl.value)
  avatarUrl.value = URL.createObjectURL(blob)
}
async function uploadAvatar(event) {
  const file = event.target.files?.[0]
  if (!file) return
  if (file.size > 2_000_000) { failed.value = true; message.value = '头像须小于 2 MB'; return }
  const form = new FormData(); form.append('file', file)
  busy.value = true; failed.value = false
  try { await api.post('/doctor/avatar', form); await loadAvatar(); message.value = '头像已更新' }
  catch (error) { failed.value = true; message.value = error.message }
  finally { busy.value = false; event.target.value = '' }
}
const recordVersion = ref(0), recordId = ref(null), correctionReason = ref('')
const record = reactive({
  chiefComplaint: '',
  examination: '',
  assessment: '',
  treatmentNote: '',
  advice: '',
  followUpDate: '',
  itemId: '',
  quantity: 1
})

async function refresh() {
  try {
    const [a, c, r, p] = await Promise.all([api.get('/doctor/appointments'), api.get('/doctor/treatment-items'), api.get('/doctor/reviews'), api.get('/doctor/profile')]);
    appointments.value = a.items;
    catalog.value = c.items;
    reviews.value = r.items;
    profile.specialty = p.profile.specialty || '';
    profile.intro = p.profile.intro || ''
    if (p.profile.avatar_key) await loadAvatar()
  } catch (e) {
    message.value = e.message;
    failed.value = true
  }
}

async function act(task) {
  busy.value = true;
  failed.value = false;
  try {
    await task();
    message.value = '操作成功';
    await refresh()
  } catch (e) {
    message.value = e.message;
    failed.value = true
  } finally {
    busy.value = false
  }
}

async function submit() {
  if (!selected.value) return;
  const body = recordBody();
  if (selected.value.status === 'COMPLETED') {
    await act(() => api.post(`/doctor/medical-records/${recordId.value}/corrections`, {...body, version: recordVersion.value, reason: correctionReason.value}));
    if (!failed.value) selected.value = null
    return
  }
  await act(async () => {
    const saved = await api.put(`/doctor/appointments/${selected.value.id}/medical-record/draft`, {...body, version: recordVersion.value});
    recordVersion.value = saved.version;
    await api.post(`/doctor/appointments/${selected.value.id}/medical-record/submit`, {version: saved.version}, {headers: {'Idempotency-Key': key()}})
  });
  if (!failed.value) selected.value = null
}

function recordBody() {
  const body = {
    chiefComplaint: record.chiefComplaint,
    examination: record.examination,
    assessment: record.assessment,
    treatmentNote: record.treatmentNote,
    advice: record.advice,
    items: record.itemId ? [{itemId: String(record.itemId), quantity: String(record.quantity)}] : []
  };
  if (record.followUpDate) body.followUpDate = record.followUpDate;
  return body
}

async function openRecord(appointment) {
  selected.value = appointment
  try {
    context.value = ['CHECKED_IN', 'IN_VISIT'].includes(appointment.status)
      ? await api.get(`/doctor/appointments/${appointment.id}/context`) : null
    const found = (await api.get(`/doctor/appointments/${appointment.id}/medical-record/draft`)).record
    recordId.value = found.id || null
    recordVersion.value = found.version || 0
    record.chiefComplaint = found.chief_complaint || ''
    record.examination = found.examination || ''
    record.assessment = found.assessment || ''
    record.treatmentNote = found.treatment_note || ''
    record.advice = found.advice || ''
    record.followUpDate = found.follow_up_date || ''
    const items = found.draft_items_json ? JSON.parse(found.draft_items_json) : []
    record.itemId = items[0]?.itemId || ''
    record.quantity = Number(items[0]?.quantity || 1)
    correctionReason.value = ''
  } catch (error) {
    failed.value = true
    message.value = error.message
  }
}

async function saveDraft() {
  if (!selected.value || selected.value.status === 'COMPLETED') return
  await act(async () => {
    const saved = await api.put(`/doctor/appointments/${selected.value.id}/medical-record/draft`, {...recordBody(), version: recordVersion.value});
    recordVersion.value = saved.version
    recordId.value = saved.recordId
  })
}

const time = value => value ? new Date(value.endsWith('Z') ? value : value + 'Z').toLocaleString('zh-CN', {
  timeZone: 'Asia/Shanghai',
  hour12: false
}) : ''
onMounted(refresh)
onUnmounted(() => { if (avatarUrl.value) URL.revokeObjectURL(avatarUrl.value) })
</script>
<template>
  <h1>医生工作台</h1>
  <p class="status" :class="{error:failed}" role="status" aria-live="polite">{{ message }}</p>
  <section class="panel"><h2>头像</h2>
    <img v-if="avatarUrl" :src="avatarUrl" alt="我的头像" width="96" height="96" class="avatar">
    <label for="doctor-avatar">上传 JPEG、PNG 或 WebP 图片，最大 2 MB</label>
    <input id="doctor-avatar" type="file" accept="image/jpeg,image/png,image/webp" :disabled="busy" @change="uploadAvatar">
  </section>
  <section class="panel"><form action="/hospital/api/doctor/profile" method="post" aria-labelledby="doctor-profile-heading"
                       @submit.prevent="act(() => api.put('/doctor/profile', {...profile}))">
    <h2 id="doctor-profile-heading">医生资料</h2>
    <label for="specialty">擅长方向</label><textarea id="specialty" v-model.trim="profile.specialty" name="specialty" maxlength="500"></textarea>
    <label for="intro">个人介绍</label><textarea id="intro" v-model.trim="profile.intro" name="intro" maxlength="1000"></textarea>
    <button class="primary" type="submit" :disabled="busy">保存医生资料</button>
  </form></section>
  <section class="panel"><h2>今日预约</h2>
    <p v-if="!appointments.length">今日暂无预约。</p>
    <div v-for="a in appointments" :key="a.id" class="record"><strong>预约 {{ a.appointment_no }} · {{
        a.status
      }}</strong><small>{{ time(a.starts_at) }} · ID {{ a.id }}</small>
      <div class="row">
        <button v-if="a.status==='CHECKED_IN'" type="button" class="quiet" :disabled="busy"
                @click="act(()=>api.put(`/doctor/appointments/${a.id}/call`))">叫号
        </button>
        <button v-if="a.status==='CHECKED_IN'" type="button" class="primary" :disabled="busy"
                @click="act(()=>api.put(`/doctor/appointments/${a.id}/start`))">开始接诊
        </button>
        <button v-if="['CHECKED_IN','IN_VISIT'].includes(a.status)" type="button" class="primary" @click="openRecord(a)">填写记录</button>
        <button v-if="a.status==='COMPLETED'" type="button" class="quiet" @click="openRecord(a)">修订记录</button>
      </div>
    </div>
  </section>
  <section v-if="selected" class="panel">
    <div v-if="context" class="record">
      <h2>接诊资料 · {{ context.patient.name }}</h2>
      <p>过敏史：{{ context.healthArchive.allergy_history || '未填写' }}</p>
      <p>既往史：{{ context.healthArchive.past_history || '未填写' }}</p>
      <p v-for="prior in context.priorRecords" :key="prior.id">既往记录：{{ prior.chief_complaint }} · {{ prior.advice }}</p>
    </div>
    <form aria-labelledby="record-heading" action="/hospital/api/doctor/appointments" method="post"
          @submit.prevent="submit"><h2 id="record-heading">{{ selected.status === 'COMPLETED' ? '修订记录' : '就诊记录' }} · 预约 {{ selected.id }}</h2>
      <p>当前版本 {{ recordVersion }}。草稿仅医生可见。</p>
      <div class="grid">
        <div><label for="complaint">主诉</label><textarea id="complaint" v-model.trim="record.chiefComplaint"
                                                          name="chiefComplaint" maxlength="2000"
                                                          required></textarea><label
            for="examination">检查结果</label><textarea id="examination" v-model.trim="record.examination"
                                                        name="examination" maxlength="2000" required></textarea><label
            for="assessment">诊断评估</label><textarea id="assessment" v-model.trim="record.assessment"
                                                       name="assessment" maxlength="2000"></textarea></div>
        <div><label for="treatment-note">处理过程</label><textarea id="treatment-note"
                                                                   v-model.trim="record.treatmentNote"
                                                                   name="treatmentNote"
                                                                   maxlength="2000"></textarea><label
            for="advice">医嘱</label><textarea id="advice" v-model.trim="record.advice" name="advice" maxlength="2000"
                                               required></textarea><label for="follow-up">建议复诊日期</label><input
            id="follow-up" v-model="record.followUpDate" name="followUpDate" type="date"></div>
      </div>
      <label v-if="selected.status === 'COMPLETED'" for="correction-reason">修订原因</label>
      <textarea v-if="selected.status === 'COMPLETED'" id="correction-reason" v-model.trim="correctionReason" name="reason" maxlength="500" required></textarea>
      <fieldset v-if="selected.status !== 'COMPLETED'">
        <legend>诊疗收费项目（可选）</legend>
        <label for="item">项目</label><select id="item" v-model="record.itemId" name="itemId">
        <option value="">无</option>
        <option v-for="i in catalog" :key="i.id" :value="i.id">{{ i.name }} · {{ i.fee }} 元</option>
      </select><label for="quantity">数量</label><input id="quantity" v-model.number="record.quantity" name="quantity"
                                                        type="number" min="1" max="20"></fieldset>
      <button v-if="selected.status !== 'COMPLETED'" class="quiet" type="button" :disabled="busy" @click="saveDraft">保存草稿</button>
      <button v-if="selected.status !== 'CHECKED_IN'" class="primary" type="submit" :disabled="busy">{{ selected.status === 'COMPLETED' ? '提交修订' : '提交记录并完成接诊' }}</button>
    </form>
  </section>
  <section class="panel">
    <h2>患者评价</h2>
    <p v-if="!reviews.length">暂无评价。</p>
    <article v-for="item in reviews" :key="item.id" class="record">
      <strong>{{ item.display_name }} · 总体 {{ item.overall_score }} 分</strong>
      <p>{{ item.content || '无文字评价' }}</p>
      <p v-if="item.reply">我的回复：{{ item.reply }}</p>
      <form v-else :aria-labelledby="`review-reply-${item.id}`" :action="`/hospital/api/doctor/reviews/${item.id}/reply`"
            method="post" @submit.prevent="act(() => api.post(`/doctor/reviews/${item.id}/reply`, {content: reply[item.id]}))">
        <h3 :id="`review-reply-${item.id}`">回复评价</h3>
        <label :for="`reply-${item.id}`">回复内容</label>
        <textarea :id="`reply-${item.id}`" v-model.trim="reply[item.id]" name="content" maxlength="500" required></textarea>
        <button class="primary" type="submit" :disabled="busy">发送回复</button>
      </form>
      <p v-if="item.appeal_status">申诉状态：{{ item.appeal_status }}</p>
      <form v-else :aria-labelledby="`review-appeal-${item.id}`" :action="`/hospital/api/doctor/reviews/${item.id}/appeal`"
            method="post" @submit.prevent="act(() => api.post(`/doctor/reviews/${item.id}/appeal`, {reason: appealReason[item.id]}))">
        <h3 :id="`review-appeal-${item.id}`">申请评价复核</h3>
        <label :for="`appeal-${item.id}`">申诉原因</label>
        <textarea :id="`appeal-${item.id}`" v-model.trim="appealReason[item.id]" name="reason" maxlength="500" required></textarea>
        <button class="quiet" type="submit" :disabled="busy">提交申诉</button>
      </form>
    </article>
  </section>
  <LeaveRequests/>
</template>
<style scoped>.record small {
  display: block
}

.record button {
  margin-top: .5rem
}

fieldset {
  border: 1px solid #c8d9de;
  border-radius: 8px;
  margin: 1rem 0;
  padding: 1rem
}</style>

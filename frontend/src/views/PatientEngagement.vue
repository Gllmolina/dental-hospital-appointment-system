<script setup>
import {onMounted, onUnmounted, reactive, ref} from 'vue'
import {useRouter} from 'vue-router'
import {api, session} from '../api.js'

const props = defineProps({doctors: {type: Array, required: true}, appointments: {type: Array, required: true}})
const profile = reactive({name: '', gender: '', birthday: ''})
const phone = reactive({newPhone: '', code: '', currentPassword: ''})
const router = useRouter()
const avatarUrl = ref('')
const archive = reactive({allergyHistory: '', pastHistory: '', note: '', version: 0})
const favorites = ref([]), reviews = ref([]), feedback = ref([])
const reviewFor = ref(''), favoriteChoice = ref('')
const review = reactive({serviceScore: 5, communicationScore: 5, overallScore: 5, content: '', anonymous: false})
const report = reactive({type: 'SERVICE', content: ''})
const busy = ref(false), message = ref(''), failed = ref(false)

async function refresh() {
  try {
    const [a, f, r, b, p] = await Promise.all([
      api.get('/patient/health-archive'), api.get('/patient/favorites'),
      api.get('/patient/reviews'), api.get('/patient/feedback'), api.get('/patient/profile')
    ])
    Object.assign(profile, {name: p.profile.name || '', gender: p.profile.gender || '', birthday: p.profile.birthday || ''})
    if (p.profile.avatar_key) await loadAvatar()
    Object.assign(archive, {
      allergyHistory: a.archive.allergy_history || '', pastHistory: a.archive.past_history || '',
      note: a.archive.note || '', version: a.archive.version
    })
    favorites.value = f.items
    reviews.value = r.items
    feedback.value = b.items
  } catch (error) {
    failed.value = true
    message.value = error.message
  }
}
async function loadAvatar() {
  const blob = await api.get('/patient/avatar', {responseType: 'blob'})
  if (avatarUrl.value) URL.revokeObjectURL(avatarUrl.value)
  avatarUrl.value = URL.createObjectURL(blob)
}
async function uploadAvatar(event) {
  const file = event.target.files?.[0]
  if (!file) return
  if (file.size > 2_000_000) { failed.value = true; message.value = '头像须小于 2 MB'; return }
  const form = new FormData(); form.append('file', file)
  busy.value = true; failed.value = false
  try { await api.post('/patient/avatar', form); await loadAvatar(); message.value = '头像已更新' }
  catch (error) { failed.value = true; message.value = error.message }
  finally { busy.value = false; event.target.value = '' }
}
async function requestPhone() {
  busy.value = true; failed.value = false
  try { const result = await api.post('/patient/phone-change/request', {newPhone: phone.newPhone}); message.value = result.message }
  catch (error) { failed.value = true; message.value = error.message }
  finally { busy.value = false }
}
async function confirmPhone() {
  busy.value = true; failed.value = false
  try {
    await api.post('/patient/phone-change/confirm', {...phone})
    session.token = ''; session.role = ''; router.push('/login')
  } catch (error) { failed.value = true; message.value = error.message }
  finally { busy.value = false }
}

async function run(task) {
  busy.value = true
  failed.value = false
  try {
    await task()
    message.value = '已保存'
    await refresh()
  } catch (error) {
    failed.value = true
    message.value = error.message
    if (error.response?.status === 409) await refresh()
  } finally {
    busy.value = false
  }
}

function reviewed(id) {
  return reviews.value.some(item => item.appointment_id === id)
}

async function submitReview() {
  await run(() => api.post(`/appointments/${reviewFor.value}/review`, {...review}))
  if (!failed.value) {
    reviewFor.value = ''
    review.content = ''
  }
}

async function submitFeedback() {
  await run(() => api.post('/feedback', {...report}))
  if (!failed.value) report.content = ''
}

onMounted(refresh)
onUnmounted(() => { if (avatarUrl.value) URL.revokeObjectURL(avatarUrl.value) })
</script>

<template>
  <p class="status" :class="{error:failed}" role="status" aria-live="polite">{{ message }}</p>
  <section class="panel">
    <h2>头像</h2>
    <img v-if="avatarUrl" :src="avatarUrl" alt="我的头像" width="96" height="96" class="avatar">
    <label for="patient-avatar">上传 JPEG、PNG 或 WebP 图片，最大 2 MB</label>
    <input id="patient-avatar" type="file" accept="image/jpeg,image/png,image/webp" :disabled="busy" @change="uploadAvatar">
  </section>
  <section class="panel">
    <form action="/hospital/api/patient/profile" method="post" aria-labelledby="profile-heading"
          @submit.prevent="run(() => api.put('/patient/profile', {...profile}))">
      <h2 id="profile-heading">个人资料</h2>
      <label for="profile-name">姓名</label><input id="profile-name" v-model.trim="profile.name" name="name" autocomplete="name" maxlength="30" required>
      <label for="profile-gender">性别</label><select id="profile-gender" v-model="profile.gender" name="gender">
        <option value="">未填写</option><option value="MALE">男</option><option value="FEMALE">女</option><option value="OTHER">其他</option>
      </select>
      <label for="profile-birthday">出生日期</label><input id="profile-birthday" v-model="profile.birthday" name="birthday" type="date">
      <button class="primary" type="submit" :disabled="busy">保存个人资料</button>
    </form>
  </section>
  <section class="panel">
    <h2>更换手机号</h2>
    <form @submit.prevent="requestPhone">
      <label for="new-phone">新手机号</label><input id="new-phone" v-model.trim="phone.newPhone" type="tel" inputmode="tel" autocomplete="tel" pattern="1[0-9]{10}" required>
      <button type="submit" :disabled="busy">发送验证码</button>
    </form>
    <form @submit.prevent="confirmPhone">
      <label for="phone-code">8 位验证码</label><input id="phone-code" v-model.trim="phone.code" type="text" inputmode="numeric" autocomplete="one-time-code" pattern="[0-9]{8}" maxlength="8" required>
      <label for="phone-current-password">当前密码</label><input id="phone-current-password" v-model="phone.currentPassword" type="password" autocomplete="current-password" required>
      <button type="submit" :disabled="busy">确认更换手机号</button>
    </form>
    <p class="muted">更换成功后需要重新登录。</p>
  </section>
  <section class="panel">
    <form action="/hospital/api/patient/health-archive" method="post" aria-labelledby="archive-heading"
          @submit.prevent="run(() => api.put('/patient/health-archive', {...archive}))">
      <h2 id="archive-heading">个人健康档案</h2>
      <p class="muted">请填写有助于就诊的病史，每项最多 500 字。</p>
      <label for="allergy">过敏史</label><textarea id="allergy" v-model.trim="archive.allergyHistory" name="allergyHistory" maxlength="500"></textarea>
      <label for="past-history">既往史</label><textarea id="past-history" v-model.trim="archive.pastHistory" name="pastHistory" maxlength="500"></textarea>
      <label for="archive-note">其他说明</label><textarea id="archive-note" v-model.trim="archive.note" name="note" maxlength="500"></textarea>
      <button class="primary" type="submit" :disabled="busy">保存档案</button>
    </form>
  </section>

  <section class="panel">
    <h2>收藏医生</h2>
    <p v-if="!favorites.length">暂无收藏。</p>
    <div v-for="doctor in favorites" :key="doctor.doctor_id" class="record row">
      <span>{{ doctor.name }} · {{ doctor.department_name }} · {{ doctor.title }}</span>
      <button class="quiet" type="button" :disabled="busy"
              @click="run(() => api.delete(`/patient/favorites/${doctor.doctor_id}`))">取消收藏</button>
    </div>
    <label for="favorite-doctor">添加收藏</label>
    <select id="favorite-doctor" v-model="favoriteChoice" name="favoriteDoctor">
      <option value="">选择医生</option>
      <option v-for="doctor in props.doctors" :key="doctor.id" :value="String(doctor.id)">{{ doctor.name }} · {{ doctor.department_name }}</option>
    </select>
    <button type="button" class="quiet" :disabled="busy || !favoriteChoice"
            @click="run(() => api.put(`/patient/favorites/${favoriteChoice}`))">收藏医生</button>
  </section>

  <section class="panel">
    <h2>就诊评价</h2>
    <p v-if="!props.appointments.some(a => a.status === 'COMPLETED')">完成就诊后可评价。</p>
    <label for="review-appointment">选择已完成预约</label>
    <select id="review-appointment" v-model="reviewFor" name="reviewAppointment">
      <option value="">选择预约</option>
      <option v-for="a in props.appointments.filter(a => a.status === 'COMPLETED' && !reviewed(a.id))" :key="a.id" :value="String(a.id)">{{ a.appointment_no }}</option>
    </select>
    <form v-if="reviewFor" action="/hospital/api/appointments/review" method="post"
          aria-labelledby="review-heading" @submit.prevent="submitReview">
      <h3 id="review-heading">填写评价</h3>
      <p>如有未支付账单，请先完成支付。</p>
      <div class="grid">
        <div><label for="service-score">服务评分（1–5）</label><input id="service-score" v-model.number="review.serviceScore" name="serviceScore" type="number" min="1" max="5" required></div>
        <div><label for="communication-score">沟通评分（1–5）</label><input id="communication-score" v-model.number="review.communicationScore" name="communicationScore" type="number" min="1" max="5" required></div>
        <div><label for="overall-score">总体评分（1–5）</label><input id="overall-score" v-model.number="review.overallScore" name="overallScore" type="number" min="1" max="5" required></div>
      </div>
      <label for="review-content">评价内容（可选）</label><textarea id="review-content" v-model.trim="review.content" name="content" maxlength="500"></textarea>
      <div class="row"><input id="anonymous" v-model="review.anonymous" name="anonymous" type="checkbox" class="check"><label for="anonymous">匿名显示</label></div>
      <button class="primary" type="submit" :disabled="busy">提交评价</button>
    </form>
    <div v-for="item in reviews" :key="item.id" class="record">
      <strong>总体 {{ item.overall_score }} 分 · {{ item.anonymous ? '匿名' : '实名' }}</strong>
      <p>{{ item.content || '无文字评价' }}</p>
      <p v-if="item.reply">医生回复：{{ item.reply }}</p>
    </div>
  </section>

  <section class="panel">
    <form action="/hospital/api/feedback" method="post" aria-labelledby="feedback-heading" @submit.prevent="submitFeedback">
      <h2 id="feedback-heading">意见反馈</h2>
      <label for="feedback-type">反馈类型</label>
      <select id="feedback-type" v-model="report.type" name="type" required>
        <option value="SERVICE">服务体验</option><option value="SYSTEM">系统问题</option><option value="OTHER">其他</option>
      </select>
      <label for="feedback-content">内容（10–1000 字）</label>
      <textarea id="feedback-content" v-model.trim="report.content" name="content" minlength="10" maxlength="1000" required></textarea>
      <button class="primary" type="submit" :disabled="busy">提交反馈</button>
    </form>
    <div v-for="item in feedback" :key="item.id" class="record">
      <strong>{{ item.type }} · {{ item.status }}</strong><p>{{ item.content }}</p><p v-if="item.reply">回复：{{ item.reply }}</p>
    </div>
  </section>
</template>

<style scoped>
.check { width: auto; min-height: auto }
.row label { margin: 0 }
.avatar { object-fit: cover; border-radius: 50% }
</style>

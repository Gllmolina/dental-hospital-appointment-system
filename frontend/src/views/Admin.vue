<script setup>
import {onMounted, onUnmounted, reactive, ref, nextTick} from 'vue'
import * as echarts from 'echarts/core'
import {BarChart} from 'echarts/charts'
import {GridComponent, TooltipComponent, LegendComponent} from 'echarts/components'
import {CanvasRenderer} from 'echarts/renderers'
import {api} from '../api.js'
import RescheduleForm from './RescheduleForm.vue'
import AdminFeedback from './AdminFeedback.vue'
import LeaveRequests from './LeaveRequests.vue'
import KnowledgeAdmin from './KnowledgeAdmin.vue'
import ReviewAppeals from './ReviewAppeals.vue'
import AdminSettings from './AdminSettings.vue'

const tab = ref('overview'), busy = ref(false), message = ref(''), failed = ref(false)
echarts.use([BarChart, GridComponent, TooltipComponent, LegendComponent, CanvasRenderer])
const summary = ref({}), departments = ref([]), rooms = ref([]), doctors = ref([]), schedules = ref([]),
    users = ref([]), appointments = ref([]), bills = ref([]), items = ref([])
const department = reactive({code: '', name: '', description: '', sortOrder: 0})
const room = reactive({code: '', name: ''})
const doctor = reactive({
  username: '',
  phone: '',
  password: '',
  employeeNo: '',
  departmentId: '',
  name: '',
  title: '',
  specialty: '',
  defaultFee: '30.00'
})
const schedule = reactive({
  doctorId: '',
  roomId: '',
  visitDate: '',
  startTime: '09:00',
  endTime: '12:00',
  fee: '30.00',
  capacity: 2
})
const treatment = reactive({code: '', name: '', fee: '0.00'})
const checkinId = ref(''), capacitySlotId = ref(''), newCapacity = ref(1)
const statsFrom = ref(new Date().toLocaleDateString('en-CA', {timeZone: 'Asia/Shanghai'})),
    statsTo = ref(statsFrom.value)
const statsDepartment = ref(''), statsDoctor = ref(''), statsOverview = ref(null)
const chartEl = ref(null);
let chart
const suspensionId = ref(null), suspensionReason = ref('')
const disruptions = ref([]), disruptionStatus = ref('OPEN')
const resolution = ref(null), cancelReason = ref('')

async function loadDisruptions() {
  try {
    disruptions.value = (await api.get('/admin/disruptions', {params: {status: disruptionStatus.value}})).items
  } catch (error) {
    failed.value = true
    message.value = error.message
  }
}

function chooseResolution(item, action) {
  resolution.value = {id: item.id, action}
  cancelReason.value = ''
}

async function suspend(scheduleId) {
  await run(async () => {
    await api.post(`/admin/schedules/${scheduleId}/suspend`, {reason: suspensionReason.value})
    suspensionId.value = null
    tab.value = 'disruptions'
  })
  if (!failed.value) {
    message.value = '排班已停诊。后台生成任务后，点击刷新队列查看。'
    await loadDisruptions()
  }
}

async function cancelDisrupted(item) {
  await run(async () => {
    await api.put(`/admin/appointments/${item.appointment_id}/cancel`, {reason: cancelReason.value, disruptionId: item.id})
    resolution.value = null
  })
  if (!failed.value) await loadDisruptions()
}

async function rescheduled() {
  resolution.value = null
  failed.value = false
  message.value = '改约成功，已通知患者。'
  await Promise.all([refresh(), loadDisruptions()])
}

function openTab(name) {
  if (name === 'statistics') loadStats()
  if (name === 'disruptions') loadDisruptions()
}

async function refresh() {
  try {
    const [s, d, r, doctorsResult, sc, u, a, b, t] = await Promise.all([
      api.get('/admin/dashboard/summary'), api.get('/admin/departments'), api.get('/admin/rooms'), api.get('/admin/doctors'), api.get('/admin/schedules'), api.get('/admin/users'), api.get('/admin/appointments'), api.get('/admin/bills'), api.get('/admin/treatment-items')
    ])
    summary.value = s;
    departments.value = d.items;
    rooms.value = r.items;
    doctors.value = doctorsResult.items;
    schedules.value = sc.items;
    users.value = u.items;
    appointments.value = a.items;
    bills.value = b.items;
    items.value = t.items
  } catch (e) {
    message.value = e.message;
    failed.value = true
  }
}

async function run(task) {
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

function create(path, data) {
  return run(() => api.post(path, {...data}))
}

function publish(s) {
  return run(() => api.put(`/admin/schedules/${s.id}/publish`, {version: s.version}))
}

function statusUser(u) {
  return run(() => api.put(`/admin/users/${u.id}/status`, {
    status: u.status === 'ACTIVE' ? 'DISABLED' : 'ACTIVE',
    reason: '管理员操作'
  }))
}

function updateDepartment(d) {
  return run(() => api.put(`/admin/departments/${d.id}`, {
    name: d.name,
    description: d.description,
    sortOrder: d.sort_order,
    enabled: !d.enabled
  }))
}

function updateRoom(r) {
  return run(() => api.put(`/admin/rooms/${r.id}`, {name: r.name, enabled: !r.enabled}))
}

async function loadStats() {
  await nextTick();
  statsOverview.value = null;
  try {
    const params = {from: statsFrom.value, to: statsTo.value};
    if (statsDepartment.value) params.departmentId = statsDepartment.value;
    if (statsDoctor.value) params.doctorId = statsDoctor.value;
    const overview = await api.get('/admin/statistics/overview', {params});
    statsOverview.value = overview;
    const trends = overview.trends;
    if (!chart && chartEl.value) chart = echarts.init(chartEl.value);
    chart?.setOption({
      tooltip: {trigger: 'axis'},
      legend: {data: ['预约', '完成', '取消']},
      xAxis: {type: 'category', data: trends.map(x => `${x.visit_date} · 科室${x.department_id}`)},
      yAxis: {type: 'value', minInterval: 1},
      series: [{name: '预约', type: 'bar', data: trends.map(x => x.total)}, {
        name: '完成',
        type: 'bar',
        data: trends.map(x => x.completed)
      }, {name: '取消', type: 'bar', data: trends.map(x => x.cancelled)}]
    });
    message.value = trends.length ? `统计截至 ${overview.asOf}` : '所选日期暂无数据';
    failed.value = false
  } catch (e) {
    message.value = e.message;
    failed.value = true
  }
}

function resize() {
  chart?.resize()
}

onMounted(() => {
  refresh();
  window.addEventListener('resize', resize)
});
onUnmounted(() => {
  window.removeEventListener('resize', resize);
  chart?.dispose()
})
</script>
<template>
  <h1>管理工作台</h1>
  <p class="status" :class="{error:failed}" role="status" aria-live="polite">{{ message }}</p>
  <el-tabs v-model="tab" @tab-change="openTab">
    <el-tab-pane label="概览" name="overview">
      <div class="grid">
        <section class="panel"><h2>今日预约</h2>
          <div class="metric">{{ summary.todayAppointments ?? '—' }}</div>
        </section>
        <section class="panel"><h2>已完成</h2>
          <div class="metric">{{ summary.completed ?? '—' }}</div>
        </section>
        <section class="panel"><h2>待支付</h2>
          <div class="metric">{{ summary.pendingPayment ?? '—' }}</div>
        </section>
      </div>
      <section class="panel"><h2>预约列表</h2>
        <div v-for="a in appointments" :key="a.id" class="record"><strong>预约 {{ a.appointment_no }} · {{
            a.status
          }}</strong><small>编号 {{ a.id }} · 患者 {{ a.patient_id }} · {{ a.visit_date }}</small></div>
      </section>
      <section class="panel"><h2>账单摘要</h2>
        <div v-for="b in bills" :key="b.id" class="record">{{ b.bill_no }} · {{ b.type }} · {{ b.amount }} 元 ·
          {{ b.status }}
        </div>
      </section>
    </el-tab-pane>
    <el-tab-pane label="资源与医生" name="resources">
      <div class="grid">
        <section class="panel">
          <form aria-labelledby="department-heading" action="/hospital/api/admin/departments" method="post"
                @submit.prevent="create('/admin/departments',department)"><h2 id="department-heading">新增科室</h2>
            <label for="dep-code">代码</label><input id="dep-code" v-model.trim="department.code" name="code"
                                                     required><label for="dep-name">名称</label><input id="dep-name"
                                                                                                       v-model.trim="department.name"
                                                                                                       name="name"
                                                                                                       required><label
                for="dep-desc">简介</label><textarea id="dep-desc" v-model.trim="department.description"
                                                     name="description"></textarea>
            <button class="primary" type="submit" :disabled="busy">保存科室</button>
          </form>
          <div v-for="d in departments" :key="d.id" class="record"><strong>{{ d.name }} · {{
              d.enabled ? '启用' : '停用'
            }}</strong><small>ID {{ d.id }} · {{ d.code }}</small>
            <button type="button" class="quiet" :disabled="busy" @click="updateDepartment(d)">
              {{ d.enabled ? '停用' : '启用' }}
            </button>
          </div>
        </section>
        <section class="panel">
          <form aria-labelledby="room-heading" action="/hospital/api/admin/rooms" method="post"
                @submit.prevent="create('/admin/rooms',room)"><h2 id="room-heading">新增诊室</h2><label for="room-code">代码</label><input
              id="room-code" v-model.trim="room.code" name="code" required><label for="room-name">名称</label><input
              id="room-name" v-model.trim="room.name" name="name" required>
            <button class="primary" type="submit" :disabled="busy">保存诊室</button>
          </form>
          <div v-for="r in rooms" :key="r.id" class="record"><strong>{{ r.name }} · {{
              r.enabled ? '启用' : '停用'
            }}</strong><small>ID {{ r.id }} · {{ r.code }}</small>
            <button type="button" class="quiet" :disabled="busy" @click="updateRoom(r)">{{
                r.enabled ? '停用' : '启用'
              }}
            </button>
          </div>
        </section>
      </div>
      <section class="panel">
        <form aria-labelledby="doctor-heading" action="/hospital/api/admin/doctors" method="post"
              @submit.prevent="create('/admin/doctors',doctor)"><h2 id="doctor-heading">新增医生</h2>
          <div class="grid">
            <div><label for="doctor-user">登录用户名</label><input id="doctor-user" v-model.trim="doctor.username"
                                                                   name="username" required><label for="doctor-phone">手机号</label><input
                id="doctor-phone" v-model.trim="doctor.phone" name="phone" type="tel" required><label for="doctor-pwd">初始密码</label><input
                id="doctor-pwd" v-model="doctor.password" name="password" type="password" minlength="8"
                autocomplete="new-password" required></div>
            <div><label for="doctor-emp">工号</label><input id="doctor-emp" v-model.trim="doctor.employeeNo"
                                                            name="employeeNo" required><label
                for="doctor-name">姓名</label><input id="doctor-name" v-model.trim="doctor.name" name="name"
                                                     required><label for="doctor-dept">科室</label><select
                id="doctor-dept" v-model="doctor.departmentId" name="departmentId" required>
              <option value="">请选择</option>
              <option v-for="d in departments" :key="d.id" :value="d.id">{{ d.name }}</option>
            </select></div>
            <div><label for="doctor-title">职称</label><input id="doctor-title" v-model.trim="doctor.title"
                                                              name="title"><label
                for="doctor-specialty">擅长</label><input id="doctor-specialty" v-model.trim="doctor.specialty"
                                                          name="specialty"><label
                for="doctor-fee">默认挂号费</label><input id="doctor-fee" v-model="doctor.defaultFee" name="defaultFee"
                                                          type="number" min="0.01" step="0.01" required></div>
          </div>
          <button class="primary" type="submit" :disabled="busy">保存医生</button>
        </form>
        <div v-for="d in doctors" :key="d.id" class="record">{{ d.name }} · {{ d.employee_no }} · {{ d.title }} · 科室
          {{ d.department_id }} · {{ d.status }}
        </div>
      </section>
    </el-tab-pane>
    <el-tab-pane label="排班与签到" name="schedules">
      <section class="panel">
        <form aria-labelledby="schedule-heading" action="/hospital/api/admin/schedules" method="post"
              @submit.prevent="create('/admin/schedules',schedule)"><h2 id="schedule-heading">新建排班草稿</h2>
          <div class="grid">
            <div><label for="schedule-doctor">医生</label><select id="schedule-doctor" v-model="schedule.doctorId"
                                                                  name="doctorId" required>
              <option value="">请选择</option>
              <option v-for="d in doctors" :key="d.id" :value="d.id">{{ d.name }}</option>
            </select><label for="schedule-room">诊室</label><select id="schedule-room" v-model="schedule.roomId"
                                                                    name="roomId" required>
              <option value="">请选择</option>
              <option v-for="r in rooms" :key="r.id" :value="r.id">{{ r.name }}</option>
            </select></div>
            <div><label for="visit-date">日期</label><input id="visit-date" v-model="schedule.visitDate"
                                                            name="visitDate" type="date" required><label
                for="start-time">开始</label><input id="start-time" v-model="schedule.startTime" name="startTime"
                                                    type="time" required><label for="end-time">结束</label><input
                id="end-time" v-model="schedule.endTime" name="endTime" type="time" required></div>
            <div><label for="schedule-fee">挂号费</label><input id="schedule-fee" v-model="schedule.fee" name="fee"
                                                                type="number" min="0.01" step="0.01" required><label
                for="capacity">每时段名额</label><input id="capacity" v-model.number="schedule.capacity" name="capacity"
                                                        type="number" min="0" max="20" required></div>
          </div>
          <button class="primary" type="submit" :disabled="busy">保存草稿</button>
        </form>
      </section>
      <section class="panel"><h2>排班列表</h2>
        <div v-for="s in schedules" :key="s.id" class="record"><strong>{{ s.visit_date }} · 医生 {{ s.doctor_id }} ·
          {{ s.status }}</strong><small>ID {{ s.id }} · 版本 {{ s.version }} · 每时段 {{ s.capacity }} 人</small>
          <button v-if="s.status==='DRAFT'" class="primary" type="button" :disabled="busy" @click="publish(s)">
            发布号源
          </button>
          <button v-if="s.status === 'PUBLISHED'" class="quiet" type="button" :disabled="busy"
                  :aria-expanded="suspensionId === s.id" :aria-controls="`suspend-form-${s.id}`"
                  @click="suspensionId = suspensionId === s.id ? null : s.id; suspensionReason = ''">
            停诊
          </button>
          <form v-if="suspensionId === s.id" :id="`suspend-form-${s.id}`"
                :aria-labelledby="`suspend-heading-${s.id}`"
                :action="`/hospital/api/admin/schedules/${s.id}/suspend`" method="post" @submit.prevent="suspend(s.id)">
            <h3 :id="`suspend-heading-${s.id}`">确认停诊</h3>
            <p>确认后会关闭本排班全部号源。已支付预约进入停诊调度队列。</p>
            <label :for="`suspend-reason-${s.id}`">停诊原因（必填）</label>
            <textarea :id="`suspend-reason-${s.id}`" name="reason" v-model.trim="suspensionReason" required maxlength="200"/>
            <button class="primary" type="submit" :disabled="busy">确认停诊</button>
          </form>
        </div>
      </section>
      <div class="grid">
        <section class="panel">
          <form aria-labelledby="checkin-heading" action="/hospital/api/admin/appointments" method="post"
                @submit.prevent="run(()=>api.put(`/admin/appointments/${checkinId}/check-in`))"><h2
              id="checkin-heading">现场签到</h2><label for="checkin-id">预约 ID</label><input id="checkin-id"
                                                                                              v-model.trim="checkinId"
                                                                                              name="id" required>
            <button class="primary" type="submit" :disabled="busy">核验签到</button>
          </form>
        </section>
        <section class="panel">
          <form aria-labelledby="capacity-heading" action="/hospital/api/admin/slots" method="post"
                @submit.prevent="run(()=>api.put(`/admin/slots/${capacitySlotId}/capacity`,{capacity:newCapacity}))"><h2
              id="capacity-heading">调整号量</h2><label for="slot-id">号源 ID</label><input id="slot-id"
                                                                                            v-model.trim="capacitySlotId"
                                                                                            name="slotId"
                                                                                            required><label
              for="new-capacity">新容量</label><input id="new-capacity" v-model.number="newCapacity" name="capacity"
                                                      type="number" min="0" max="20" required>
            <button class="primary" type="submit" :disabled="busy">更新容量</button>
          </form>
        </section>
      </div>
    </el-tab-pane>
    <el-tab-pane label="停诊调度" name="disruptions">
      <section class="panel">
        <h2>停诊调度队列</h2>
        <label for="disruption-status">处理状态</label>
        <select id="disruption-status" v-model="disruptionStatus"
                @change="resolution = null; loadDisruptions()">
          <option value="OPEN">待处理</option>
          <option value="RESCHEDULED">已改约</option>
          <option value="CANCELLED">已取消</option>
        </select>
        <button class="quiet" type="button" @click="loadDisruptions">刷新队列</button>
        <p v-if="!disruptions.length">当前状态暂无任务。</p>
        <article v-for="item in disruptions" :key="item.id" class="record">
          <strong>预约 {{ item.appointment_no }} · 挂号费 {{ item.registration_fee }} 元</strong>
          <small>停诊排班 {{ item.suspended_schedule_id }}</small>
          <div v-if="item.status === 'OPEN'" class="row">
            <button class="quiet" type="button" :disabled="busy"
                    :aria-expanded="resolution?.id === item.id && resolution?.action === 'reschedule'"
                    @click="chooseResolution(item, 'reschedule')">同价改约</button>
            <button class="quiet" type="button" :disabled="busy"
                    :aria-expanded="resolution?.id === item.id && resolution?.action === 'cancel'"
                    @click="chooseResolution(item, 'cancel')">取消并退款</button>
          </div>
          <template v-if="resolution?.id === item.id">
            <RescheduleForm v-if="resolution.action === 'reschedule'" :appointment-id="item.appointment_id"
                            :disruption-id="item.id" admin @saved="rescheduled" @close="resolution = null"/>
            <form v-else :aria-labelledby="`refund-heading-${item.id}`"
                  :action="`/hospital/api/admin/appointments/${item.appointment_id}/cancel`" method="post"
                  @submit.prevent="cancelDisrupted(item)">
              <h3 :id="`refund-heading-${item.id}`">确认取消并退款</h3>
              <label :for="`refund-reason-${item.id}`">取消原因（必填）</label>
              <textarea :id="`refund-reason-${item.id}`" name="reason" v-model.trim="cancelReason" required maxlength="200"/>
              <button class="primary" type="submit" :disabled="busy">确认取消并退款</button>
              <button class="quiet" type="button" :disabled="busy" @click="resolution = null">返回</button>
            </form>
          </template>
        </article>
      </section>
    </el-tab-pane>
    <el-tab-pane label="账号与价目" name="accounts">
      <div class="grid">
        <section class="panel"><h2>账号</h2>
          <div v-for="u in users" :key="u.id" class="record"><strong>{{ u.username }} · {{ u.role }} · {{
              u.status
            }}</strong><small>ID {{ u.id }} · {{ u.phone }}</small>
            <button type="button" class="quiet" :disabled="busy || u.username==='admin'" @click="statusUser(u)">
              {{ u.status === 'ACTIVE' ? '停用' : '启用' }}
            </button>
          </div>
        </section>
        <section class="panel">
          <form aria-labelledby="treatment-heading" action="/hospital/api/admin/treatment-items" method="post"
                @submit.prevent="create('/admin/treatment-items',treatment)"><h2 id="treatment-heading">
            新增诊疗价目</h2><label for="item-code">代码</label><input id="item-code" v-model.trim="treatment.code"
                                                                       name="code" required><label
              for="item-name">名称</label><input id="item-name" v-model.trim="treatment.name" name="name"
                                                 required><label for="item-fee">价格</label><input id="item-fee"
                                                                                                   v-model="treatment.fee"
                                                                                                   name="fee"
                                                                                                   type="number" min="0"
                                                                                                   step="0.01" required>
            <button class="primary" type="submit" :disabled="busy">保存价目</button>
          </form>
          <div v-for="i in items" :key="i.id" class="record">{{ i.name }} · {{ i.fee }} 元 ·
            {{ i.enabled ? '启用' : '停用' }}
          </div>
        </section>
      </div>
    </el-tab-pane>
    <el-tab-pane label="基础统计" name="statistics">
      <section class="panel">
        <form aria-labelledby="stats-heading" action="/hospital/api/admin/statistics/appointments" method="get"
              @submit.prevent="loadStats"><h2 id="stats-heading">预约统计</h2>
          <div class="grid">
            <div><label for="stats-from">开始日期</label><input id="stats-from" v-model="statsFrom" name="from"
                                                                type="date" required></div>
            <div><label for="stats-to">结束日期</label><input id="stats-to" v-model="statsTo" name="to" type="date"
                                                              required></div>
            <div><label for="stats-department">科室</label><select id="stats-department" v-model="statsDepartment" name="departmentId">
              <option value="">全部科室</option><option v-for="d in departments" :key="d.id" :value="d.id">{{ d.name }}</option>
            </select></div>
            <div><label for="stats-doctor">医生</label><select id="stats-doctor" v-model="statsDoctor" name="doctorId">
              <option value="">全部医生</option><option v-for="d in doctors" :key="d.id" :value="d.id">{{ d.name }}</option>
            </select></div>
          </div>
          <button class="primary" type="submit">查询</button>
        </form>
        <div v-if="statsOverview" class="grid">
          <div class="record"><strong>预约 {{ statsOverview.counts.total }} · 完成 {{ statsOverview.counts.completed }}</strong>
            <p>取消 {{ statsOverview.counts.cancelled }} · 超时 {{ statsOverview.counts.expired }} · 复诊人数 {{ statsOverview.counts.repeatPatients }}</p></div>
          <div class="record"><strong>已约号源利用率 {{ statsOverview.utilization.value === null ? '—' : `${(statsOverview.utilization.value * 100).toFixed(1)}%` }}</strong>
            <small>{{ statsOverview.utilization.numerator }} / {{ statsOverview.utilization.denominator }}；仅计算未停诊排班的已约号源与容量。</small></div>
          <div class="record"><strong>取消率 {{ statsOverview.cancellationRate.value === null ? '—' : `${(statsOverview.cancellationRate.value * 100).toFixed(1)}%` }}</strong>
            <small>{{ statsOverview.cancellationRate.numerator }} / {{ statsOverview.cancellationRate.denominator }}；按当前就诊日期统计。</small></div>
          <div class="record"><strong>爽约率 {{ statsOverview.noShowRate.value === null ? '—' : `${(statsOverview.noShowRate.value * 100).toFixed(1)}%` }}</strong>
            <small>{{ statsOverview.noShowRate.numerator }} / {{ statsOverview.noShowRate.denominator }}；排除停诊排班。</small></div>
        </div>
        <div ref="chartEl" class="chart" role="img" aria-label="预约、完成、取消数量柱状图"></div>
        <div v-if="statsOverview" class="grid">
          <section><h3>时段热度</h3><p v-if="!statsOverview.hourlyHeat.length">暂无数据。</p>
            <p v-for="h in statsOverview.hourlyHeat" :key="h.visit_hour">{{ h.visit_hour }} 时：{{ h.total }} 单</p></section>
          <section><h3>医生工作量</h3><p v-if="!statsOverview.doctorWorkload.length">暂无数据。</p>
            <p v-for="d in statsOverview.doctorWorkload" :key="d.doctor_id">{{ d.doctor_name }}：完成 {{ d.completed }}，排班 {{ d.published_minutes }} 分钟，评分 {{ d.rating_average ?? '—' }}</p></section>
        </div>
      </section>
    </el-tab-pane>
    <el-tab-pane label="意见反馈" name="feedback">
      <AdminFeedback/>
    </el-tab-pane>
    <el-tab-pane label="请假审核" name="leave">
      <LeaveRequests admin/>
    </el-tab-pane>
    <el-tab-pane label="知识库" name="knowledge">
      <KnowledgeAdmin/>
    </el-tab-pane>
    <el-tab-pane label="评价申诉" name="review-appeals">
      <ReviewAppeals/>
    </el-tab-pane>
    <el-tab-pane label="业务参数" name="settings">
      <AdminSettings/>
    </el-tab-pane>
  </el-tabs>
</template>

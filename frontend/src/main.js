import {createApp} from 'vue'
import {createRouter, createWebHashHistory} from 'vue-router'
import {ElTabs, ElTabPane} from 'element-plus'
import 'element-plus/dist/index.css'
import App from './App.vue'
import Login from './views/Login.vue'
import ChangePassword from './views/ChangePassword.vue'
import {session} from './api.js'
import './style.css'

export const router = createRouter({
    history: createWebHashHistory(import.meta.env.BASE_URL),
    routes: [
        {path: '/', redirect: '/login'},
        {path: '/login', component: Login},
        {path: '/change-password', component: ChangePassword, meta: {authenticated: true}},
        {path: '/patient', component: () => import('./views/Patient.vue'), meta: {role: 'PATIENT'}},
        {path: '/doctor', component: () => import('./views/Doctor.vue'), meta: {role: 'DOCTOR'}},
        {path: '/admin', component: () => import('./views/Admin.vue'), meta: {role: 'ADMIN'}}
    ]
})
router.beforeEach(to => {
    if (to.meta.authenticated && !session.token) return '/login'
    if (session.token && session.mustChangePassword && to.path !== '/change-password') return '/change-password'
    if (to.meta.role && (!session.token || session.role !== to.meta.role)) return '/login'
    if (to.path === '/login' && session.token) return `/${session.role.toLowerCase()}`
})
createApp(App).use(router).component('ElTabs', ElTabs).component('ElTabPane', ElTabPane).mount('#app')

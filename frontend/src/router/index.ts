import { createRouter, createWebHistory } from 'vue-router'
import { ElMessage } from 'element-plus'
import DefaultLayout from '@/layouts/DefaultLayout.vue'
import { getProject } from '@/api/project'
import { TOKEN_KEY } from '@/utils/constants'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    // 认证页：独立全屏，不进 DefaultLayout（没有侧边栏/顶栏）
    { path: '/login', name: 'login', component: () => import('@/views/LoginView.vue'), meta: { title: '登录' } },
    { path: '/register', name: 'register', component: () => import('@/views/RegisterView.vue'), meta: { title: '注册' } },
    {
      path: '/',
      component: DefaultLayout,
      children: [
        { path: '', redirect: '/workbench' },
        { path: 'workbench', name: 'workbench', component: () => import('@/views/WorkbenchView.vue'), meta: { title: '工作台' } },
        { path: 'projects', name: 'projects', component: () => import('@/views/projects/ProjectListView.vue'), meta: { title: '项目管理' } },
        {
          path: 'projects/:id/monitor',
          name: 'monitor',
          component: () => import('@/views/projects/MonitorView.vue'),
          meta: { title: '监听配置' },
          /*
           * UPLOAD 型项目没有可监听的日志文件（它的 logPath 存的是文件夹名），
           * 列表页那个「监听」按钮本来就不给它显示——但手敲 URL、浏览器前进/后退
           * 仍然进得来，进来后「启动监听」必然失败。这里统一拦在路由层。
           */
          beforeEnter: async (to) => {
            const project = await getProject(String(to.params.id)).catch(() => null)
            if (project?.source === 'UPLOAD') {
              ElMessage.warning('上传型项目没有监听路径，已返回项目管理')
              return { path: '/projects' }
            }
            return true
          },
        },
        { path: 'projects/:id/logs', name: 'logs', component: () => import('@/views/projects/LogSearchView.vue'), meta: { title: '日志检索' } },
        { path: 'diagnosis', name: 'diagnosis-list', component: () => import('@/views/diagnosis/DiagnosisPanelView.vue'), meta: { title: 'Agent 诊断', full: true } },
        { path: 'diagnosis/:sessionId', name: 'diagnosis', component: () => import('@/views/diagnosis/DiagnosisPanelView.vue'), meta: { title: 'Agent 诊断', full: true } },
        { path: 'reports', name: 'reports', component: () => import('@/views/reports/ReportListView.vue'), meta: { title: '报告中心' } },
        { path: 'reports/:id', name: 'report-detail', component: () => import('@/views/reports/ReportDetailView.vue'), meta: { title: '报告详情' } },
        { path: 'settings', name: 'settings', component: () => import('@/views/SettingsView.vue'), meta: { title: '系统设置' } },
      ],
    },
    { path: '/:pathMatch(.*)*', redirect: '/workbench' },
  ],

  /*
   * ★ 没有 scrollBehavior 时，vue-router 不会重置滚动位置 ——
   *   浏览器会沿用上一页的滚动量。表现是：在滚到一半的日志列表里点进诊断页，
   *   新版页面直接落在半截处（顶部标题、结论卡片的开头都被顶出可视区），
   *   看起来像"页面没加载完"。前进/后退时则应该还原原来的位置。
   */
  scrollBehavior(_to, _from, savedPosition) {
    return savedPosition ?? { top: 0 }
  },
})

router.afterEach((to) => {
  const title = (to.meta.title as string | undefined) ?? ''
  document.title = title ? `${title} · TraceMind` : 'TraceMind'
})

/*
 * 多租户登录守卫：没 token 一律回登录页（登录/注册本身除外），
 * 已登录再访问登录/注册则跳回工作台。
 */
router.beforeEach((to) => {
  const token = localStorage.getItem(TOKEN_KEY)
  const isPublic = to.path === '/login' || to.path === '/register'
  if (!token && !isPublic) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }
  if (token && isPublic) {
    return { path: '/workbench' }
  }
  return true
})

export default router

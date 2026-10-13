import { api } from '../../services/api'
import { money } from '../../utils/format'

type FollowTab = 'today' | 'overdue' | 'upcoming'
type TaskGroup = { key: string; label: string; tasks: any[] }

function tomorrowInBeijing() {
  const date = new Date(Date.now() + 8 * 3600000)
  date.setUTCDate(date.getUTCDate() + 1)
  return date.toISOString().slice(0, 10)
}

Page({
  data: {
    active: 'today' as FollowTab,
    today: [] as any[], overdue: [] as any[], upcoming: [] as any[],
    groups: [] as TaskGroup[], total: 0, loading: true, loadError: ''
  },
  _loadVersion: 0,
  onShow() {
    const tab = wx.getStorageSync('followTab')
    wx.removeStorageSync('followTab')
    if (['today', 'overdue', 'upcoming'].includes(tab)) this.setData({ active: tab })
    this.load()
  },
  async load() {
    const version = ++this._loadVersion
    this.setData({ loading: true, loadError: '' })
    try {
      const [rawToday, rawOverdue, rawUpcoming] = await Promise.all([
        api.todayFollows(), api.overdueFollows(), api.upcomingFollows()
      ])
      if (version !== this._loadVersion) return
      const map = (items: any) => (Array.isArray(items) ? items : []).map(x => ({
        ...x, budgetText: money(x?.budget), planDate: this.formatPlanDate(x?.planTime),
        overdueDays: Math.max(0, Number(x?.overdueDays) || 0)
      }))
      const today = map(rawToday), overdue = map(rawOverdue), upcoming = map(rawUpcoming)
      this.setData({ today, overdue, upcoming, total: today.length + overdue.length + upcoming.length })
      this.updateGroups()
    } catch (_) {
      if (version === this._loadVersion) this.setData({ loadError: '任务加载失败，请重试' })
    } finally {
      if (version === this._loadVersion) this.setData({ loading: false })
    }
  },
  formatPlanDate(value?: string) {
    if (!value) return '未设置'
    const normalized = value.replace(' ', 'T')
    const date = normalized.slice(0, 10).split('-')
    if (date.length !== 3) return '未设置'
    return `${date[0]}年${Number(date[1])}月${Number(date[2])}日 ${normalized.slice(11, 16)}`
  },
  updateGroups() {
    const active = this.data.active
    if (active !== 'upcoming') {
      const tasks = active === 'today' ? this.data.today : this.data.overdue
      this.setData({ groups: tasks.length ? [{ key: active, label: active === 'today' ? '今天的待办' : '需要尽快处理', tasks }] : [] })
      return
    }
    const tomorrow = tomorrowInBeijing()
    const groups: TaskGroup[] = []
    const byDate = new Map<string, TaskGroup>()
    const tasks = [...this.data.upcoming].sort((a, b) => String(a.planTime).localeCompare(String(b.planTime)))
    tasks.forEach(task => {
      const key = String(task.planTime || '').slice(0, 10)
      let group = byDate.get(key)
      if (!group) {
        group = { key, label: key === tomorrow ? '明天' : this.formatPlanDate(task.planTime).split(' ')[0], tasks: [] }
        byDate.set(key, group)
        groups.push(group)
      }
      group.tasks.push(task)
    })
    this.setData({ groups })
  },
  tab(e: WechatMiniprogram.TouchEvent) {
    const active = e.currentTarget.dataset.tab as FollowTab
    if (!['today', 'overdue', 'upcoming'].includes(active)) return
    this.setData({ active })
    this.updateGroups()
  },
  goUnscheduled() {
    wx.setStorageSync('customerFilter', 'UNSCHEDULED')
    wx.switchTab({ url: '/pages/customer/index' })
  },
  record(e: WechatMiniprogram.TouchEvent) { wx.navigateTo({ url: `/pages/follow-edit/index?customerId=${e.currentTarget.dataset.id}&taskId=${e.currentTarget.dataset.task}` }) },
  ai(e: WechatMiniprogram.TouchEvent) { wx.navigateTo({ url: `/pages/ai/index?id=${e.currentTarget.dataset.id}` }) },
  detail(e: WechatMiniprogram.TouchEvent) { wx.navigateTo({ url: `/pages/customer-detail/index?id=${e.currentTarget.dataset.id}` }) }
})

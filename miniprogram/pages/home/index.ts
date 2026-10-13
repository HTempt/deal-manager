import { api } from '../../services/api'
import { money } from '../../utils/format'

function adviceItem(x: any) {
  const overdueDays = Number(x.overdueDays || 0)
  return {
    ...x,
    avatar: (x.customerName || '?').substring(0, 1),
    budgetText: x.budget == null ? '待确认' : `¥${money(x.budget)}`,
    contactText: x.hasContactHistory === false ? '尚未联系' : `${x.daysWithoutContact} 天未联系`,
    priorityClass: `priority-${String(x.priority || 'B').toLowerCase()}`,
    overdueText: overdueDays > 0 ? `逾期${overdueDays}天` : (x.planTime ? '今天有安排' : '')
  }
}

function generatedAt(value?: string) {
  if (!value) return ''
  const text = String(value).replace('T', ' ')
  return `生成于 ${text.length >= 16 ? text.substring(5, 16) : text}`
}

Page({
  data: {
    loading: true, loadError: '',
    overview: { customerCount: 0, todayFollowCount: 0, todayScheduledCount: 0, highIntentCount: 0, overdueCount: 0, unscheduledCount: 0, dormantCount:0, todayTasks: [] },
    advice: { loading: true, error: '', overview: '', items: [], candidateCount: 0, updatedText: '', stale: false, aiGenerated: false }
  },
  onShow() { this.load(); this.loadAdvice(false) },
  async load() {
    this.setData({ loading: true, loadError: '' })
    try {
      const o = await api.home()
      o.unscheduledCount = Number(o.unscheduledCount || 0)
      o.todayScheduledCount = Number(o.todayScheduledCount || 0)
      o.dormantCount = Number(o.dormantCount || 0)
      o.todayTasks = (o.todayTasks || []).map((x: any) => ({
        ...x, avatar: (x.customerName || '?').substring(0, 1),
        budgetText: x.budget == null ? '待确认' : `¥${money(x.budget)}`,
        contactText: x.hasContactHistory === false ? '尚未联系' : `${x.daysWithoutContact} 天未联系`
      }))
      this.setData({ overview: o })
    } catch (_) { this.setData({ loadError: '推荐加载失败，请重试' }) }
    finally { this.setData({ loading: false }) }
  },
  // 每日建议与规则列表分开加载：模型失败或超额时不隐藏其他首页内容。
  async loadAdvice(refresh: boolean) {
    if (this.data.advice.loading && refresh) return
    this.setData({ advice: { ...this.data.advice, loading: true, error: '' } })
    try {
      const a: any = await api.dailyAdvice(refresh === true)
      const advice: any = {
        loading: false, error: '', overview: a.overview || '', candidateCount: Number(a.candidateCount || 0),
        stale: a.stale === true, aiGenerated: a.aiGenerated === true, updatedText: generatedAt(a.updatedAt),
        items: (a.items || []).map(adviceItem)
      }
      this.setData({ advice })
    } catch (e: any) {
      this.setData({ advice: { ...this.data.advice, loading: false, error: e?.message || '今日建议加载失败，请重试' } })
    }
  },
  refreshAdvice() { this.loadAdvice(true) },
  retryAdvice() { this.loadAdvice(false) },
  goPriority() { wx.pageScrollTo({ selector: '#priority-customers', duration: 250 }) },
  goCustomers(e?: WechatMiniprogram.TouchEvent) {
    wx.setStorageSync('customerFilter',e?.currentTarget.dataset.filter||'ALL')
    wx.switchTab({ url: '/pages/customer/index' })
  },
  goFollow(e: WechatMiniprogram.TouchEvent) {
    const tab = e.currentTarget.dataset.tab || 'today'
    wx.setStorageSync('followTab', tab)
    wx.switchTab({ url: '/pages/follow/index' })
  },
  goDetail(e: WechatMiniprogram.TouchEvent) { wx.navigateTo({ url: `/pages/customer-detail/index?id=${e.currentTarget.dataset.id}` }) },
  goAi(e: WechatMiniprogram.TouchEvent) { wx.navigateTo({ url: `/pages/ai/index?id=${e.currentTarget.dataset.id}` }) },
  goRecord(e: WechatMiniprogram.TouchEvent) {
    const { id, task } = e.currentTarget.dataset
    const taskQuery = task == null || task === '' ? '' : `&taskId=${task}`
    wx.navigateTo({ url: `/pages/follow-edit/index?customerId=${id}${taskQuery}` })
  }
})
import { api } from '../../services/api'
import { currentMonth } from '../../utils/date'
import { money } from '../../utils/format'

const emptyOverview = {
  newCustomers: 0, customerGrowthPercent: 0, dealAmount: 0, dealCustomers: 0,
  dealAmountText: '0', dealAmountDisplay: '¥0', dealCustomersDisplay: '本月暂无成交客户',
  growthAvailable: false, growthDisplay: '暂无上月数据'
}

const funnelStages = ['新增客户', '需求沟通', '方案中', '报价中', '谈判中', '成交']
const emptyFunnel = () => ({
  stages: funnelStages.map(stage => ({ stage, customers: 0, width: 0 })),
  quotedCustomers: 0, lostCustomers: 0, inquiryToQuoteDisplay: '—', dealConversionDisplay: '—', quoteToDealDisplay: '—', cohortBasis: '', progressBasis: ''
})
const emptyEfficiency = () => ({
  expectedTasks: 0, completedTasks: 0, pendingTasks: 0, followedCustomers: 0, followRecords: 0, dealCycleCustomers: 0,
  completionDisplay: '—', completionWidth: 0, averageFollowDisplay: '—', averageCycleDisplay: '—', asOfText: ''
})

Page({
  data: {
    month: currentMonth(), overview: { ...emptyOverview }, sources: [] as any[],
    funnel: emptyFunnel(), funnelError: '', funnelLoading: true, funnelExpanded: false, dataError: '', overviewLoading: true,
    sourceLoading: true, sourceError: '', efficiency: emptyEfficiency(), efficiencyLoading: true, efficiencyError: '', showBasis: false,
    summary: '', summaryAiGenerated: false, summaryUpdatedAt: '', summaryStale: false, summaryLoading: true, summaryError: ''
  },
  _loadVersion: 0,
  _refreshRequested: false,
  onShow() { this.load() },
  load() { return this.loadData(false) },
  reanalyze() {
    if (this.data.summaryLoading) return
    return this.loadData(true)
  },
  retrySummary() {
    if (this.data.summaryLoading) return
    return this.loadData(this._refreshRequested)
  },
  async loadData(refresh: boolean) {
    const version = ++this._loadVersion
    const month = this.data.month
    this._refreshRequested = refresh
    this.setData({
      ...(refresh ? {} : { summary: '', summaryAiGenerated: false, summaryUpdatedAt: '', summaryStale: false }),
      summaryLoading: true, summaryError: ''
    })
    await Promise.all([
      this.loadSummary(month, refresh, version), this.loadFunnel(month, version),
      this.loadOverview(month, version), this.loadSources(month, version), this.loadEfficiency(month, version)
    ])
  },
  async loadOverview(month: string, version: number) {
    this.setData({ overviewLoading: true, dataError: '' })
    try {
      const raw = await api.dataOverview(month)
      if (version !== this._loadVersion) return
      const overviewRaw = raw || {}
      const growth = this.safeNumber(overviewRaw.customerGrowthPercent)
      const newCustomers = this.safeNumber(overviewRaw.newCustomers)
      const dealCustomers = this.safeNumber(overviewRaw.dealCustomers)
      const hasDealAmount = overviewRaw.dealAmount !== null && overviewRaw.dealAmount !== undefined && overviewRaw.dealAmount !== ''
      const growthAvailable = overviewRaw.customerGrowthPercent !== null && overviewRaw.customerGrowthPercent !== undefined && overviewRaw.customerGrowthPercent !== ''
      const overview = {
        ...overviewRaw, newCustomers, customerGrowthPercent: growth, dealCustomers,
        dealAmountText: hasDealAmount ? money(overviewRaw.dealAmount) : '0',
        dealAmountDisplay: hasDealAmount ? `¥${money(overviewRaw.dealAmount)}` : '暂无成交',
        dealCustomersDisplay: dealCustomers > 0 ? `来自 ${dealCustomers} 位成交客户` : '本月暂无成交客户',
        growthAvailable,
        growthDisplay: growthAvailable ? `${growth >= 0 ? '↑' : '↓'} ${Math.abs(growth)}% 较上月` : '暂无上月数据'
      }
      this.setData({ overview })
    } catch (_) {
      if (version === this._loadVersion) this.setData({ dataError: '经营数据加载失败，请重试' })
    } finally {
      if (version === this._loadVersion) this.setData({ overviewLoading: false })
    }
  },
  async loadSources(month: string, version: number) {
    this.setData({ sourceLoading: true, sourceError: '' })
    try {
      const raw = await api.dataSource(month)
      if (version !== this._loadVersion) return
      const sources = Array.isArray(raw) ? raw : []
      const max = Math.max(1, ...sources.map(x => this.safeNumber(x?.newCustomers)))
      this.setData({ sources: sources.map(x => {
        const count = Math.max(0, this.safeNumber(x?.newCustomers))
        return { ...x, newCustomers: count, dealAmountText: money(x?.dealAmount), width: Math.round(count / max * 100) }
      }) })
    } catch (_) {
      if (version === this._loadVersion) this.setData({ sourceError: '客户来源加载失败，请重试' })
    } finally {
      if (version === this._loadVersion) this.setData({ sourceLoading: false })
    }
  },
  async loadEfficiency(month: string, version: number) {
    this.setData({ efficiencyLoading: true, efficiencyError: '' })
    try {
      const raw = await api.dataFollowEfficiency(month)
      if (version !== this._loadVersion) return
      if (!raw || raw.expectedTasks == null || raw.completedTasks == null) throw new Error('缺少跟进统计')
      const expected = Math.max(0, this.safeNumber(raw.expectedTasks))
      this.setData({ efficiency: {
        expectedTasks: expected, completedTasks: Math.max(0, this.safeNumber(raw.completedTasks)),
        pendingTasks: Math.max(0, this.safeNumber(raw.pendingTasks)),
        followedCustomers: Math.max(0, this.safeNumber(raw.followedCustomers)), followRecords: Math.max(0, this.safeNumber(raw.followRecords)),
        dealCycleCustomers: Math.max(0, this.safeNumber(raw.dealCycleCustomers)),
        completionDisplay: expected > 0 ? this.decimalDisplay(raw.completionPercent, '%') : '—',
        completionWidth: expected > 0 ? Math.min(100, Math.max(0, this.safeNumber(raw.completionPercent))) : 0,
        averageFollowDisplay: raw.followedCustomers > 0 ? this.decimalDisplay(raw.averageFollowCount, '次') : '—',
        averageCycleDisplay: raw.dealCycleCustomers > 0 ? this.decimalDisplay(raw.averageDealCycleDays, '天') : '—',
        asOfText: raw.asOf ? raw.asOf.replace('T', ' ').slice(0, 16) : ''
      } })
    } catch (_) {
      if (version === this._loadVersion) this.setData({ efficiencyError: '跟进效率加载失败，请重试' })
    } finally {
      if (version === this._loadVersion) this.setData({ efficiencyLoading: false })
    }
  },
  async loadSummary(month: string, refresh: boolean, version: number) {
    try {
      const result = await api.dataSummary(month, refresh)
      if (version !== this._loadVersion) return
      if (!result?.content?.trim()) throw new Error('AI 未返回有效经营复盘')
      this.setData({
        summary: result.content, summaryAiGenerated: result.aiGenerated, summaryStale: Boolean(result.stale),
        summaryUpdatedAt: result.updatedAt ? result.updatedAt.replace('T', ' ').slice(0, 16) : '',
        summaryLoading: false
      })
    } catch (_) {
      if (version !== this._loadVersion) return
      this.setData({
        summaryLoading: false,
        summaryError: refresh ? (this.data.summaryAiGenerated ? '重新分析失败，原复盘已保留，请重试' : '重新分析失败，请重试') : '经营复盘加载失败，请重试'
      })
    }
  },
  async loadFunnel(month: string, version: number) {
    this.setData({ funnelLoading: true, funnelError: '' })
    try {
      const raw = await api.dataFunnel(month)
      if (version !== this._loadVersion) return
      const stages = funnelStages.map(stage => {
        const item = Array.isArray(raw?.stages) ? raw.stages.find(item => item?.stage === stage) : undefined
        return { stage, customers: Math.max(0, this.safeNumber(item?.customers)), width: 0 }
      })
      const max = Math.max(1, ...stages.map(item => item.customers))
      const percent = (value: any, denominator: number) => {
        if (denominator <= 0 || value === null || value === undefined || value === '' || !Number.isFinite(Number(value))) return '—'
        return `${Math.min(100, Math.max(0, Number(value))).toFixed(1)}%`
      }
      this.setData({
        funnel: {
          stages: stages.map(item => ({ ...item, width: Math.round(item.customers / max * 100) })),
          quotedCustomers: stages[3].customers,
          lostCustomers: Math.max(0, this.safeNumber(raw?.lostCustomers)),
          inquiryToQuoteDisplay: percent(raw?.inquiryToQuotePercent, stages[0].customers),
          dealConversionDisplay: percent(raw?.dealConversionPercent, stages[0].customers),
          quoteToDealDisplay: percent(raw?.quoteToDealPercent, stages[3].customers),
          cohortBasis: typeof raw?.cohortBasis === 'string' ? raw.cohortBasis : '',
          progressBasis: typeof raw?.progressBasis === 'string' ? raw.progressBasis : ''
        }, funnelLoading: false, funnelError: ''
      })
    } catch (_) {
      if (version !== this._loadVersion) return
      this.setData({ funnelLoading: false, funnelError: '客户漏斗加载失败，请重试' })
    }
  },
  retryFunnel() { return this.loadFunnel(this.data.month, this._loadVersion) },
  retryOverview() { return this.loadOverview(this.data.month, this._loadVersion) },
  retrySources() { return this.loadSources(this.data.month, this._loadVersion) },
  retryEfficiency() { return this.loadEfficiency(this.data.month, this._loadVersion) },
  toggleFunnel() { this.setData({ funnelExpanded: !this.data.funnelExpanded }) },
  toggleBasis() { this.setData({ showBasis: !this.data.showBasis }) },
  decimalDisplay(value: any, suffix: string) {
    return value == null || value === '' || !Number.isFinite(Number(value)) ? '—' : `${Number(value).toFixed(1)}${suffix}`
  },
  safeNumber(value: any) { const number = Number(value); return Number.isFinite(number) ? number : 0 },
  month(e: WechatMiniprogram.PickerChange) {
    this.setData({ month: String(e.detail.value), overview: { ...emptyOverview }, sources: [],
      funnel: emptyFunnel(), funnelError: '', funnelLoading: true, dataError: '', overviewLoading: true,
      efficiency: emptyEfficiency(), efficiencyLoading: true, efficiencyError: '', sourceLoading: true, sourceError: '',
      summary: '', summaryAiGenerated: false, summaryUpdatedAt: '', summaryStale: false }, () => this.load())
  }
})

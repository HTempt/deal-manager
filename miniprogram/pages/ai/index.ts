import { api } from '../../services/api'
import { shortDate } from '../../utils/date'
import { loadNotificationPreference, requestFollowNotification } from '../../utils/notification'

type AiAction = 'GENERATE' | 'REGENERATE' | 'SHORTEN'
type AiResult = {
  content: string
  reason: string
  nextAction: string
  assessment: string
  suggestedAction: string
  followUpDays?: number
  followUpReason: string
  nextFollowTime?: string
  /** Deprecated monthly field retained by the API for compatibility. */
  remainingThisMonth?: number
  remainingToday: number
  unlimited: boolean
}

Page({
  data: {
    id: '', customer: null as any,
    scenarios: ['首次跟进', '报价后跟进', '客户嫌贵', '客户不回复', '促成成交', '唤醒客户'],
    scenario: '报价后跟进', result: null as AiResult | null, generating: false, generationError: '',
    scheduling: false, nextFollowDisplay: '', adopted: false, lastFeedback: '', loadError: '', loading: true
  },
  async onLoad(q: Record<string, string>) {
    this.setData({ id: q.id,scenario:q.scenario==='唤醒客户'?'唤醒客户':this.data.scenario })
    loadNotificationPreference()
    await this.loadCustomer()
  },
  async loadCustomer() {
    this.setData({loading:true,loadError:''})
    try {
      const [customer,records]=await Promise.all([api.customer(this.data.id),api.customerFollows(this.data.id)])
      this.setData({customer,lastFeedback:records[0]?.content||'暂无跟进记录'})
    } catch (_) {this.setData({loadError:'客户资料加载失败，请重试'})}
    finally {this.setData({loading:false})}
  },
  choose(e: WechatMiniprogram.TouchEvent) {
    if (this.data.generating || this.data.scheduling) return
    this.setData({ scenario: e.currentTarget.dataset.value, result: null, adopted:false, generationError:'' })
  },
  async generate(e?: WechatMiniprogram.TouchEvent) {
    if (this.data.generating || this.data.scheduling || this.data.loading || this.data.loadError) return
    const action: AiAction = e?.currentTarget.dataset.action || (this.data.result ? 'REGENERATE' : 'GENERATE')
    const previousContent = this.data.result?.content
    this.setData({ generating: true, generationError:'' })
    try {
      const result = await api.generateAi({
        customerId: Number(this.data.id), scenario: this.data.scenario, action,
        ...(previousContent ? { previousContent } : {})
      })
      if (![result?.assessment, result?.suggestedAction, result?.content, result?.followUpReason]
        .every(value => typeof value === 'string' && value.trim())) throw new Error('AI 未返回完整跟进建议，请重试')
      this.setData({ result, nextFollowDisplay:shortDate(result.nextFollowTime),
        adopted:Boolean(result.nextFollowTime&&result.nextFollowTime===this.data.customer?.nextFollowTime) })
    } catch (error:any) {
      const raw=typeof error?.message==='string'?error.message:typeof error?.errMsg==='string'?error.errMsg:''
      const detail=/timeout|超时/i.test(raw)?'请求超时，请重试':/request:fail/i.test(raw)?'网络请求失败，请重试':raw
      this.setData({generationError:`${this.data.result?'生成失败，原建议已保留，请重试':'生成失败，请重试'}${detail?'：'+detail:''}`})
    } finally {
      this.setData({ generating: false })
    }
  },
  copy() {
    if (!this.data.result) return
    wx.setClipboardData({ data: this.data.result.content, success: () => wx.showToast({ title: '话术已复制' }) })
  },
  async adoptFollowUp() {
    const nextFollowTime=this.data.result?.nextFollowTime
    if(!nextFollowTime || this.data.scheduling || this.data.generating || this.data.adopted) return
    this.setData({scheduling:true})
    try {
      const customer=await api.customer(this.data.id)
      this.setData({customer})
      if(customer.status!=='ACTIVE') {
        wx.showToast({title:'已成交或已流失，无法安排跟进',icon:'none'})
        return
      }
      if(customer.nextFollowTime===nextFollowTime) {this.setData({adopted:true});return}
      const confirmed=await new Promise<boolean>(resolve=>wx.showModal({
        title:'确认跟进安排',
        content:customer.nextFollowTime
          ? `将现有计划 ${shortDate(customer.nextFollowTime)} 改为 ${shortDate(nextFollowTime)}？`
          : `安排在 ${shortDate(nextFollowTime)} 跟进？`,
        success:r=>resolve(r.confirm),fail:()=>resolve(false)
      }))
      if(!confirmed) return
      const updated=await api.scheduleCustomer(this.data.id,nextFollowTime)
      this.setData({customer:updated,adopted:true})
      wx.showToast({title:'已安排跟进'})
      await requestFollowNotification()
    } catch (_) {} finally {this.setData({scheduling:false})}
  }
})

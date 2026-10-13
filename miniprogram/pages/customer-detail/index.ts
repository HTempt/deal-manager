import { api } from '../../services/api'
import { money, maskPhone, intent } from '../../utils/format'
import { shortDate, today } from '../../utils/date'

function errorText(error:any) {
  const message=typeof error?.message==='string'?error.message:typeof error?.errMsg==='string'?error.errMsg:''
  if(/timeout|超时/i.test(message))return '请求超时，请重试'
  if(/request:fail/i.test(message))return '网络请求失败，请重试'
  return message
}

Page({
  data:{ id:'',customer:null as any,records:[] as any[],loading:true,loadError:'',
    summary:null as any,summaryLoading:false,summaryError:'',summaryNotice:'',summaryUpdatedAt:'',
    contactDays:0,hasContactHistory:false,
    dealVisible:false,deal:{amount:'',productName:'',dealDate:today()} },
  _loadVersion:0,
  _summaryVersion:0,
  _summaryRefresh:false,
  onLoad(q:Record<string,string>){this.setData({id:q.id})},
  onShow(){if(this.data.id)this.load()},
  async load(refresh=false){
    refresh=refresh===true
    const version=++this._loadVersion
    ++this._summaryVersion
    this._summaryRefresh=refresh
    this.setData({loading:true,loadError:'',summaryLoading:refresh,summaryError:'',summaryNotice:''})
    try {
      const [raw,rawRecords]=await Promise.all([api.customer(this.data.id),api.customerFollows(this.data.id)])
      if(version!==this._loadVersion) return
      const customer={...raw,avatar:(raw.name||'?').substring(0,1),budgetText:money(raw.budget),intentText:intent(raw.intentionScore),phoneText:maskPhone(raw.phone),nextText:shortDate(raw.nextFollowTime),
        canWake:raw.status==='ACTIVE'&&['AT_RISK','DORMANT','LONG_DORMANT'].includes(raw.dormancyLevel)}
      const records=rawRecords.map((x:any)=>({...x,createdText:shortDate(x.createdAt),nextText:shortDate(x.nextFollowTime)}))
      const reference=raw.lastFollowTime||rawRecords[0]?.createdAt||raw.createdAt
      const referenceMs=reference?Date.parse(`${reference.replace(' ','T')}+08:00`):NaN
      const contactDays=Number.isFinite(referenceMs)?Math.max(0,Math.floor((Date.now()-referenceMs)/86400000)):0
      this.setData({customer,records,contactDays,hasContactHistory:Boolean(raw.lastFollowTime||records.length),deal:{...this.data.deal,amount:customer.budget||''},loading:false})
      await this.loadSummary(refresh)
    } catch (error:any) {
      if(version===this._loadVersion){const detail=errorText(error);this.setData({loadError:`客户资料加载失败，请重试${detail?'：'+detail:''}`})}
    }
    finally {if(version===this._loadVersion)this.setData({loading:false,summaryLoading:false})}
  },
  async loadSummary(refresh=false){
    const version=++this._summaryVersion
    this._summaryRefresh=refresh
    this.setData({summaryLoading:true,summaryError:'',summaryNotice:''})
    try {
      const summary=await api.customerSummary(this.data.id,refresh)
      if(version!==this._summaryVersion) return
      if(!summary?.overview?.trim()||!summary?.suggestion?.trim()||summary.aiGenerated===false) throw new Error('AI 未返回有效客户摘要，请重试')
      const unchanged=summary.overview===this.data.summary?.overview&&summary.suggestion===this.data.summary?.suggestion
      const summaryNotice=!refresh?'':unchanged?'摘要已重新分析，内容与上次一致':'客户摘要已更新'
      this.setData({summary,summaryNotice,summaryUpdatedAt:summary.updatedAt?summary.updatedAt.replace('T',' ').slice(0,19):''})
    } catch (error:any) {
      if(version!==this._summaryVersion) return
      const message=this.data.summary?'摘要更新失败，原摘要已保留':'客户摘要加载失败，请重试'
      const detail=errorText(error)
      this.setData({summaryError:`${message}${detail?'：'+detail:''}`})
    } finally {if(version===this._summaryVersion)this.setData({summaryLoading:false})}
  },
  refreshSummary(){if(!this.data.summaryLoading&&!this.data.loading)return this.load(true)},
  retrySummary(){if(!this.data.summaryLoading&&!this.data.loading)return this._summaryRefresh?this.load(true):this.loadSummary(false)},
  retryLoad(){if(!this.data.loading&&!this.data.summaryLoading)return this.load(this._summaryRefresh)},
  onUnload(){++this._loadVersion;++this._summaryVersion},
  edit(){wx.navigateTo({url:`/pages/customer-edit/index?id=${this.data.id}`})},
  record(){wx.navigateTo({url:`/pages/follow-edit/index?customerId=${this.data.id}`})},
  ai(){wx.navigateTo({url:`/pages/ai/index?id=${this.data.id}${this.data.customer?.canWake?'&scenario='+encodeURIComponent('唤醒客户'):''}`})},
  call(){const p=this.data.customer?.phone;if(p)wx.makePhoneCall({phoneNumber:p})},
  showDeal(){this.setData({dealVisible:true})}, closeDeal(){this.setData({dealVisible:false})},
  noop(){},
  dealInput(e:WechatMiniprogram.Input){this.setData({[`deal.${e.currentTarget.dataset.key}`]:e.detail.value})},
  dealDate(e:WechatMiniprogram.PickerChange){this.setData({'deal.dealDate':e.detail.value})},
  async confirmDeal(){if(!Number(this.data.deal.amount))return wx.showToast({title:'请输入成交金额',icon:'none'});await api.createDeal({customerId:Number(this.data.id),amount:Number(this.data.deal.amount),productName:this.data.deal.productName,dealTime:`${this.data.deal.dealDate}T12:00:00`});this.setData({dealVisible:false});wx.showToast({title:'恭喜成交！'});this.load()},
  remove(){wx.showModal({title:'删除客户',content:'删除后将同时取消待跟进任务，确定继续吗？',confirmColor:'#c95632',success:async r=>{if(r.confirm){await api.deleteCustomer(this.data.id);wx.showToast({title:'已删除'});setTimeout(()=>wx.navigateBack(),400)}}})}
})

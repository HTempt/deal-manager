import { api } from '../../services/api'
import { money, intent } from '../../utils/format'
import { shortDate, today, toApiDate } from '../../utils/date'
import { loadNotificationPreference, requestFollowNotification } from '../../utils/notification'

Page({
  data: {
    tabs: [{k:'ALL',n:'全部',count:0},{k:'FOLLOW',n:'待跟进',count:0},{k:'UNSCHEDULED',n:'待安排',count:0},{k:'DORMANT_ALL',n:'沉睡客户',count:0},{k:'HIGH',n:'高意向',count:0},{k:'QUOTED',n:'报价中',count:0},{k:'DEAL',n:'已成交',count:0},{k:'LOST',n:'已流失',count:0}],
    dormancyFilters:[{k:'DORMANT_ALL',n:'全部沉睡'},{k:'AT_RISK',n:'即将沉睡'},{k:'DORMANT',n:'沉睡'},{k:'LONG_DORMANT',n:'长期沉睡'}],
    dormancyFilter:'DORMANT_ALL',
    active: 'ALL', keyword: '', list: [] as any[], total: 0, hasCustomers: false, loading: true,
    page:1, hasMore:false, loadingMore:false, loadError:'', loadMoreError:'',
    savingId: '', customVisible: false, customId: '', customName: '', customDate: today(1), customTime: '10:00', minDate: today()
  },
  _loadVersion: 0,
  onUnload(){++this._loadVersion;clearTimeout((this as any).timer)},
  onShow() {
    loadNotificationPreference()
    const filter = wx.getStorageSync('customerFilter')
    wx.removeStorageSync('customerFilter')
    if (this.data.tabs.some(x => x.k === filter)) this.setData({active:filter,keyword:'',dormancyFilter:'DORMANT_ALL'})
    this.load()
  },
  async load() {
    const version = ++this._loadVersion
    const active = this.data.active
    const keyword = this.data.keyword
    const filter=active==='DORMANT_ALL'?this.data.dormancyFilter:active
    this.setData({loading:true,loadError:'',loadMoreError:'',loadingMore:false,list:[],hasMore:false,page:1})
    const q = `?filter=${filter}&keyword=${encodeURIComponent(keyword)}&size=100`
    try {
      const data = await api.customers(q)
      const list=this.formatCustomers(data.records||[])
      const tabResults = await Promise.all(this.data.tabs.map((tab:any) => {
        if (tab.k === filter) return Promise.resolve(data)
        const tabQuery = `?filter=${tab.k}&keyword=${encodeURIComponent(keyword)}&size=1`
        return api.customers(tabQuery)
      }))
      const tabs = this.data.tabs.map((tab:any, index:number) => ({...tab, count: Number(tabResults[index]?.total || 0)}))
      if(version!==this._loadVersion) return
      this.setData({list, tabs, total:Number(data.total||0),hasCustomers:Number(tabResults[0]?.total||0)>0,hasMore:list.length<Number(data.total||0)})
      // 仅“全部”且无搜索条件时，空列表才代表账号确实还没有客户。
      if (active === 'ALL' && !keyword) this.setData({hasCustomers: Boolean(data.total || list.length)})
    } catch (_) {if(version===this._loadVersion)this.setData({loadError:'客户加载失败，请重试'})} finally { if(version===this._loadVersion) this.setData({loading:false}) }
  },
  formatCustomers(records:any[]) {
    const labels:Record<string,string>={AT_RISK:'即将沉睡',DORMANT:'沉睡',LONG_DORMANT:'长期沉睡'}
    return records.map(x=>({...x,avatar:(x.name||'?').substring(0,1),budgetText:money(x.budget),intentText:intent(x.intentionScore),nextText:shortDate(x.nextFollowTime),unscheduled:x.status==='ACTIVE'&&!x.nextFollowTime,
      dormancyText:labels[x.dormancyLevel]||'',canWake:x.status==='ACTIVE'&&Boolean(labels[x.dormancyLevel]),
      inactivityText:x.hasContactHistory?`${x.daysWithoutFollow} 天未联系`:`尚未跟进，创建已 ${x.daysWithoutFollow||0} 天`}))
  },
  changeDormancy(e:WechatMiniprogram.TouchEvent) {this.setData({dormancyFilter:e.currentTarget.dataset.key},()=>this.load())},
  onReachBottom() {return this.loadMore()},
  async loadMore() {
    if(this.data.loading||this.data.loadingMore||!this.data.hasMore)return
    const version=this._loadVersion
    const filter=this.data.active==='DORMANT_ALL'?this.data.dormancyFilter:this.data.active
    const page=this.data.page+1
    this.setData({loadingMore:true,loadMoreError:''})
    try {
      const data=await api.customers(`?filter=${filter}&keyword=${encodeURIComponent(this.data.keyword)}&size=100&page=${page}`)
      if(version!==this._loadVersion)return
      const list=[...this.data.list,...this.formatCustomers(data.records||[])]
      this.setData({list,page,total:Number(data.total||0),hasMore:(data.records||[]).length>0&&list.length<Number(data.total||0)})
    }catch(_){if(version===this._loadVersion)this.setData({loadMoreError:'更多客户加载失败，请重试'})}
    finally{if(version===this._loadVersion)this.setData({loadingMore:false})}
  },
  wake(e:WechatMiniprogram.TouchEvent) {wx.navigateTo({url:`/pages/ai/index?id=${e.currentTarget.dataset.id}&scenario=${encodeURIComponent('唤醒客户')}`})},
  changeTab(e: WechatMiniprogram.TouchEvent) { this.setData({active:e.currentTarget.dataset.key},()=>this.load()) },
  search(e: WechatMiniprogram.Input) { this.setData({keyword:e.detail.value}); clearTimeout((this as any).timer); (this as any).timer=setTimeout(()=>this.load(),350) },
  add() { wx.navigateTo({url:'/pages/customer-edit/index'}) },
  detail(e: WechatMiniprogram.TouchEvent) { wx.navigateTo({url:`/pages/customer-detail/index?id=${e.currentTarget.dataset.id}`}) },
  quickSchedule(e: WechatMiniprogram.TouchEvent) {
    if(this.data.savingId) return
    const {id,days}=e.currentTarget.dataset
    return this.saveSchedule(String(id),today(Number(days)),'10:00')
  },
  openCustom(e: WechatMiniprogram.TouchEvent) {
    if(this.data.savingId) return
    this.setData({customVisible:true,customId:String(e.currentTarget.dataset.id),customName:e.currentTarget.dataset.name,customDate:today(1),customTime:'10:00',minDate:today()})
  },
  closeCustom() { if(!this.data.savingId) this.setData({customVisible:false}) },
  noop() {},
  customDate(e:WechatMiniprogram.PickerChange) {this.setData({customDate:String(e.detail.value)})},
  customTime(e:WechatMiniprogram.PickerChange) {this.setData({customTime:String(e.detail.value)})},
  confirmCustom() {return this.saveSchedule(this.data.customId,this.data.customDate,this.data.customTime)},
  async saveSchedule(id:string,date:string,time:string) {
    if(this.data.savingId) return
    const planned=toApiDate(date,time)
    if(!planned||new Date(planned).getTime()<=Date.now()) return wx.showToast({title:'请选择未来的跟进时间',icon:'none'})
    this.setData({savingId:id})
    try {
      await api.scheduleCustomer(id,planned)
      this.setData({customVisible:false})
      await this.load()
      wx.showToast({title:'已安排跟进'})
      await requestFollowNotification()
    } catch (_) {} finally {this.setData({savingId:''})}
  }
})

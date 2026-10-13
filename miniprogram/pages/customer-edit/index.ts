import { api } from '../../services/api'
import { today, toApiDate } from '../../utils/date'
import { loadNotificationPreference, requestFollowNotification } from '../../utils/notification'

Page({
  data: {
    id: '', saving: false, followEnabled: true, intentionMode:'AUTO',
    sources: ['微信','抖音','小红书','转介绍','美团','线下门店','其他'], sourceIndex: 0,
    tagOptions: ['高意向','价格敏感','决策人','需要家人商量','已报价'].map(name => ({name, selected:false})),
    customTag: '',
    stages: ['新客户','需求沟通','方案中','报价中','谈判中'], stageIndex: 0,
    form: { name:'', city:'', address:'', phone:'', need:'', budget:'', tags:[] as string[], date:today(1), time:'10:00', intentionScore:10 }
  },
  onLoad(query: Record<string,string>) {
    loadNotificationPreference()
    if (query.id) { this.setData({id:query.id}); wx.setNavigationBarTitle({title:'编辑客户'}); this.load(query.id) }
  },
  async load(id:string) {
    const c=await api.customer(id); const d=c.nextFollowTime ? c.nextFollowTime.slice(0,10) : ''; const t=c.nextFollowTime ? c.nextFollowTime.slice(11,16) : '10:00'
    const stages=c.stage&&!this.data.stages.includes(c.stage)?[...this.data.stages,c.stage]:this.data.stages
    const sourceIndex=Math.max(0,this.data.sources.indexOf(c.source)); const stageIndex=Math.max(0,stages.indexOf(c.stage))
    const tags=Array.isArray(c.tags)?c.tags.slice(0,5):[]
    const tagOptions=[...new Set([...this.data.tagOptions.map(x=>x.name),...tags])].map(name=>({name,selected:tags.includes(name)}))
    this.setData({stages,sourceIndex,stageIndex,intentionMode:c.intentionMode||'AUTO',followEnabled:Boolean(c.nextFollowTime),tagOptions,customTag:'',form:{name:c.name,city:c.city||'未填写',address:c.address||'',phone:c.phone||'',need:c.need,budget:c.budget||'',tags,date:d||today(1),time:t,intentionScore:c.intentionScore??10}})
  },
  input(e: WechatMiniprogram.Input) { this.setData({[`form.${e.currentTarget.dataset.key}`]:e.detail.value}) },
  customTagInput(e: WechatMiniprogram.Input) { this.setData({customTag:e.detail.value}) },
  toggleTag(e: WechatMiniprogram.TouchEvent) {
    const tag=String(e.currentTarget.dataset.tag), tags=[...this.data.form.tags], index=tags.indexOf(tag)
    if (index>=0) tags.splice(index,1)
    else { if(tags.length>=5)return wx.showToast({title:'最多选择 5 个标签',icon:'none'}); tags.push(tag) }
    this.setData({form:{...this.data.form,tags},tagOptions:this.data.tagOptions.map(x=>({...x,selected:tags.includes(x.name)}))})
  },
  addCustomTag() {
    const tag=this.data.customTag.trim(), tags=[...this.data.form.tags]
    if(!tag)return
    if(tag.length>20)return wx.showToast({title:'标签不能超过 20 个字',icon:'none'})
    if(tags.includes(tag)){this.setData({customTag:''});return}
    if(tags.length>=5)return wx.showToast({title:'最多添加 5 个标签',icon:'none'})
    tags.push(tag)
    const tagOptions=[...new Set([...this.data.tagOptions.map(x=>x.name),tag])].map(name=>({name,selected:tags.includes(name)}))
    this.setData({customTag:'',form:{...this.data.form,tags},tagOptions})
  },
  source(e: WechatMiniprogram.PickerChange) { this.setData({sourceIndex:Number(e.detail.value)}) },
  stage(e: WechatMiniprogram.PickerChange) { this.setData({stageIndex:Number(e.detail.value)}) },
  scoreMode(e: WechatMiniprogram.TouchEvent) { this.setData({intentionMode:e.currentTarget.dataset.mode}) },
  scoreSlider(e: WechatMiniprogram.SliderChange) { this.setData({'form.intentionScore':e.detail.value}) },
  date(e: WechatMiniprogram.PickerChange) { this.setData({'form.date':e.detail.value}) },
  time(e: WechatMiniprogram.PickerChange) { this.setData({'form.time':e.detail.value}) },
  toggleFollow(e: WechatMiniprogram.SwitchChange) { this.setData({followEnabled:e.detail.value}) },
  async submit() {
    const f=this.data.form
    if (!f.name.trim() || !f.need.trim()) return wx.showToast({title:'请填写姓名和需求',icon:'none'})
    if (!f.city.trim()) return wx.showToast({title:'请输入客户所在城市',icon:'none'})
    if (!/^[\u4e00-\u9fffA-Za-z0-9]+$/.test(f.name)) return wx.showToast({title:'客户姓名只能包含中文、字母或数字',icon:'none'})
    if (this.data.intentionMode==='MANUAL' && (String(f.intentionScore).trim()==='' || !Number.isInteger(Number(f.intentionScore)) || Number(f.intentionScore)<0 || Number(f.intentionScore)>100)) return wx.showToast({title:'意向分须为 0～100 的整数',icon:'none'})
    if (this.data.saving) return
    this.setData({saving:true})
    const payload={name:f.name,city:f.city.trim(),address:f.address.trim()||null,phone:f.phone,need:f.need,budget:f.budget?Number(f.budget):null,source:this.data.sources[this.data.sourceIndex],tags:f.tags,stage:this.data.stages[this.data.stageIndex],intentionMode:this.data.intentionMode,intentionScore:this.data.intentionMode==='MANUAL'?Number(f.intentionScore):null,nextFollowTime:this.data.followEnabled?toApiDate(f.date,f.time):null}
    try { this.data.id ? await api.updateCustomer(this.data.id,payload) : await api.createCustomer(payload); if(payload.nextFollowTime) await requestFollowNotification(); wx.showToast({title:'保存成功'}); setTimeout(()=>wx.navigateBack(),500) } finally { this.setData({saving:false}) }
  }
})

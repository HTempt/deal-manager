import { api } from '../../services/api'
import { today, toApiDate } from '../../utils/date'
import { loadNotificationPreference, requestFollowNotification } from '../../utils/notification'

Page({
  data:{customerId:'',taskId:'',customer:null as any,types:['微信','电话','面谈','其他'],type:'微信',
    statuses:['没有回应','有兴趣','考虑中','准备成交','已流失'],status:'有兴趣',
    activeInquiry:false,priceInquiry:false,manualScore:false,intentionScore:'10',
    nextOptions:[{n:'明天',d:1},{n:'3天后',d:3},{n:'7天后',d:7},{n:'暂不设置',d:0}],
    nextDays:3,custom:false,date:today(3),time:'10:00',content:'',saving:false},
  async onLoad(q:Record<string,string>){
    loadNotificationPreference()
    const customer=await api.customer(q.customerId)
    customer.avatar=(customer.name||'?').substring(0,1)
    this.setData({customerId:q.customerId,taskId:q.taskId||'',customer,
      manualScore:customer.intentionMode==='MANUAL',intentionScore:String(customer.intentionScore??10)})
  },
  chooseType(e:WechatMiniprogram.TouchEvent){this.setData({type:e.currentTarget.dataset.value})},
  chooseStatus(e:WechatMiniprogram.TouchEvent){
    const status=e.currentTarget.dataset.value
    const noReply=['没有回应','已流失'].includes(status)
    this.setData({status,...(noReply?{activeInquiry:false,priceInquiry:false}:{})})
  },
  signals(e:WechatMiniprogram.CheckboxGroupChange){
    this.setData({activeInquiry:e.detail.value.includes('activeInquiry'),priceInquiry:e.detail.value.includes('priceInquiry')})
  },
  manualScore(e:WechatMiniprogram.SwitchChange){this.setData({manualScore:e.detail.value})},
  scoreInput(e:WechatMiniprogram.Input){this.setData({intentionScore:e.detail.value})},
  chooseNext(e:WechatMiniprogram.TouchEvent){const d=Number(e.currentTarget.dataset.days);this.setData({nextDays:d,custom:false,date:d?today(d):''})},
  custom(){this.setData({custom:true,nextDays:-1})},
  date(e:WechatMiniprogram.PickerChange){this.setData({date:String(e.detail.value)})},time(e:WechatMiniprogram.PickerChange){this.setData({time:String(e.detail.value)})},
  content(e:WechatMiniprogram.Input){this.setData({content:e.detail.value})},
  async submit(){
    if(this.data.saving)return
    if(!this.data.content.trim())return wx.showToast({title:'请填写客户反馈',icon:'none'})
    const score=Number(this.data.intentionScore)
    if(this.data.manualScore&&(!this.data.intentionScore.trim()||!Number.isInteger(score)||score<0||score>100))return wx.showToast({title:'意向分须为 0～100 的整数',icon:'none'})
    this.setData({saving:true})
    try{
      const nextFollowTime=this.data.date?toApiDate(this.data.date,this.data.time):null
      const record=await api.createFollow({customerId:Number(this.data.customerId),taskId:this.data.taskId?Number(this.data.taskId):null,
        followType:this.data.type,content:this.data.content,customerFeedback:this.data.status,
        customerStatus:this.data.status==='已流失'?'已流失':null,activeInquiry:this.data.activeInquiry,priceInquiry:this.data.priceInquiry,
        intentionMode:this.data.manualScore?'MANUAL':'AUTO',intentionScore:this.data.manualScore?score:null,nextFollowTime})
      if(nextFollowTime) await requestFollowNotification()
      wx.showModal({title:'跟进已记录',content:`意向：${record.intentionBefore} → ${record.intentionAfter}（${record.intentionMode==='MANUAL'?'人工评分':'综合评分'}）`,
        showCancel:false,confirmText:'完成',success:()=>wx.navigateBack()})
    }finally{this.setData({saving:false})}
  }
})

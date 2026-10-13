import { api } from '../../services/api'
Page({
  data:{setting:{replyStyle:'自然亲切',replyLength:'适中',preferredAddress:'自动判断',businessIntro:'',businessAdvantage:'',commonExpression:''},styles:['自然亲切','专业正式','简洁直接','销售型'],addresses:['哥/姐','您','老板','自动判断'],lengths:['简短','适中','详细'],saving:false},
  async onLoad(){this.setData({setting:await api.aiSetting()})},
  choose(e:WechatMiniprogram.TouchEvent){this.setData({[`setting.${e.currentTarget.dataset.key}`]:e.currentTarget.dataset.value})},
  input(e:WechatMiniprogram.Input){this.setData({[`setting.${e.currentTarget.dataset.key}`]:e.detail.value})},
  async save(){this.setData({saving:true});try{await api.saveAiSetting(this.data.setting);wx.showToast({title:'保存成功'});setTimeout(()=>wx.navigateBack(),400)}finally{this.setData({saving:false})}}
})

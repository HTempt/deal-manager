import { api } from '../../services/api'
Page({
  data:{current:'',options:[] as any[],saving:false},
  async onLoad(){const d=await api.industries();d.options=(d.options||[]).map((x:any)=>({...x,icon:(x.industryName||'?').substring(0,1)}));this.setData(d)},
  choose(e:WechatMiniprogram.TouchEvent){this.setData({current:e.currentTarget.dataset.name})},
  async save(){if(!this.data.current)return wx.showToast({title:'请选择行业',icon:'none'});this.setData({saving:true});try{await api.saveIndustry(this.data.current);wx.showToast({title:'设置成功'});setTimeout(()=>wx.navigateBack(),400)}finally{this.setData({saving:false})}}
})

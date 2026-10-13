import { api } from '../../services/api'
import { cacheNotificationPreference, requestFollowNotification } from '../../utils/notification'

Page({
  data:{profile:null as any,notificationEnabled:false,notificationReady:false,notificationSaving:false},
  onShow(){this.load()},
  async load(){
    try { const profile=await api.profile();profile.avatar=(profile.user.nickname||'老').substring(0,1);this.setData({profile}) } catch (_) {}
    try { const settings=await api.notificationSettings();cacheNotificationPreference(settings.enabled);this.setData({notificationEnabled:settings.enabled,notificationReady:true}) } catch (_) {cacheNotificationPreference(false);this.setData({notificationReady:false})}
  },
  async toggleNotifications(e:WechatMiniprogram.SwitchChange){
    if(this.data.notificationSaving || !this.data.notificationReady) return
    const previous=this.data.notificationEnabled
    const enabled=e.detail.value
    this.setData({notificationSaving:true,notificationEnabled:enabled})
    try {
      const result=await api.setNotificationEnabled(enabled)
      cacheNotificationPreference(result.enabled)
      this.setData({notificationEnabled:result.enabled})
      wx.showToast({title:enabled?'提醒已开启':'提醒已关闭'})
    } catch (_) { cacheNotificationPreference(previous);this.setData({notificationEnabled:previous}) }
    finally { this.setData({notificationSaving:false}) }
  },
  async subscribeNotifications(){
    if(this.data.notificationSaving) return
    this.setData({notificationSaving:true})
    try {
      const accepted=await requestFollowNotification()
      if(accepted) wx.showToast({title:'已同意订阅'})
    } finally {this.setData({notificationSaving:false})}
  },
  go(e:WechatMiniprogram.TouchEvent){wx.navigateTo({url:e.currentTarget.dataset.url})},
  about(){wx.showModal({title:'关于跟客',content:'跟客 1.0.0 · AI 客户跟进助手\n客户来了以后，不让他被忘记。',showCancel:false})}
})

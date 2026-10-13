Page({
  data: { agreed: false },
  dormantCustomers() {
    wx.setStorageSync('customerFilter','DORMANT_ALL')
    wx.switchTab({url:'/pages/customer/index'})
  },
  toggleAgreement() {
    this.setData({ agreed: !this.data.agreed })
  },
  showAgreement() {
    wx.showModal({
      title: '会员服务协议',
      content: '请在开通会员前阅读本协议。会员服务内容、价格、服务期限、续费和退款规则以正式开通页面展示为准。你确认已阅读并同意后，才可以继续开通。',
      showCancel: false,
      confirmText: '我知道了'
    })
  },
  subscribe() {
    if (!this.data.agreed) {
      wx.showToast({ title: '请先阅读并勾选会员服务协议', icon: 'none' })
      return
    }
    wx.showModal({ title: '专业版即将上线', content: '支付能力需要配置微信商户号后启用。当前版本可先体验完整业务闭环。', showCancel: false })
  }
})

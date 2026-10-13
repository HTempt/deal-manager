App<IAppOption>({
  globalData: { user: null },
  onLaunch() {}
})

interface IAppOption {
  globalData: { user: unknown | null }
}

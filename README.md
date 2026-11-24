# 类原生运营商信息优化（carrier-opt）

PixelExperience / 类原生 ROM 上把运营商显示、网络类型和信号图标改回国内习惯的 LSPosed 模块。

**2.0 起没有任何界面**：没有桌面图标，也没有模块自己的设置页。所有开关直接长在
「设置 → 系统 → 状态栏」页里，改完点底部的「重启系统界面」生效。

## 功能

| 开关 | 作用 | 默认 |
| --- | --- | --- |
| 显示 4G | LTE 显示成 4G，载波聚合显示 4G+ | 开 |
| 运营商中文化 | 运营商名换成中国移动 / 联通 / 电信 / 广电 | 开 |
| 锁屏双卡简化 | 锁屏上两个运营商名之间用细竖线 | 开 |
| 修改分隔符 | 分隔符改用字符 `\|` | 关 |
| 数据网络图标优化 | 信号图标不再显示感叹号 | 关 |
| 搜网加速 | 手动搜网边扫边出结果 | 开 |

## 环境

- Android 12 及以上（实测 Redmi K30 Pro Zoom / lmi，PixelExperience Plus 13）
- LSPosed（API 93+），作用域勾选 **系统界面** 和 **设置**
- 不需要 root

## 安装

1. 安装 APK，在 LSPosed 里启用模块，作用域勾「系统界面」和「设置」
2. 重启一次手机（或重启设置进程 / 状态栏），让注入生效
3. 打开 设置 → 系统 → 状态栏，页面最下面就是这组开关

## 原理

- 注入在 `com.android.settings` 的 `com.android.settings.custom.statusbar.StatusBarSettings`
  页面里，在 `PreferenceFragmentCompat#onViewCreated` 之后往 `PreferenceScreen` 里塞开关
- 开关写 `Settings.System`（前缀 `pe_l10n_`），去感叹号同时写 `Settings.Global`
  的 `captive_portal_mode` 和四条检测地址；设置进程本身是系统身份，这些都不需要 root
- 点「重启系统界面」时，模块给 SystemUI 里的注入代码发一条广播，由它自己退出、
  系统重新拉起，新值立刻重绘（设置进程和 SystemUI 不同 uid，从外面杀不掉）

## 注意

- 去感叹号会连带抹掉「未验证 / 数据关闭 / 无服务」这些标记，公共 WiFi 的登录页也不再自动提示
- 切完开关需要点一次「重启系统界面」才会应用到状态栏和锁屏

## 免责

自用模块，按现状提供。刷机、改系统设置有风险，出问题请自行刷回或卸载。

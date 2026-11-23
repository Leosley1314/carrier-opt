# 类原生运营商信息优化（carrier-opt）

面向 **PixelExperience / 类原生 Android 13** 的 LSPosed 模块，包名 `com.aosp.carrier`。
主要解决国内双卡环境下的几个小毛病：LTE 显示成 4G、运营商名是英文、锁屏双卡分隔难看、信号图标挂着感叹号。

> 仅在 **PixelExperience_Plus_lmi-13.0-20230927-1336-OFFICIAL**（Redmi K30 Pro Zoom / lmi）实测通过，其他 ROM / 版本不保证可用。

## 功能

| 开关 | 作用 | 作用域 |
| --- | --- | --- |
| 显示 4G | LTE 一律显示 4G，载波聚合显示 4G+ | SystemUI、设置 |
| 运营商中文化 | 移动 / 联通 / 电信 / 广电 | SystemUI、设置 |
| 锁屏双卡简化 | 锁屏双卡时两个运营商名之间改细垒线 | SystemUI |
| 修改分隔符 | 分隔符换成垒线字符，方便对比效果 | SystemUI |
| 数据网络图标优化 | 抹掉信号图标上的感叹号；副作用是公共 WiFi 登录页不再自动提示 | SystemUI |
| 搜网加速 | 手动搜网只扫 LTE/NR 并改成增量返回，边扫边出结果 | 设置 |
| 隐藏启动器图标 | 禁用 alias，图标消失；仍可从 LSPosed 管理器打开设置页 | 自身 |

页面底部另有「重启系统界面」一行，点一下重启 SystemUI，改完开关立即生效。

## 安装

1. 装 APK；
2. LSPosed 里启用模块，作用域勾选 `com.android.systemui`、`com.android.settings`；
3. 点模块里的「重启系统界面」（或重启手机）。

## 构建

需要 JDK 17 + Android SDK 33：

```bash
export JAVA_HOME=/path/to/jdk17
export ANDROID_HOME=/path/to/android-sdk
gradle :app:assembleRelease
# 产物：app/build/outputs/apk/release/app-release.apk
```

本仓库不带 `gradle-wrapper.jar`（二进制未入库），CI 用 `gradle/actions/setup-gradle` 指定 Gradle 7.6 构建；
本地想用 wrapper 可以先跑一次 `gradle wrapper --gradle-version 7.6` 生成。

release 用的是 debug 签名，方便自用直装；正式分发请换成自己的 keystore。

## 说明

- 开关状态写在 `Settings.System` 的 `pe_l10n_*` 键里，SystemUI 与设置页跳进程读取一致，LSPosed 偏好镜像只做兜底；
- 「数据网络图标优化」会一并抹掉「未验证 / 数据关闭 / 无服务」这类标记，这是实现方式的副作用；
- 去感叹号需要 root：模块会改 `captive_portal_mode` 与联网检测 URL，`su` 需已在 KernelSU / Magisk 里授权；
- 图标只有 `mipmap-anydpi-v26` 的矢量自适应图标（minSdk 31，够用），没有放位图 fallback。

## 许可

MIT

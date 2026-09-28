# SpazPeek

**OPPO ColorOS 16 头部跟踪接管工具，附带空间音频链路取证。**

让 AirPods Pro 2 的头动驱动系统空间音频，声场固定正前方。

## 主要功能：头部跟踪（Shizuku root）

针对 OPPO / ColorOS 16（Android 16），基于 Shizuku root 能力做头追接管。

- AirPods Pro 2 AACP 头追直读，四元数解码出 pitch / yaw
- PoseBridge TCP 桥 127.0.0.1:53987，把姿态换算成 HID 量发给 vtracker3
- vtracker3 伪装蓝牙指纹 bt:MAC，驱动 audioserver 空间音频
- 恒定 20ms 高速模式，40Hz 以上判稳，绿字显示已稳定发送中
- 声场固定正前方：发送角与头动反向，左右转点头声场钉住
- 方向标定：左转抬头自动测符号，一键左右反向 / 上下反向
- 发送幅度滑杆，热更新即时生效
- 3D 球可视化真实头朝向，球下两行字显示发送 rx rz 与角度
- 前台服务 + 反冻守护 + 静音保持，后台不掉线

链路：AirPods AACP -> SpazPeek 解码 -> PoseBridge -> vtracker3 -> 系统空间音频。

实测机：一加 13T PKX110 ColorOS 16，APatch root + Zygisk + LSPosed + Shizuku。

## 附加功能：输出声道信息

- 双渲检测：App 已渲染又输出立体声，系统再渲一遍会糊
- App 输出格式：单声道 / 立体声 / 5.1 / 7.1.4 通道布局
- 头部跟踪状态：开启 / 关闭 / 相对世界 / 世界锁定
- 系统重渲：Spatializer 渲染引擎进程检测
- Oplus Spatializer / Dolby / Dirac / DTS 检测
- 输出链路：蓝牙 A2DP / 扬声器 / 有线 / USB
- 重采样：源采样率 vs 输出采样率
- 一键复制 / 导出报告，3 秒自动刷新

数据源：dumpsys audio / media.audio_flinger / media.audio_policy，经 Shizuku 直读，纯本地无网络权限。

## 使用

1. 装 Shizuku 并启动，SpazPeek 里授权
2. 选已配对的 AirPods Pro 2，打开接管开关
3. 等帧率 40Hz 以上绿字，左右转点头听声场是否固定正前方
4. 方向不对就点方向标定，或单独点左右反向 / 上下反向
5. 发送幅度滑杆按听感调
6. 附加取证：播放音乐后点刷新，看输出总览

## 构建

要求 JDK 17+、Android SDK 35。

```bash
./gradlew assembleDebug
```

产物：app/build/outputs/apk/debug/app-debug.apk

> 设备内 proot 直接构建，在 `~/.gradle/gradle.properties` 加：
>
> ```properties
> android.aapt2FromMavenOverride=/root/Android/build-tools/35.0.0/aapt2
> android.aapt2.process.daemon=false
> ```

## 兼容

- minSdk 29 / targetSdk 35
- Material 3 动态取色 / 深色模式
- OPPO ColorOS 16 头追接管为主，AOSP 标准 dump 取证为辅

## License

[MIT](LICENSE)

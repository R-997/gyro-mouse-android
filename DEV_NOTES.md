# 🖱️ Gyro Mouse - 开发备忘录

> 记录当前开发进度和关键信息，方便下次继续开发

---

## 📱 项目概述

**Gyro Mouse** - 将 Android 手机变成蓝牙 HID 鼠标，利用陀螺仪控制电脑光标移动。

- **目标平台**: Android 9.0+ (API 28+)
- **技术栈**: Kotlin + Android HID Device API
- **连接方式**: 蓝牙 HID (无需电脑端安装软件)

---

## ✅ 已完成功能

### 核心功能
- [x] HID 鼠标设备注册与连接
- [x] 陀螺仪传感器数据采集
- [x] 蓝牙配对设备自动连接
- [x] 鼠标移动/点击事件发送
- [x] **滚轮控制（Y轴歪头）** ✨ 2026-03-27

### 传感器映射（当前配置）
| 手机动作 | 陀螺仪轴 | 鼠标控制 |
|---------|---------|---------|
| 前后倾斜（点头） | X轴 | 上下移动 |
| 水平旋转（像方向盘） | Z轴（反转） | 左右移动 |
| 左右倾斜（歪头） | Y轴 | **滚轮滚动** ✨ |

### 滚轮控制（2026-03-27 新增）
- **触发方式**: 左右歪头（Y轴旋转）
- **左歪头**: 向上滚动
- **右歪头**: 向下滚动
- **灵敏度可调**: 0.5 - 5.0（默认 2.0）
- **开关**: 可在设置中启用/禁用

### UI 与交互
- [x] 灵敏度调节：0.5 - 10.0（默认 3.5）
- [x] 平滑度调节：0 - 100%
- [x] 连接状态实时显示
- [x] 连接倒计时：8秒超时
- [x] 双击返回键退出应用
- [x] 设备特定设置记忆（每个设备独立保存灵敏度）

### 稳定性优化
- [x] 阿里云 Gradle 镜像（国内加速）
- [x] 8秒连接超时处理
- [x] 退出时正确清理 HID 资源（修复弹窗问题）
- [x] QoS 设置（Win10 兼容性）
- [x] 蓝牙可发现性设置

---

## 🔧 技术细节

### 关键类
```
GyroMouseManager.kt
├── initHidDevice()      - 初始化 HID 设备
├── connectToDevice()    - 连接指定设备
├── sendMouseMove()      - 发送鼠标移动事件
├── onSensorChanged()    - 传感器数据处理（核心算法）
├── load/saveDeviceSettings() - 设备设置持久化
└── cleanup()            - 资源清理

MainActivity.kt
├── autoInitHid()        - 自动初始化
├── tryConnectPairedDevice() - 自动连接已配对设备
├── startConnectProgress()   - 连接倒计时显示
└── onKeyDown()          - 双击返回退出
```

### 传感器数据处理（核心算法）
```kotlin
// 当前映射（2026-03-21 配置）
val gyroX = event.values[0]  // X轴 - 前后倾斜 → 上下移动
val gyroZ = event.values[2]  // Z轴 - 水平旋转 → 左右移动（已反转）

val targetVelX = -gyroZ * sensitivity * 100  // 左右
val targetVelY = -gyroX * sensitivity * 100  // 上下
```

### HID 报告描述符
- 标准 USB HID Mouse 描述符
- 3 个按钮（左、右、中）
- X/Y 轴相对移动（-127 到 127）
- 支持滚轮

---

## 📂 文件结构

```
app/src/main/
├── java/com/gyromouse/
│   ├── MainActivity.kt      (主界面)
│   └── GyroMouseManager.kt  (核心管理类)
├── res/layout/
│   └── activity_main.xml    (UI 布局)
├── res/values/
│   ├── colors.xml
│   ├── strings.xml
│   └── themes.xml
└── AndroidManifest.xml      (权限声明)
```

---

## 🐛 已知问题

1. **首次配对流程** - 需要在 Win10 端主动发现设备，不能手机端主动连接
2. **部分手机兼容性** - 部分国产 ROM 可能阉割了 HID Device 功能
3. **传感器漂移** - 长时间使用可能出现零点漂移（建议添加校准功能）

---

## 💡 待优化项（想法池）

### 高优先级
- [x] ~~添加滚轮控制~~ ✅ 已完成（Y轴歪头）
- [ ] 添加键盘模式切换
- [ ] 添加 AirMouse 模式（用加速度计）

### 中优先级
- [ ] 传感器零点校准功能
- [ ] 更多平滑算法选项（卡尔曼滤波）
- [ ] 震动反馈
- [ ] 通知栏快捷控制

### 低优先级
- [ ] 自定义按键功能
- [ ] 宏录制
- [ ] 多设备切换
- [ ] 深色/浅色主题切换

---

## 🔌 快速开始（下次开发）

### 1. 打开项目
```
桌面/gyro-mouse-android  或
GitHub: https://github.com/R-997/gyro-mouse-android
```

### 2. 关键文件位置
- 传感器逻辑：`app/src/main/java/com/gyromouse/GyroMouseManager.kt`
- UI 逻辑：`app/src/main/java/com/gyromouse/MainActivity.kt`

### 3. 当前传感器配置
```
X轴 → 上下移动
Z轴（反转） → 左右移动
灵敏度默认：3.5
```

---

## 📝 修改历史

| 日期 | 修改内容 |
|------|---------|
| 2026-03-21 | 初始版本，完成基础 HID 鼠标功能 |
| 2026-03-21 | 添加国内镜像、8秒超时、设备记忆 |
| 2026-03-21 | 修改传感器映射为 X/Z 轴 |
| 2026-03-21 | 添加双击退出、修复退出弹窗 |
| 2026-03-27 | 添加 Y轴滚轮控制（左右歪头触发） |

---

## 📞 继续开发

下次对话时，可以说：
> "继续开发陀螺仪鼠标 App，我要添加滚轮功能" 
> 或
> "打开桌面的 gyro-mouse-android 项目，我要改传感器灵敏度"

我会查看代码继续帮你开发！

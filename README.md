# 🖱️ Gyro Mouse - Android 蓝牙陀螺仪鼠标

将 Android 手机变成蓝牙鼠标，利用陀螺仪控制光标移动。

## ✨ 功能特性

- 🎯 **陀螺仪控制**：倾斜手机即可控制鼠标移动
- 🔗 **蓝牙 HID**：无需安装任何软件到电脑，原生支持
- 🎚️ **灵敏度调节**：实时调整移动速度和响应
- 🖱️ **完整鼠标功能**：左键、右键、中键全部支持
- 📱 **Material Design**：现代美观的界面设计

## 📋 系统要求

- Android 9.0 (API 28) 或更高版本
- 支持蓝牙 HID Device 功能
- 陀螺仪和加速度计传感器

## 🚀 使用方法

### 1. 准备工作

1. 确保手机蓝牙已开启
2. 在手机系统设置中，与目标电脑配对蓝牙

### 2. 启动应用

1. 打开 Gyro Mouse 应用
2. 点击 **"初始化 HID"** 按钮
3. 等待初始化完成

### 3. 连接电脑

1. 点击 **"连接设备"**
2. 从列表中选择你的电脑
3. 等待连接成功（状态指示器变绿）

### 4. 控制鼠标

- **移动**：倾斜手机
  - 左右倾斜 → 左右移动
  - 前后倾斜 → 上下移动
- **点击**：按下屏幕下方的按钮
  - 左键 / 右键 / 中键

### 5. 调整设置

- **灵敏度**：控制光标移动速度
- **平滑度**：减少抖动，使移动更顺滑

## 🛠️ 项目结构

```
gyro-mouse-android/
├── app/
│   ├── src/main/java/com/gyromouse/
│   │   ├── MainActivity.kt          # 主界面
│   │   └── GyroMouseManager.kt      # 核心管理类
│   └── src/main/res/                # 布局和资源文件
├── build.gradle                     # 项目构建配置
└── settings.gradle                  # 项目设置
```

## 🔧 构建说明

### 使用 Android Studio

1. 打开 Android Studio
2. 选择 "Open an existing Android Studio project"
3. 选择 `gyro-mouse-android` 文件夹
4. 等待 Gradle 同步完成
5. 点击 Run 按钮编译并安装

### 使用命令行

```bash
cd gyro-mouse-android
./gradlew assembleDebug
```

生成的 APK 位于：`app/build/outputs/apk/debug/app-debug.apk`

## ⚠️ 已知限制

1. **Android 版本**：需要 Android 9.0+（HID Device 支持）
2. **配对**：使用前必须先在系统设置中配对电脑
3. **兼容性**：部分旧设备可能不支持蓝牙 HID 功能
4. **延迟**：蓝牙连接可能有轻微延迟，不适合 FPS 游戏

## 📝 技术细节

### HID 报告描述符

应用使用标准的 USB HID Mouse 报告描述符：
- 3 个按钮（左、右、中）
- X/Y 轴相对移动（-127 到 127）
- 滚轮支持

### 传感器处理

- **陀螺仪**：检测旋转速度，转换为鼠标移动
- **加速度计**：可用于未来扩展（如敲击检测）
- **平滑算法**：指数移动平均减少抖动

## 🔒 权限说明

应用需要以下权限：

- `BLUETOOTH` / `BLUETOOTH_ADMIN`：蓝牙基本功能
- `BLUETOOTH_CONNECT` (Android 12+)：连接设备
- `BLUETOOTH_ADVERTISE` (Android 12+)：HID 设备广播

## 🐛 故障排除

### 初始化失败
- 检查蓝牙是否开启
- 确认 Android 版本 ≥ 9.0
- 查看设备是否支持蓝牙 HID

### 无法连接
- 确认电脑已与手机配对
- 尝试断开电脑上的其他蓝牙鼠标
- 重启电脑蓝牙

### 移动不流畅
- 调整灵敏度和平滑度设置
- 确保握持手机时保持稳定
- 校准传感器

## 📄 许可证

MIT License - 自由使用和修改

## 🙏 致谢

- Android Bluetooth HID API
- Material Design Components
- 所有贡献者和测试者

---

Made with ❤️ by OpenClaw
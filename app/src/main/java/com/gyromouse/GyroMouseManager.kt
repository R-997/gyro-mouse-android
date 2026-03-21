package com.gyromouse

import android.Manifest
import android.bluetooth.*
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import java.nio.ByteBuffer
import java.util.*
import kotlin.math.*

/**
 * GyroMouseManager - 核心管理类
 * 整合传感器数据和蓝牙 HID 鼠标功能
 */
class GyroMouseManager(private val context: Context) : SensorEventListener {

    companion object {
        const val TAG = "GyroMouse"
        const val HID_REPORT_ID = 1
        const val CONNECT_TIMEOUT_MS = 8000L  // 8秒连接超时（系统通常8秒超时）
        
        // 鼠标报告描述符（标准 USB HID Mouse）
        val MOUSE_REPORT_DESCRIPTOR = byteArrayOf(
            0x05.toByte(), 0x01.toByte(),        // Usage Page (Generic Desktop)
            0x09.toByte(), 0x02.toByte(),        // Usage (Mouse)
            0xA1.toByte(), 0x01.toByte(),        // Collection (Application)
            0x09.toByte(), 0x01.toByte(),        //   Usage (Pointer)
            0xA1.toByte(), 0x00.toByte(),        //   Collection (Physical)
            0x05.toByte(), 0x09.toByte(),        //     Usage Page (Button)
            0x19.toByte(), 0x01.toByte(),        //     Usage Minimum (1)
            0x29.toByte(), 0x03.toByte(),        //     Usage Maximum (3)
            0x15.toByte(), 0x00.toByte(),        //     Logical Minimum (0)
            0x25.toByte(), 0x01.toByte(),        //     Logical Maximum (1)
            0x95.toByte(), 0x03.toByte(),        //     Report Count (3)
            0x75.toByte(), 0x01.toByte(),        //     Report Size (1)
            0x81.toByte(), 0x02.toByte(),        //     Input (Data, Variable, Absolute)
            0x95.toByte(), 0x01.toByte(),        //     Report Count (1)
            0x75.toByte(), 0x05.toByte(),        //     Report Size (5)
            0x81.toByte(), 0x03.toByte(),        //     Input (Constant, Variable, Absolute)
            0x05.toByte(), 0x01.toByte(),        //     Usage Page (Generic Desktop)
            0x09.toByte(), 0x30.toByte(),        //     Usage (X)
            0x09.toByte(), 0x31.toByte(),        //     Usage (Y)
            0x09.toByte(), 0x38.toByte(),        //     Usage (Wheel)
            0x15.toByte(), 0x81.toByte(),        //     Logical Minimum (-127)
            0x25.toByte(), 0x7F.toByte(),        //     Logical Maximum (127)
            0x75.toByte(), 0x08.toByte(),        //     Report Size (8)
            0x95.toByte(), 0x03.toByte(),        //     Report Count (3)
            0x81.toByte(), 0x06.toByte(),        //     Input (Data, Variable, Relative)
            0xC0.toByte(),                       //   End Collection
            0xC0.toByte()                        // End Collection
        )
    }

    interface ConnectionListener {
        fun onConnectionStateChanged(connected: Boolean)
        fun onError(message: String)
        fun onSensorData(x: Float, y: Float)
        fun onConnectionTimeout()
    }

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter = bluetoothManager.adapter
    private val prefs: SharedPreferences = context.getSharedPreferences("GyroMousePrefs", Context.MODE_PRIVATE)
    
    private var gyroscope: Sensor? = null
    private var accelerometer: Sensor? = null
    
    private var hidDevice: BluetoothHidDevice? = null
    private var connectedDevice: BluetoothDevice? = null
    
    private var listener: ConnectionListener? = null
    private val handler = Handler(Looper.getMainLooper())
    private var connectTimeoutRunnable: Runnable? = null
    
    // 传感器融合参数
    private var lastTimestamp = 0L
    private var velocityX = 0f
    private var velocityY = 0f
    private var positionX = 0f
    private var positionY = 0f
    
    // 灵敏度设置 (0.5 - 10.0)，默认更高
    var sensitivity = 3.5f
    var smoothingFactor = 0.3f  // 平滑系数
    
    // 按钮状态
    private var leftButton = false
    private var rightButton = false
    private var middleButton = false

    fun setListener(listener: ConnectionListener) {
        this.listener = listener
    }
    
    /**
     * 加载设备特定设置
     */
    fun loadDeviceSettings(deviceAddress: String?) {
        deviceAddress?.let { addr ->
            sensitivity = prefs.getFloat("sensitivity_$addr", 3.5f)
            smoothingFactor = prefs.getFloat("smoothing_$addr", 0.3f)
            Log.d(TAG, "加载设备 $addr 设置: sensitivity=$sensitivity, smoothing=$smoothingFactor")
        }
    }
    
    /**
     * 保存设备特定设置
     */
    fun saveDeviceSettings(deviceAddress: String?) {
        deviceAddress?.let { addr ->
            prefs.edit().apply {
                putFloat("sensitivity_$addr", sensitivity)
                putFloat("smoothing_$addr", smoothingFactor)
                apply()
            }
            Log.d(TAG, "保存设备 $addr 设置: sensitivity=$sensitivity, smoothing=$smoothingFactor")
        }
    }

    /**
     * 初始化 HID 设备
     */
    fun initHidDevice(callback: (success: Boolean, error: String?) -> Unit) {
        Log.d(TAG, "=== 开始初始化 HID ===")
        Log.d(TAG, "Android 版本: ${Build.VERSION.SDK_INT} (需要 >= 28)")
        
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            Log.e(TAG, "Android 版本过低")
            callback(false, "需要 Android 9.0 (API 28) 或更高版本")
            return
        }

        // 检查蓝牙 LE 支持
        val hasBle = context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)
        Log.d(TAG, "支持蓝牙 LE: $hasBle")
        if (!hasBle) {
            callback(false, "设备不支持蓝牙低功耗")
            return
        }

        // HID Device 支持检查（暂时跳过，让注册时自然失败）
        Log.d(TAG, "检查 HID Device 支持（将在注册时确认）")

        if (bluetoothAdapter == null) {
            Log.e(TAG, "蓝牙适配器为空")
            callback(false, "蓝牙不可用")
            return
        }

        Log.d(TAG, "蓝牙适配器: ${bluetoothAdapter.name}, 地址: ${bluetoothAdapter.address}")
        Log.d(TAG, "蓝牙是否开启: ${bluetoothAdapter.isEnabled}")

        if (!hasBluetoothPermissions()) {
            Log.e(TAG, "缺少蓝牙权限")
            callback(false, "缺少蓝牙权限")
            return
        }

        if (!bluetoothAdapter.isEnabled) {
            Log.e(TAG, "蓝牙未开启")
            callback(false, "请开启蓝牙")
            return
        }

        // 注册 HID 设备
        try {
            Log.d(TAG, "正在获取 HID Device Profile...")
            
            val serviceListener = object : BluetoothHidDevice.Callback() {
                override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
                    Log.d(TAG, "HID App 状态变化: registered=$registered, pluggedDevice=$pluggedDevice")
                    handler.post {
                        if (registered) {
                            Log.i(TAG, "HID 设备注册成功！")
                            // 注册成功后设置可发现
                            setDiscoverable(300)
                            callback(true, null)
                        } else {
                            // 只有在 App 正常运行时才回调失败（避免退出时弹窗）
                            if (hidDevice != null) {
                                Log.e(TAG, "HID 设备注册失败")
                                callback(false, "HID 设备注册失败")
                            } else {
                                Log.d(TAG, "HID 设备注销（正常退出）")
                            }
                        }
                    }
                }

                override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
                    val stateStr = when (state) {
                        BluetoothProfile.STATE_CONNECTED -> "CONNECTED"
                        BluetoothProfile.STATE_CONNECTING -> "CONNECTING"
                        BluetoothProfile.STATE_DISCONNECTED -> "DISCONNECTED"
                        BluetoothProfile.STATE_DISCONNECTING -> "DISCONNECTING"
                        else -> "UNKNOWN($state)"
                    }
                    Log.d(TAG, "连接状态变化: ${device.name} -> $stateStr")
                    
                    handler.post {
                        when (state) {
                            BluetoothProfile.STATE_CONNECTED -> {
                                // 取消超时计时
                                connectTimeoutRunnable?.let { handler.removeCallbacks(it) }
                                connectTimeoutRunnable = null
                                
                                Log.i(TAG, "已连接到: ${device.name}")
                                connectedDevice = device
                                // 加载设备设置
                                loadDeviceSettings(device.address)
                                listener?.onConnectionStateChanged(true)
                                startSensors()
                            }
                            BluetoothProfile.STATE_CONNECTING -> {
                                // 开始超时计时
                                startConnectTimeout()
                            }
                            BluetoothProfile.STATE_DISCONNECTED -> {
                                // 取消超时计时
                                connectTimeoutRunnable?.let { handler.removeCallbacks(it) }
                                connectTimeoutRunnable = null
                                
                                // 保存当前设备设置
                                saveDeviceSettings(connectedDevice?.address)
                                
                                Log.i(TAG, "已断开连接")
                                connectedDevice = null
                                listener?.onConnectionStateChanged(false)
                                stopSensors()
                            }
                        }
                    }
                }

                override fun onGetReport(device: BluetoothDevice, type: Byte, id: Byte, bufferSize: Int) {
                    Log.d(TAG, "onGetReport: type=$type, id=$id, bufferSize=$bufferSize")
                }

                override fun onSetReport(device: BluetoothDevice, type: Byte, id: Byte, data: ByteArray?) {
                    Log.d(TAG, "onSetReport: type=$type, id=$id")
                }

                override fun onSetProtocol(device: BluetoothDevice, protocol: Byte) {
                    Log.d(TAG, "onSetProtocol: protocol=$protocol")
                }

                // 注意：onIntrData 在某些 API 版本中不存在，暂时注释
                // override fun onIntrData(device: BluetoothDevice, reportId: Byte, data: ByteArray) {
                //     Log.d(TAG, "onIntrData: reportId=$reportId, data=${data.contentToString()}")
                // }

                override fun onVirtualCableUnplug(device: BluetoothDevice) {
                    Log.d(TAG, "onVirtualCableUnplug: ${device.name}")
                }
            }

            val profileListener = object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                    Log.d(TAG, "Service connected: profile=$profile")
                    if (profile == BluetoothProfile.HID_DEVICE) {
                        hidDevice = proxy as BluetoothHidDevice
                        Log.i(TAG, "HID Device Profile 已获取")
                        
                        // 注册 HID 应用
                        val appParameters = BluetoothHidDeviceAppSdpSettings(
                            "Gyro Mouse",
                            "Gyro Mouse HID",
                            "OpenClaw",
                            BluetoothHidDevice.SUBCLASS1_MOUSE,
                            MOUSE_REPORT_DESCRIPTOR
                        )
                        
                        // QoS 设置 - 对 Win10 很重要
                        val qosOut = BluetoothHidDeviceAppQosSettings(
                            BluetoothHidDeviceAppQosSettings.SERVICE_BEST_EFFORT,
                            0, 0, 0, 0, 0
                        )
                        
                        Log.d(TAG, "正在注册 HID App...")
                        val success = hidDevice?.registerApp(
                            appParameters, 
                            null,  // qosIn - 输入 QoS
                            qosOut,  // qosOut - 输出 QoS  
                            Runnable::run, 
                            serviceListener
                        )
                        Log.d(TAG, "registerApp 返回: $success")
                    }
                }

                override fun onServiceDisconnected(profile: Int) {
                    Log.d(TAG, "Service disconnected: profile=$profile")
                    if (profile == BluetoothProfile.HID_DEVICE) {
                        hidDevice = null
                    }
                }
            }

            val result = bluetoothAdapter.getProfileProxy(context, profileListener, BluetoothProfile.HID_DEVICE)
            Log.d(TAG, "getProfileProxy 结果: $result")
            
        } catch (e: Exception) {
            Log.e(TAG, "初始化失败: ${e.message}", e)
            callback(false, "初始化失败: ${e.message}")
        }
    }
    
    private fun startConnectTimeout() {
        connectTimeoutRunnable?.let { handler.removeCallbacks(it) }
        connectTimeoutRunnable = Runnable {
            Log.w(TAG, "连接超时（${CONNECT_TIMEOUT_MS}ms）")
            listener?.onConnectionTimeout()
        }
        handler.postDelayed(connectTimeoutRunnable!!, CONNECT_TIMEOUT_MS)
    }

    /**
     * 连接指定设备
     */
    fun connectToDevice(device: BluetoothDevice): Boolean {
        Log.d(TAG, "=== 尝试连接设备 ===")
        Log.d(TAG, "设备名称: ${device.name}")
        Log.d(TAG, "设备地址: ${device.address}")
        Log.d(TAG, "HID 设备对象: $hidDevice")
        
        if (hidDevice == null) {
            Log.e(TAG, "HID 设备未初始化，无法连接")
            listener?.onError("请先初始化 HID 设备")
            return false
        }
        
        if (!hasBluetoothPermissions()) {
            Log.e(TAG, "缺少蓝牙权限")
            listener?.onError("缺少蓝牙权限")
            return false
        }

        return try {
            Log.d(TAG, "调用 hidDevice.connect()...")
            val result = hidDevice?.connect(device)
            Log.d(TAG, "connect() 返回: $result")
            result ?: false
        } catch (e: SecurityException) {
            Log.e(TAG, "连接失败(权限): ${e.message}")
            listener?.onError("缺少连接权限: ${e.message}")
            false
        } catch (e: Exception) {
            Log.e(TAG, "连接失败: ${e.message}", e)
            listener?.onError("连接失败: ${e.message}")
            false
        }
    }

    /**
     * 断开连接
     */
    fun disconnect() {
        Log.d(TAG, "断开连接")
        // 保存设置
        saveDeviceSettings(connectedDevice?.address)
        
        connectedDevice?.let { device ->
            try {
                hidDevice?.disconnect(device)
            } catch (e: Exception) {
                Log.e(TAG, "断开连接失败: ${e.message}")
            }
        }
    }

    /**
     * 启动传感器
     */
    fun startSensors() {
        Log.d(TAG, "启动传感器")
        gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        
        gyroscope?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
            Log.d(TAG, "陀螺仪已注册")
        }
        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
            Log.d(TAG, "加速度计已注册")
        }
        
        lastTimestamp = SystemClock.elapsedRealtimeNanos()
    }

    /**
     * 停止传感器
     */
    fun stopSensors() {
        Log.d(TAG, "停止传感器")
        sensorManager.unregisterListener(this)
        velocityX = 0f
        velocityY = 0f
        positionX = 0f
        positionY = 0f
    }

    /**
     * 发送鼠标移动
     */
    fun sendMouseMove(deltaX: Int, deltaY: Int, wheel: Int = 0) {
        val device = connectedDevice ?: return
        val hid = hidDevice ?: return

        // HID 鼠标报告格式: [buttons, x, y, wheel]
        val report = ByteArray(4)
        report[0] = ((if (leftButton) 1 else 0) or 
                     (if (rightButton) 2 else 0) or 
                     (if (middleButton) 4 else 0)).toByte()
        report[1] = deltaX.toByte()
        report[2] = deltaY.toByte()
        report[3] = wheel.toByte()

        try {
            hid.sendReport(device, HID_REPORT_ID, report)
        } catch (e: SecurityException) {
            Log.e(TAG, "发送报告失败: ${e.message}")
            listener?.onError("发送报告失败")
        }
    }

    /**
     * 设置按钮状态
     */
    fun setButton(left: Boolean? = null, right: Boolean? = null, middle: Boolean? = null) {
        left?.let { leftButton = it }
        right?.let { rightButton = it }
        middle?.let { middleButton = it }
        
        // 立即发送按钮状态更新
        sendMouseMove(0, 0, 0)
    }

    /**
     * 传感器数据处理
     */
    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_GYROSCOPE -> {
                val currentTime = event.timestamp
                val dt = (currentTime - lastTimestamp) / 1_000_000_000f  // 转换为秒
                lastTimestamp = currentTime

                if (dt > 0 && dt < 0.1f) {  // 防止异常值
                    // 陀螺仪数据 (rad/s) 转换为角速度
                    val gyroX = event.values[0]  // 绕 X 轴 (俯仰) -> 控制上下
                    val gyroZ = event.values[2]  // 绕 Z 轴 (偏航/水平旋转) -> 控制左右
                    
                    // 新映射：
                    // - X 轴 (俯仰) 控制 上下移动 (Y)
                    // - Z 轴 (水平旋转) 控制 左右移动 (X)
                    val targetVelX = -gyroZ * sensitivity * 100  // Z轴控制左右，反转方向
                    val targetVelY = -gyroX * sensitivity * 100  // X轴控制上下
                    
                    // 简单平滑
                    velocityX += (targetVelX - velocityX) * smoothingFactor
                    velocityY += (targetVelY - velocityY) * smoothingFactor
                    
                    // 计算位移
                    val dx = (velocityX * dt).toInt().coerceIn(-127, 127)
                    val dy = (velocityY * dt).toInt().coerceIn(-127, 127)
                    
                    if (dx != 0 || dy != 0) {
                        sendMouseMove(dx, dy)
                        listener?.onSensorData(dx.toFloat(), dy.toFloat())
                    }
                }
            }
            Sensor.TYPE_ACCELEROMETER -> {
                // 可以在这里添加基于加速度计的功能
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun hasBluetoothPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, 
                Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context,
                Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context,
                Manifest.permission.BLUETOOTH) == PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * 获取已配对设备列表
     */
    fun getPairedDevices(): List<BluetoothDevice> {
        return if (hasBluetoothPermissions()) {
            bluetoothAdapter?.bondedDevices?.toList() ?: emptyList()
        } else {
            emptyList()
        }
    }

    /**
     * 设置蓝牙可发现性 - HID 设备需要保持可被发现
     */
    fun setDiscoverable(durationSeconds: Int = 300) {
        try {
            Log.d(TAG, "设置蓝牙可发现: $durationSeconds 秒")
            // 使用反射调用 setScanMode，需要 BLUETOOTH_ADVERTISE 权限
            val method = bluetoothAdapter?.javaClass?.getMethod("setScanMode", Int::class.java, Int::class.java)
            method?.invoke(bluetoothAdapter, BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE, durationSeconds)
        } catch (e: Exception) {
            Log.w(TAG, "设置可发现失败: ${e.message}")
        }
    }

    /**
     * 获取当前连接的 HID 设备
     */
    fun getConnectedDevice(): BluetoothDevice? {
        return connectedDevice
    }

    fun cleanup() {
        Log.d(TAG, "清理资源")
        // 保存设置
        saveDeviceSettings(connectedDevice?.address)
        // 取消超时
        connectTimeoutRunnable?.let { handler.removeCallbacks(it) }
        stopSensors()
        disconnect()
        hidDevice?.let { device ->
            bluetoothAdapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, device)
        }
        hidDevice = null
    }
}
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
 * 
 * 特性：
 * 1. 传感器融合 - 互补滤波器融合陀螺仪和加速度计
 * 2. 抖动优化 - 死区、自适应平滑、速度衰减
 * 3. 滚轮控制 - 基于倾斜角度
 */
class GyroMouseManager(private val context: Context) : SensorEventListener {

    companion object {
        const val TAG = "GyroMouse"
        const val HID_REPORT_ID = 1
        const val CONNECT_TIMEOUT_MS = 8000L
        
        // 互补滤波器系数 (0-1)，越大越信任加速度计
        const val COMPLEMENTARY_ALPHA = 0.02f
        
        // 鼠标报告描述符
        val MOUSE_REPORT_DESCRIPTOR = byteArrayOf(
            0x05.toByte(), 0x01.toByte(),
            0x09.toByte(), 0x02.toByte(),
            0xA1.toByte(), 0x01.toByte(),
            0x09.toByte(), 0x01.toByte(),
            0xA1.toByte(), 0x00.toByte(),
            0x05.toByte(), 0x09.toByte(),
            0x19.toByte(), 0x01.toByte(),
            0x29.toByte(), 0x03.toByte(),
            0x15.toByte(), 0x00.toByte(),
            0x25.toByte(), 0x01.toByte(),
            0x95.toByte(), 0x03.toByte(),
            0x75.toByte(), 0x01.toByte(),
            0x81.toByte(), 0x02.toByte(),
            0x95.toByte(), 0x01.toByte(),
            0x75.toByte(), 0x05.toByte(),
            0x81.toByte(), 0x03.toByte(),
            0x05.toByte(), 0x01.toByte(),
            0x09.toByte(), 0x30.toByte(),
            0x09.toByte(), 0x31.toByte(),
            0x09.toByte(), 0x38.toByte(),
            0x15.toByte(), 0x81.toByte(),
            0x25.toByte(), 0x7F.toByte(),
            0x75.toByte(), 0x08.toByte(),
            0x95.toByte(), 0x03.toByte(),
            0x81.toByte(), 0x06.toByte(),
            0xC0.toByte(),
            0xC0.toByte()
        )
        
        // 默认参数
        const val DEFAULT_DEAD_ZONE = 0.05f
        const val DEFAULT_VELOCITY_DECAY = 0.85f
        const val DEFAULT_JITTER_THRESHOLD = 0.3f
        const val DEFAULT_USE_SENSOR_FUSION = true
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
    
    // ===== 传感器融合姿态角 =====
    private var pitch = 0f    // 俯仰角 (绕X轴)
    private var roll = 0f     // 横滚角 (绕Y轴)
    private var yaw = 0f      // 偏航角 (绕Z轴)
    private var lastTimestamp = 0L
    
    // 原始传感器数据缓存
    private var accelData = FloatArray(3)
    private var gyroData = FloatArray(3)
    private var hasAccelData = false
    private var hasGyroData = false
    
    // 鼠标移动速度
    private var velocityX = 0f
    private var velocityY = 0f
    private val velocityHistoryX = ArrayDeque<Float>(5)
    private val velocityHistoryY = ArrayDeque<Float>(5)
    
    // 上一次陀螺仪值（用于抖动检测）
    private var lastGyroX = 0f
    private var lastGyroZ = 0f
    private var stationaryFrames = 0
    
    // ===== 设置参数 =====
    var sensitivity = 3.5f
    var smoothingFactor = 0.3f
    var deadZone = DEFAULT_DEAD_ZONE
    var velocityDecay = DEFAULT_VELOCITY_DECAY
    var jitterThreshold = DEFAULT_JITTER_THRESHOLD
    var useAdaptiveSmoothing = true
    var useSensorFusion = DEFAULT_USE_SENSOR_FUSION  // 是否使用传感器融合
    var fusionAlpha = COMPLEMENTARY_ALPHA              // 融合系数
    
    // 滚轮设置
    var wheelEnabled = true
    var wheelSensitivity = 2.0f
    var wheelThreshold = 0.3f
    
    private var wheelRunnable: Runnable? = null
    private var currentWheelDirection = 0
    private var currentWheelSpeed = 1
    private val baseWheelIntervalMs = 100L
    private var belowThresholdCount = 0
    
    // 按钮状态
    private var leftButton = false
    private var rightButton = false
    private var middleButton = false
    
    private val stationaryThreshold = 5

    fun setListener(listener: ConnectionListener) {
        this.listener = listener
    }
    
    fun loadDeviceSettings(deviceAddress: String?) {
        deviceAddress?.let { addr ->
            sensitivity = prefs.getFloat("sensitivity_$addr", 3.5f)
            smoothingFactor = prefs.getFloat("smoothing_$addr", 0.3f)
            wheelEnabled = prefs.getBoolean("wheel_enabled_$addr", true)
            wheelSensitivity = prefs.getFloat("wheel_sensitivity_$addr", 2.0f)
            wheelThreshold = prefs.getFloat("wheel_threshold_$addr", 0.3f)
            deadZone = prefs.getFloat("dead_zone_$addr", DEFAULT_DEAD_ZONE)
            velocityDecay = prefs.getFloat("velocity_decay_$addr", DEFAULT_VELOCITY_DECAY)
            jitterThreshold = prefs.getFloat("jitter_threshold_$addr", DEFAULT_JITTER_THRESHOLD)
            useAdaptiveSmoothing = prefs.getBoolean("adaptive_smoothing_$addr", true)
            useSensorFusion = prefs.getBoolean("sensor_fusion_$addr", DEFAULT_USE_SENSOR_FUSION)
            fusionAlpha = prefs.getFloat("fusion_alpha_$addr", COMPLEMENTARY_ALPHA)
            Log.d(TAG, "加载设备 $addr 设置: fusion=$useSensorFusion")
        }
    }
    
    fun saveDeviceSettings(deviceAddress: String?) {
        deviceAddress?.let { addr ->
            prefs.edit().apply {
                putFloat("sensitivity_$addr", sensitivity)
                putFloat("smoothing_$addr", smoothingFactor)
                putBoolean("wheel_enabled_$addr", wheelEnabled)
                putFloat("wheel_sensitivity_$addr", wheelSensitivity)
                putFloat("wheel_threshold_$addr", wheelThreshold)
                putFloat("dead_zone_$addr", deadZone)
                putFloat("velocity_decay_$addr", velocityDecay)
                putFloat("jitter_threshold_$addr", jitterThreshold)
                putBoolean("adaptive_smoothing_$addr", useAdaptiveSmoothing)
                putBoolean("sensor_fusion_$addr", useSensorFusion)
                putFloat("fusion_alpha_$addr", fusionAlpha)
                apply()
            }
        }
    }

    fun initHidDevice(callback: (success: Boolean, error: String?) -> Unit) {
        Log.d(TAG, "=== 开始初始化 HID ===")
        
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            callback(false, "需要 Android 9.0 (API 28) 或更高版本")
            return
        }

        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            callback(false, "设备不支持蓝牙低功耗")
            return
        }

        if (bluetoothAdapter == null) {
            callback(false, "蓝牙不可用")
            return
        }

        if (!hasBluetoothPermissions()) {
            callback(false, "缺少蓝牙权限")
            return
        }

        if (!bluetoothAdapter.isEnabled) {
            callback(false, "请开启蓝牙")
            return
        }

        try {
            val serviceListener = object : BluetoothHidDevice.Callback() {
                override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
                    handler.post {
                        if (registered) {
                            Log.i(TAG, "HID 设备注册成功！")
                            setDiscoverable(300)
                            callback(true, null)
                        } else {
                            if (hidDevice != null) {
                                callback(false, "HID 设备注册失败")
                            }
                        }
                    }
                }

                override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
                    handler.post {
                        when (state) {
                            BluetoothProfile.STATE_CONNECTED -> {
                                connectTimeoutRunnable?.let { handler.removeCallbacks(it) }
                                connectTimeoutRunnable = null
                                Log.i(TAG, "已连接到: ${device.name}")
                                connectedDevice = device
                                loadDeviceSettings(device.address)
                                listener?.onConnectionStateChanged(true)
                                startSensors()
                            }
                            BluetoothProfile.STATE_CONNECTING -> {
                                startConnectTimeout()
                            }
                            BluetoothProfile.STATE_DISCONNECTED -> {
                                connectTimeoutRunnable?.let { handler.removeCallbacks(it) }
                                connectTimeoutRunnable = null
                                saveDeviceSettings(connectedDevice?.address)
                                Log.i(TAG, "已断开连接")
                                connectedDevice = null
                                listener?.onConnectionStateChanged(false)
                                stopSensors()
                            }
                        }
                    }
                }

                override fun onGetReport(device: BluetoothDevice, type: Byte, id: Byte, bufferSize: Int) {}
                override fun onSetReport(device: BluetoothDevice, type: Byte, id: Byte, data: ByteArray?) {}
                override fun onSetProtocol(device: BluetoothDevice, protocol: Byte) {}
                override fun onVirtualCableUnplug(device: BluetoothDevice) {}
            }

            val profileListener = object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                    if (profile == BluetoothProfile.HID_DEVICE) {
                        hidDevice = proxy as BluetoothHidDevice
                        val appParameters = BluetoothHidDeviceAppSdpSettings(
                            "Gyro Mouse",
                            "Gyro Mouse HID",
                            "OpenClaw",
                            BluetoothHidDevice.SUBCLASS1_MOUSE,
                            MOUSE_REPORT_DESCRIPTOR
                        )
                        val qosOut = BluetoothHidDeviceAppQosSettings(
                            BluetoothHidDeviceAppQosSettings.SERVICE_BEST_EFFORT,
                            0, 0, 0, 0, 0
                        )
                        hidDevice?.registerApp(appParameters, null, qosOut, Runnable::run, serviceListener)
                    }
                }

                override fun onServiceDisconnected(profile: Int) {
                    if (profile == BluetoothProfile.HID_DEVICE) {
                        hidDevice = null
                    }
                }
            }

            bluetoothAdapter.getProfileProxy(context, profileListener, BluetoothProfile.HID_DEVICE)
            
        } catch (e: Exception) {
            Log.e(TAG, "初始化失败: ${e.message}", e)
            callback(false, "初始化失败: ${e.message}")
        }
    }
    
    private fun startConnectTimeout() {
        connectTimeoutRunnable?.let { handler.removeCallbacks(it) }
        connectTimeoutRunnable = Runnable {
            Log.w(TAG, "连接超时")
            listener?.onConnectionTimeout()
        }
        handler.postDelayed(connectTimeoutRunnable!!, CONNECT_TIMEOUT_MS)
    }

    fun connectToDevice(device: BluetoothDevice): Boolean {
        if (hidDevice == null) {
            listener?.onError("请先初始化 HID 设备")
            return false
        }
        
        if (!hasBluetoothPermissions()) {
            listener?.onError("缺少蓝牙权限")
            return false
        }

        return try {
            hidDevice?.connect(device) ?: false
        } catch (e: SecurityException) {
            listener?.onError("缺少连接权限: ${e.message}")
            false
        } catch (e: Exception) {
            listener?.onError("连接失败: ${e.message}")
            false
        }
    }

    fun disconnect() {
        saveDeviceSettings(connectedDevice?.address)
        connectedDevice?.let { device ->
            try {
                hidDevice?.disconnect(device)
            } catch (e: Exception) {
                Log.e(TAG, "断开连接失败: ${e.message}")
            }
        }
    }

    fun startSensors() {
        gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        
        gyroscope?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        
        lastTimestamp = SystemClock.elapsedRealtimeNanos()
        resetMotionState()
    }

    fun stopSensors() {
        sensorManager.unregisterListener(this)
        resetMotionState()
    }
    
    private fun resetMotionState() {
        pitch = 0f
        roll = 0f
        yaw = 0f
        velocityX = 0f
        velocityY = 0f
        velocityHistoryX.clear()
        velocityHistoryY.clear()
        lastGyroX = 0f
        lastGyroZ = 0f
        stationaryFrames = 0
        hasAccelData = false
        hasGyroData = false
    }

    fun sendMouseMove(deltaX: Int, deltaY: Int, wheel: Int = 0) {
        val device = connectedDevice ?: return
        val hid = hidDevice ?: return

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
        }
    }

    fun setButton(left: Boolean? = null, right: Boolean? = null, middle: Boolean? = null) {
        left?.let { leftButton = it }
        right?.let { rightButton = it }
        middle?.let { middleButton = it }
        sendMouseMove(0, 0, 0)
    }

    /**
     * 传感器数据回调
     */
    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_GYROSCOPE -> {
                gyroData[0] = event.values[0]
                gyroData[1] = event.values[1]
                gyroData[2] = event.values[2]
                hasGyroData = true
            }
            Sensor.TYPE_ACCELEROMETER -> {
                accelData[0] = event.values[0]
                accelData[1] = event.values[1]
                accelData[2] = event.values[2]
                hasAccelData = true
                
                // 处理滚轮（基于加速度计）
                processWheelControl()
            }
        }
        
        // 当两种数据都有时，执行融合
        if (hasGyroData && hasAccelData) {
            processSensorFusion()
        }
    }
    
    /**
     * 互补滤波器传感器融合
     * 结合陀螺仪（快速响应）和加速度计（无漂移）的优势
     */
    private fun processSensorFusion() {
        val currentTime = SystemClock.elapsedRealtimeNanos()
        val dt = (currentTime - lastTimestamp) / 1_000_000_000f
        lastTimestamp = currentTime
        
        if (dt <= 0 || dt > 0.1f) return
        
        val gyroX = gyroData[0]  // 绕X轴角速度
        val gyroY = gyroData[1]  // 绕Y轴角速度
        val gyroZ = gyroData[2]  // 绕Z轴角速度
        
        val ax = accelData[0]
        val ay = accelData[1]
        val az = accelData[2]
        
        if (useSensorFusion) {
            // ===== 互补滤波器融合 =====
            // 1. 从加速度计计算姿态角（仅当加速度接近重力时可靠）
            val accelPitch = atan2(-ax, sqrt(ay * ay + az * az))
            val accelRoll = atan2(ay, az)
            
            // 2. 陀螺仪积分更新姿态
            // 将陀螺仪数据（rad/s）转换为角度变化
            val gyroPitchDelta = gyroX * dt
            val gyroRollDelta = gyroY * dt
            val gyroYawDelta = gyroZ * dt
            
            // 3. 互补滤波器融合
            // pitch/roll: 高频用陀螺仪，低频用加速度计
            pitch = (1 - fusionAlpha) * (pitch + gyroPitchDelta) + fusionAlpha * accelPitch
            roll = (1 - fusionAlpha) * (roll + gyroRollDelta) + fusionAlpha * accelRoll
            
            // yaw: 只有陀螺仪能测（加速度计无法检测水平旋转）
            yaw += gyroYawDelta
            
            // 4. 基于融合后的姿态角变化率计算鼠标速度
            // 使用姿态角的变化作为鼠标移动的基础
            val pitchVelocity = gyroX  // 俯仰角速度控制Y轴
            val yawVelocity = gyroZ     // 偏航角速度控制X轴
            
            // 死区处理
            val deadZonedPitch = applyDeadZone(pitchVelocity, deadZone)
            val deadZonedYaw = applyDeadZone(yawVelocity, deadZone)
            
            // 计算目标速度
            val targetVelX = -deadZonedYaw * sensitivity * 100
            val targetVelY = -deadZonedPitch * sensitivity * 100
            
            processMouseMovement(targetVelX, targetVelY, gyroX, gyroZ, dt)
            
        } else {
            // 传统模式：只用陀螺仪
            val rawGyroX = gyroX
            val rawGyroZ = gyroZ
            
            val gyroXDead = applyDeadZone(rawGyroX, deadZone)
            val gyroZDead = applyDeadZone(rawGyroZ, deadZone)
            
            val targetVelX = -gyroZDead * sensitivity * 100
            val targetVelY = -gyroXDead * sensitivity * 100
            
            processMouseMovement(targetVelX, targetVelY, rawGyroX, rawGyroZ, dt)
        }
    }
    
    /**
     * 处理鼠标移动（抖动优化）
     */
    private fun processMouseMovement(
        targetVelX: Float, 
        targetVelY: Float,
        rawGyroX: Float,
        rawGyroZ: Float,
        dt: Float
    ) {
        // 静止检测
        if (abs(targetVelX) < 0.01f && abs(targetVelY) < 0.01f) {
            stationaryFrames++
            if (stationaryFrames >= stationaryThreshold) {
                velocityX *= 0.5f
                velocityY *= 0.5f
                if (abs(velocityX) < 0.01f) velocityX = 0f
                if (abs(velocityY) < 0.01f) velocityY = 0f
                return
            }
        } else {
            stationaryFrames = 0
        }
        
        // 抖动检测
        val isJitterX = detectJitter(rawGyroX, lastGyroX, jitterThreshold)
        val isJitterZ = detectJitter(rawGyroZ, lastGyroZ, jitterThreshold)
        lastGyroX = rawGyroX
        lastGyroZ = rawGyroZ
        
        // 自适应平滑
        val adaptiveSmoothing = if (useAdaptiveSmoothing) {
            calculateAdaptiveSmoothing(targetVelX, targetVelY)
        } else {
            smoothingFactor
        }
        
        val finalSmoothing = when {
            isJitterX && isJitterZ -> adaptiveSmoothing * 1.5f
            isJitterX || isJitterZ -> adaptiveSmoothing * 1.2f
            else -> adaptiveSmoothing
        }.coerceIn(0.1f, 0.95f)
        
        // 应用平滑
        velocityX = lerp(velocityX, targetVelX, 1f - finalSmoothing)
        velocityY = lerp(velocityY, targetVelY, 1f - finalSmoothing)
        
        // 中值滤波
        velocityHistoryX.addLast(velocityX)
        velocityHistoryY.addLast(velocityY)
        if (velocityHistoryX.size > 5) velocityHistoryX.removeFirst()
        if (velocityHistoryY.size > 5) velocityHistoryY.removeFirst()
        
        val filteredVelX = medianFilter(velocityHistoryX)
        val filteredVelY = medianFilter(velocityHistoryY)
        
        // 速度衰减
        velocityX = filteredVelX * velocityDecay
        velocityY = filteredVelY * velocityDecay
        
        // 计算位移
        val dx = (velocityX * dt).toInt().coerceIn(-127, 127)
        val dy = (velocityY * dt).toInt().coerceIn(-127, 127)
        
        if (abs(dx) > 0 || abs(dy) > 0) {
            sendMouseMove(dx, dy, 0)
            listener?.onSensorData(dx.toFloat(), dy.toFloat())
        }
    }
    
    /**
     * 滚轮控制（基于加速度计）
     */
    private fun processWheelControl() {
        val ax = accelData[0]
        val ay = accelData[1]
        val az = accelData[2]
        
        val tiltAngle = atan2(ax, sqrt(ay * ay + az * az))
        
        if (wheelEnabled) {
            val absTilt = abs(tiltAngle)
            if (absTilt > wheelThreshold) {
                belowThresholdCount = 0
                val newDirection = if (tiltAngle > 0) 1 else -1
                val newSpeed = ((absTilt - wheelThreshold) * wheelSensitivity * 2).toInt().coerceIn(1, 3)
                
                if (wheelRunnable == null || newDirection != currentWheelDirection || newSpeed != currentWheelSpeed) {
                    startContinuousWheel(newDirection, newSpeed)
                }
            } else {
                belowThresholdCount++
                if (belowThresholdCount >= 3) {
                    stopContinuousWheel()
                    belowThresholdCount = 0
                }
            }
        } else {
            stopContinuousWheel()
        }
    }
    
    // ===== 工具函数 =====
    
    private fun applyDeadZone(value: Float, threshold: Float): Float {
        return when {
            abs(value) < threshold -> 0f
            value > 0 -> value - threshold
            else -> value + threshold
        }
    }
    
    private fun detectJitter(current: Float, last: Float, threshold: Float): Boolean {
        if (last == 0f) return false
        val signChanged = (current > 0 && last < 0) || (current < 0 && last > 0)
        val magnitudeLarge = abs(current) > threshold && abs(last) > threshold
        return signChanged && magnitudeLarge
    }
    
    private fun calculateAdaptiveSmoothing(velX: Float, velY: Float): Float {
        val speed = sqrt(velX * velX + velY * velY)
        return when {
            speed < 50f -> 0.7f
            speed < 150f -> 0.5f
            speed < 300f -> 0.3f
            else -> 0.15f
        }
    }
    
    private fun lerp(start: Float, end: Float, factor: Float): Float {
        return start + (end - start) * factor
    }
    
    private fun medianFilter(values: ArrayDeque<Float>): Float {
        if (values.isEmpty()) return 0f
        if (values.size < 3) return values.last()
        return values.sorted()[values.size / 2]
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

    fun getPairedDevices(): List<BluetoothDevice> {
        return if (hasBluetoothPermissions()) {
            bluetoothAdapter?.bondedDevices?.toList() ?: emptyList()
        } else {
            emptyList()
        }
    }

    fun setDiscoverable(durationSeconds: Int = 300) {
        try {
            val method = bluetoothAdapter?.javaClass?.getMethod("setScanMode", Int::class.java, Int::class.java)
            method?.invoke(bluetoothAdapter, BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE, durationSeconds)
        } catch (e: Exception) {
            Log.w(TAG, "设置可发现失败: ${e.message}")
        }
    }

    fun getConnectedDevice(): BluetoothDevice? {
        return connectedDevice
    }

    private fun startContinuousWheel(direction: Int, speed: Int) {
        stopContinuousWheel()
        currentWheelDirection = direction
        currentWheelSpeed = speed
        
        wheelRunnable = object : Runnable {
            override fun run() {
                sendMouseMove(0, 0, currentWheelDirection * currentWheelSpeed)
                handler.postDelayed(this, (baseWheelIntervalMs / currentWheelSpeed).coerceAtLeast(30L))
            }
        }
        handler.post(wheelRunnable!!)
    }
    
    private fun stopContinuousWheel() {
        wheelRunnable?.let { handler.removeCallbacks(it) }
        wheelRunnable = null
        currentWheelDirection = 0
        currentWheelSpeed = 1
    }

    fun cleanup() {
        saveDeviceSettings(connectedDevice?.address)
        connectTimeoutRunnable?.let { handler.removeCallbacks(it) }
        stopContinuousWheel()
        stopSensors()
        disconnect()
        hidDevice?.let { device ->
            bluetoothAdapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, device)
        }
        hidDevice = null
    }
}
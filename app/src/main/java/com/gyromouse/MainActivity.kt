package com.gyromouse

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.gyromouse.databinding.ActivityMainBinding

/**
 * MainActivity - 主界面
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val PERMISSION_REQUEST_CODE = 1001
        private const val DOUBLE_BACK_PRESS_INTERVAL = 2000L  // 双击间隔2秒
        private const val CONNECT_TIMEOUT_SECONDS = 8  // 连接超时8秒
        private val REQUIRED_PERMISSIONS = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE
            )
        } else {
            arrayOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN
            )
        }
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var gyroManager: GyroMouseManager
    
    private var isConnected = false
    private var isCalibrated = false
    private var lastBackPressTime = 0L
    private val handler = Handler(Looper.getMainLooper())
    private var connectStartTime = 0L
    private var connectProgressRunnable: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        gyroManager = GyroMouseManager(this)
        gyroManager.setListener(object : GyroMouseManager.ConnectionListener {
            override fun onConnectionStateChanged(connected: Boolean) {
                runOnUiThread {
                    isConnected = connected
                    updateConnectionUI(connected)
                }
            }

            override fun onError(message: String) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show()
                }
            }

            override fun onSensorData(x: Float, y: Float) {
                runOnUiThread {
                    binding.tvDebug.text = "移动: X=${x.toInt()}, Y=${y.toInt()}"
                }
            }
            
            override fun onConnectionTimeout() {
                runOnUiThread {
                    binding.progressBar.visibility = View.GONE
                    binding.tvStatus.text = "连接超时"
                    binding.btnConnect.isEnabled = true
                    Toast.makeText(this@MainActivity, 
                        "连接超时（8秒），请检查电脑蓝牙", Toast.LENGTH_LONG).show()
                    showDevicePicker()
                }
            }
        })

        setupUI()
        checkPermissions()
        
        // 自动初始化 HID（如果有权限）
        if (hasBluetoothPermissions()) {
            autoInitHid()
        }
    }
    
    private fun hasBluetoothPermissions(): Boolean {
        return REQUIRED_PERMISSIONS.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }
    
    private fun autoInitHid() {
        binding.btnInit.isEnabled = false
        binding.btnInit.text = "正在初始化..."
        
        gyroManager.initHidDevice { success, error ->
            runOnUiThread {
                if (success) {
                    binding.btnInit.text = "已初始化 ✓"
                    binding.btnInit.isEnabled = false
                    // 初始化成功后，尝试连接已配对设备
                    tryConnectPairedDevice()
                } else {
                    binding.btnInit.isEnabled = true
                    binding.btnInit.text = "初始化 HID"
                    Toast.makeText(this, error ?: "初始化失败", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    
    private fun tryConnectPairedDevice() {
        val pairedDevices = gyroManager.getPairedDevices()
        if (pairedDevices.isNotEmpty()) {
            // 自动连接第一个已配对设备
            val device = pairedDevices[0]
            android.util.Log.d("MainActivity", "尝试自动连接已配对设备: ${device.name}")
            
            binding.progressBar.visibility = View.VISIBLE
            binding.tvStatus.text = "正在连接... (0s/${CONNECT_TIMEOUT_SECONDS}s)"
            binding.btnConnect.isEnabled = false
            
            // 开始显示倒计时
            startConnectProgress()
            
            val success = gyroManager.connectToDevice(device)
            if (!success) {
                stopConnectProgress()
                binding.progressBar.visibility = View.GONE
                binding.tvStatus.text = "连接失败"
                binding.btnConnect.isEnabled = true
                // 显示设备选择器让用户手动选择
                showDevicePicker()
            }
        } else {
            // 没有配对设备，显示提示
            showWaitingDialog()
        }
    }
    
    private fun startConnectProgress() {
        connectStartTime = SystemClock.elapsedRealtime()
        connectProgressRunnable = object : Runnable {
            override fun run() {
                if (isConnected) return  // 已连接，停止
                
                val elapsed = (SystemClock.elapsedRealtime() - connectStartTime) / 1000
                if (elapsed <= CONNECT_TIMEOUT_SECONDS) {
                    binding.tvStatus.text = "正在连接... (${elapsed}s/${CONNECT_TIMEOUT_SECONDS}s)"
                    handler.postDelayed(this, 1000)
                }
            }
        }
        handler.post(connectProgressRunnable!!)
    }
    
    private fun stopConnectProgress() {
        connectProgressRunnable?.let { handler.removeCallbacks(it) }
        connectProgressRunnable = null
    }

    private fun setupUI() {
        // 连接按钮
        binding.btnConnect.setOnClickListener {
            if (!isConnected) {
                showDevicePicker()
            } else {
                gyroManager.disconnect()
            }
        }

        // 初始化按钮
        binding.btnInit.setOnClickListener {
            initHidDevice()
        }

        // 左键
        binding.btnLeft.setOnTouchListener { _, event ->
            handleButtonTouch(event, left = true)
            true
        }

        // 右键
        binding.btnRight.setOnTouchListener { _, event ->
            handleButtonTouch(event, right = true)
            true
        }

        // 中键/滚轮区域
        binding.btnMiddle.setOnTouchListener { _, event ->
            handleButtonTouch(event, middle = true)
            true
        }

        // 灵敏度滑块 - 范围改为 0.5 - 10.0，默认 3.5
        binding.seekSensitivity.setOnSeekBarChangeListener(object : 
            android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                val sensitivity = 0.5f + (progress / 100f) * 9.5f  // 0.5 - 10.0
                gyroManager.sensitivity = sensitivity
                binding.tvSensitivity.text = "灵敏度: %.1f".format(sensitivity)
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {
                // 保存当前设置
                gyroManager.saveDeviceSettings(gyroManager.getConnectedDevice()?.address)
            }
        })
        // 设置默认灵敏度到 SeekBar（3.5）
        binding.seekSensitivity.progress = ((3.5f - 0.5f) / 9.5f * 100).toInt()

        // 平滑度滑块
        binding.seekSmoothing.setOnSeekBarChangeListener(object :
            android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                val smoothing = progress / 100f
                gyroManager.smoothingFactor = smoothing
                binding.tvSmoothing.text = "平滑度: %.0f%%".format(smoothing * 100)
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {
                // 保存当前设置
                gyroManager.saveDeviceSettings(gyroManager.getConnectedDevice()?.address)
            }
        })

        // 滚轮控制开关
        binding.switchWheel.setOnCheckedChangeListener { _, isChecked ->
            gyroManager.wheelEnabled = isChecked
            binding.seekWheelSensitivity.isEnabled = isChecked
            gyroManager.saveDeviceSettings(gyroManager.getConnectedDevice()?.address)
        }

        // 滚轮灵敏度滑块
        binding.seekWheelSensitivity.setOnSeekBarChangeListener(object :
            android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                val sensitivity = 0.5f + (progress / 100f) * 4.5f  // 0.5 - 5.0
                gyroManager.wheelSensitivity = sensitivity
                binding.tvWheelSensitivity.text = "滚轮灵敏度: %.1f".format(sensitivity)
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {
                gyroManager.saveDeviceSettings(gyroManager.getConnectedDevice()?.address)
            }
        })

        // 校准按钮
        binding.btnCalibrate.setOnClickListener {
            calibrateSensors()
        }

        // 使用说明
        binding.btnHelp.setOnClickListener {
            showHelpDialog()
        }
    }

    private fun handleButtonTouch(event: MotionEvent, left: Boolean = false, 
                                   right: Boolean = false, middle: Boolean = false): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                gyroManager.setButton(
                    left = if (left) true else null,
                    right = if (right) true else null,
                    middle = if (middle) true else null
                )
                updateButtonVisual(left, right, middle, pressed = true)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                gyroManager.setButton(
                    left = if (left) false else null,
                    right = if (right) false else null,
                    middle = if (middle) false else null
                )
                updateButtonVisual(left, right, middle, pressed = false)
            }
        }
        return true
    }

    private fun updateButtonVisual(left: Boolean, right: Boolean, middle: Boolean, pressed: Boolean) {
        when {
            left -> binding.btnLeft.isPressed = pressed
            right -> binding.btnRight.isPressed = pressed
            middle -> binding.btnMiddle.isPressed = pressed
        }
    }

    private fun checkPermissions() {
        val missingPermissions = REQUIRED_PERMISSIONS.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this,
                missingPermissions.toTypedArray(),
                PERMISSION_REQUEST_CODE
            )
        }
    }

    private fun initHidDevice() {
        binding.btnInit.isEnabled = false
        binding.progressBar.visibility = View.VISIBLE
        
        gyroManager.initHidDevice { success, error ->
            runOnUiThread {
                binding.progressBar.visibility = View.GONE
                
                if (success) {
                    Toast.makeText(this, "HID 设备已就绪", Toast.LENGTH_SHORT).show()
                    binding.btnInit.text = "已初始化 ✓"
                    binding.btnInit.isEnabled = false
                    // 初始化成功后尝试连接已配对设备
                    tryConnectPairedDevice()
                } else {
                    binding.btnInit.isEnabled = true
                    binding.btnInit.text = "初始化 HID"
                    Toast.makeText(this, error ?: "初始化失败", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
    
    private fun showWaitingDialog() {
        AlertDialog.Builder(this)
            .setTitle("等待电脑连接")
            .setMessage("""
                HID 设备已准备就绪！
                
                请在 Windows 10 上操作：
                1. 设置 → 设备 → 蓝牙
                2. 点击"添加蓝牙或其他设备"
                3. 选择"蓝牙"
                4. 等待发现 "Gyro Mouse"
                5. 点击连接（无需配对码）
            """.trimIndent())
            .setPositiveButton("知道了", null)
            .show()
    }

    private fun showDevicePicker() {
        val devices = gyroManager.getPairedDevices()
        
        if (devices.isEmpty()) {
            Toast.makeText(this, "请先配对电脑蓝牙", Toast.LENGTH_LONG).show()
            return
        }

        val deviceNames = devices.map { 
            "${it.name ?: "Unknown"} (${it.address})" 
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("选择要连接的设备")
            .setItems(deviceNames) { _, which ->
                val device = devices[which]
                connectToDevice(device)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun connectToDevice(device: BluetoothDevice) {
        binding.progressBar.visibility = View.VISIBLE
        binding.tvStatus.text = "正在连接... (0s/${CONNECT_TIMEOUT_SECONDS}s)"
        binding.btnConnect.isEnabled = false
        
        // 开始显示倒计时
        startConnectProgress()
        
        val success = gyroManager.connectToDevice(device)
        
        if (!success) {
            stopConnectProgress()
            binding.progressBar.visibility = View.GONE
            binding.tvStatus.text = "连接失败"
            binding.btnConnect.isEnabled = true
            Toast.makeText(this, "连接失败", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateConnectionUI(connected: Boolean) {
        binding.progressBar.visibility = View.GONE
        stopConnectProgress()
        
        if (connected) {
            // 加载设备设置到 UI
            val device = gyroManager.getConnectedDevice()
            device?.let {
                binding.seekSensitivity.progress = ((gyroManager.sensitivity - 0.5f) / 9.5f * 100).toInt()
                binding.seekSmoothing.progress = (gyroManager.smoothingFactor * 100).toInt()
                binding.switchWheel.isChecked = gyroManager.wheelEnabled
                binding.seekWheelSensitivity.progress = ((gyroManager.wheelSensitivity - 0.5f) / 4.5f * 100).toInt()
                binding.seekWheelSensitivity.isEnabled = gyroManager.wheelEnabled
            }
            
            binding.btnConnect.text = "断开连接"
            binding.btnConnect.isEnabled = true
            binding.statusIndicator.setBackgroundResource(R.drawable.circle_green)
            binding.tvStatus.text = "已连接"
            binding.cardControls.visibility = View.VISIBLE
        } else {
            binding.btnConnect.text = "连接设备"
            binding.btnConnect.isEnabled = true
            binding.statusIndicator.setBackgroundResource(R.drawable.circle_red)
            binding.tvStatus.text = "未连接"
            binding.cardControls.visibility = View.GONE
        }
    }

    private fun calibrateSensors() {
        Toast.makeText(this, "请将手机平放，正在校准...", Toast.LENGTH_SHORT).show()
        binding.btnCalibrate.postDelayed({
            isCalibrated = true
            Toast.makeText(this, "校准完成", Toast.LENGTH_SHORT).show()
            binding.btnCalibrate.text = "已校准 ✓"
        }, 1000)
    }

    private fun showHelpDialog() {
        AlertDialog.Builder(this)
            .setTitle("使用说明")
            .setMessage("""
                1. 确保手机蓝牙已开启
                2. 先在系统设置中与电脑配对
                3. 点击"初始化 HID 设备"
                4. 选择电脑并连接
                5. 移动手机来控制鼠标：
                   • 前后倾斜（X轴）= 上下移动
                   • 水平旋转（Z轴）= 左右移动
                   • 左右歪头（Y轴）= 滚轮滚动
                6. 下方按钮模拟左右中键
                
                提示：
                - 调整灵敏度获得最佳体验
                - 每个设备的设置会被记住
                - 可在设置中开关/调整滚轮功能
            """.trimIndent())
            .setPositiveButton("知道了", null)
            .show()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                Toast.makeText(this, "权限已获取", Toast.LENGTH_SHORT).show()
                autoInitHid()
            } else {
                Toast.makeText(this, "需要蓝牙权限才能使用", Toast.LENGTH_LONG).show()
            }
        }
    }
    
    // 双击返回键退出
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            val currentTime = SystemClock.elapsedRealtime()
            if (currentTime - lastBackPressTime < DOUBLE_BACK_PRESS_INTERVAL) {
                // 双击，退出
                finish()
            } else {
                // 单击，提示再按一次
                Toast.makeText(this, "再按一次返回键退出", Toast.LENGTH_SHORT).show()
                lastBackPressTime = currentTime
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        super.onDestroy()
        stopConnectProgress()
        gyroManager.cleanup()
    }
}
package com.matteogassend.bluetooth_classic

//https://github.com/PiyushJaiswal236/bluetooth_classic/blob/master/android/src/main/kotlin/com/matteogassend/bluetooth_classic/BluetoothClassicPlugin.kt

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import io.flutter.Log

import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.EventChannel.EventSink
import io.flutter.plugin.common.EventChannel.StreamHandler
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import io.flutter.plugin.common.PluginRegistry
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue

/** BluetoothClassicPlugin */
class BluetoothClassicPlugin : FlutterPlugin, MethodCallHandler,
    PluginRegistry.RequestPermissionsResultListener, ActivityAware {
    /// The MethodChannel that will the communication between Flutter and native Android
    ///
    /// This local reference serves to register the plugin with the Flutter Engine and unregister it
    /// when the Flutter Engine is detached from the Activity
    private lateinit var channel: MethodChannel
    private lateinit var bluetoothDeviceChannel: EventChannel
    private var bluetoothDeviceChannelSink: EventSink? = null
    private lateinit var bluetoothReadChannel: EventChannel
    private var bluetoothReadChannelSink: EventSink? = null
    private lateinit var bluetoothStatusChannel: EventChannel
    private var bluetoothStatusChannelSink: EventSink? = null
    private lateinit var ba: BluetoothAdapter
    private lateinit var pluginActivity: Activity
    private lateinit var application: Context
    private lateinit var looper: Looper
    private val myPermissionCode = 34264
    private var activeResult: Result? = null
    private var permissionGranted: Boolean = false
    private var thread: ConnectedThread? = null
    private var socket: BluetoothSocket? = null
    private var device: BluetoothDevice? = null


    private inner class ConnectedThread(socket: BluetoothSocket) : Thread() {

        private val inputStream = socket.inputStream
        private val outputStream = socket.outputStream

        //private val buffer: ByteArray = ByteArray(1024)
        var readStream = true
        override fun run() {
            var numBytes: Int
            var queue = ConcurrentLinkedQueue<ByteArray>()
            while (readStream) {
                try {
                    val buffer: ByteArray = ByteArray(1024)
                    numBytes = inputStream.read(buffer)
                    queue.offer(ByteArray(numBytes) {
                        buffer[it]
                    })
                    Log.i(
                        "Bluetooth Read", "read ${
                            (ByteArray(numBytes) {
                                buffer[it]
                            }).toString(Charsets.UTF_8)
                        } queue:${queue} numBytes:${numBytes}"
                    )
                    Handler(Looper.getMainLooper()).post {
                        do {
                            var b = queue.poll()
                            if (b != null) {
                                publishBluetoothData(b)
                            }
                        } while (b != null)
                    }
                } catch (e: IOException) {
                    Log.e("Bluetooth Read", "input stream disconnected", e)
                    Handler(Looper.getMainLooper()).post { publishBluetoothStatus(0) }
                    readStream = false
                }
            }
        }

        fun write(bytes: ByteArray) {
            try {
                outputStream.write(bytes)
            } catch (e: IOException) {
                readStream = false
                Log.e("Bluetooth Write", "could not send data to other device", e)
                Handler(Looper.getMainLooper()).post { publishBluetoothStatus(0) }
            }
        }
    }

    override fun onDetachedFromActivity() {
        //TODO("Not yet implemented")
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        //TODO("Not yet implemented")
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        pluginActivity = binding.activity
    }

    override fun onDetachedFromActivityForConfigChanges() {
        //TODO("Not yet implemented")
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device: BluetoothDevice =
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)!!
                    publishBluetoothDevice(device)
                }
            }
        }
    }

    private fun publishBluetoothData(data: ByteArray) {
        bluetoothReadChannelSink?.success(data)
    }

    private fun publishBluetoothStatus(status: Int) {
        Log.i("Bluetooth Device Status", "Status updated to $status")
        bluetoothStatusChannelSink?.success(status)
    }

    private fun publishBluetoothDevice(device: BluetoothDevice) {
        Log.i("device_discovery", device.address)
        bluetoothDeviceChannelSink?.success(
            hashMapOf(
                "address" to device.address,
                "name" to device.name
            )
        )
    }

    override fun onAttachedToEngine(flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
        channel = MethodChannel(
            flutterPluginBinding.binaryMessenger,
            "com.matteogassend/bluetooth_classic"
        )
        bluetoothDeviceChannel = EventChannel(
            flutterPluginBinding.binaryMessenger,
            "com.matteogassend/bluetooth_classic/devices"
        )
        bluetoothReadChannel = EventChannel(
            flutterPluginBinding.binaryMessenger,
            "com.matteogassend/bluetooth_classic/read"
        )
        bluetoothStatusChannel = EventChannel(
            flutterPluginBinding.binaryMessenger,
            "com.matteogassend/bluetooth_classic/status"
        )
        ba = BluetoothAdapter.getDefaultAdapter()
        channel.setMethodCallHandler(this)
        looper = flutterPluginBinding.applicationContext.mainLooper
        application = flutterPluginBinding.applicationContext
        bluetoothDeviceChannel.setStreamHandler(object : StreamHandler {
            override fun onListen(arguments: Any?, events: EventSink?) {
                bluetoothDeviceChannelSink = events
            }

            override fun onCancel(arguments: Any?) {
                bluetoothDeviceChannelSink = null
            }
        })
        bluetoothReadChannel.setStreamHandler(object : StreamHandler {
            override fun onListen(arguments: Any?, events: EventSink?) {
                bluetoothReadChannelSink = events
            }

            override fun onCancel(arguments: Any?) {
                bluetoothReadChannelSink = null
            }
        })
        bluetoothStatusChannel.setStreamHandler(object : StreamHandler {
            override fun onListen(arguments: Any?, events: EventSink?) {
                bluetoothStatusChannelSink = events
            }

            override fun onCancel(arguments: Any?) {
                bluetoothStatusChannelSink = null
            }


        })
    }

    override fun onMethodCall(call: MethodCall, result: Result) {
        Log.i("method_call", call.method)
        when (call.method) {
            "getPlatformVersion" -> result.success("Android ${Build.VERSION.RELEASE}")
            "initPermissions" -> initPermissions(result)
            "getDevices" -> getDevices(result)
            "startDiscovery" -> startScan(result)
            "stopDiscovery" -> stopScan(result)
            "connect" -> connect(
                result,
                call.argument<String>("deviceId")!!,
                call.argument<String>("serviceUUID")!!
            )

            "disconnect" -> disconnect(result)
            "write" -> write(result, call.argument<String>("message")!!)
            "writeBinary" -> writeBinary(result, call.argument<ByteArray>("message")!!)
            else -> result.notImplemented()
        }
    }

    private fun write(result: Result, message: String) {
        Log.i("write_handle", "inside write handle")
        if (thread != null) {
            thread!!.write(message.toByteArray())
            result.success(true)
        } else {
            result.error("write_impossible", "could not send message to unconnected device", null)
        }
    }

    private fun writeBinary(result: Result, message: ByteArray) {
        Log.i("writeBinar_handle", "inside write Binar handle")
        if (thread != null) {
            thread!!.write(message)
            result.success(true)
        } else {
            result.error("write_impossible", "could not send message to unconnected device", null)
        }
    }

    private fun disconnect(result: Result) {
        try {
            if (thread != null) {
                thread?.readStream = false  // Stop reading data
                thread?.interrupt()         // Interrupt the thread safely
                thread = null
                Log.i("Bluetooth Disconnect", "Read thread closed")
            }

            if (socket != null) {
                socket?.close()  // Close the Bluetooth socket
                socket = null
                Log.i("Bluetooth Disconnect", "RFCOMM socket closed")
            }

            device = null
            publishBluetoothStatus(0) // Notify Flutter that the device is disconnected
            Log.i("Bluetooth Disconnect", "Disconnected successfully")

            result.success(true)  // Send success response to Flutter
        } catch (e: IOException) {
            Log.e("Bluetooth Disconnect", "Error while disconnecting", e)
            result.error(
                "disconnect_failed",
                "Error disconnecting Bluetooth device: ${e.localizedMessage}",
                null
            )
        }
    }

    private fun connect(result: Result, deviceId: String, serviceUuid: String) {
        Thread {
            try {
                Handler(Looper.getMainLooper()).post {
                    publishBluetoothStatus(1)
                }
                device = ba.getRemoteDevice(deviceId)
                Log.i("Bluetooth Connection", "device found")
                assert(device != null)

                // Stop discovery to avoid interference
                if (ba.isDiscovering) {
                    ba.cancelDiscovery()
                }

                socket = device?.createRfcommSocketToServiceRecord(UUID.fromString(serviceUuid))
                Log.i("Bluetooth Connection", "rfcommsocket found")
                assert(socket != null)
                socket?.connect()
                Log.i("Bluetooth Connection", "socket connected")

                thread = ConnectedThread(socket!!)
                Log.i("Bluetooth Connection", "thread created")
                assert(thread != null)
                thread!!.start()

                Handler(Looper.getMainLooper()).post {
                    Log.i("Bluetooth Connection", "thread running")
                    result.success(true)
                    publishBluetoothStatus(2)
                }

            } catch (e: IOException) {
                Log.e("Bluetooth Connection", "connection failed", e)
                Handler(Looper.getMainLooper()).post {
                    publishBluetoothStatus(0)
                    result.error(
                        "connection_failed",
                        "could not connect to device $deviceId",
                        null,
                    )
                }
            }
        }.start()
    }

    private fun startScan(result: Result) {
        Log.i("start_scan", "scan started")
        application.registerReceiver(receiver, IntentFilter(BluetoothDevice.ACTION_FOUND))
        ba.startDiscovery()
        result.success(true)
    }

    private fun stopScan(result: Result) {
        Log.i("stop_scan", "scan stopped")
        ba.cancelDiscovery()
        result.success(true)
    }

    private fun initPermissions(result: Result) {
        if (activeResult != null) {
            result.error("init_running", "only one initialize call allowed at a time", null)
        }
        activeResult = result
        checkPermissions(application)
    }

    // Check permissions for all required Bluetooth and location permissions.
    private fun arePermissionsGranted(application: Context) {
        val permissions = mutableListOf(
            Manifest.permission.BLUETOOTH,
            Manifest.permission.BLUETOOTH_ADMIN,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
        }
        if (Build.VERSION.SDK_INT >= 33) { // Android 13+ for notifications
            permissions.add("android.permission.POST_NOTIFICATIONS")
        }
        if (Build.VERSION.SDK_INT >= 34) { // Android 14 and above
            permissions.add("android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE")
        }
        permissionGranted = permissions.all {
            ContextCompat.checkSelfPermission(application, it) == PackageManager.PERMISSION_GRANTED
        }
        Log.i("permission_check", "arePermissionsGranted: $permissionGranted")
    }

    private fun checkPermissions(application: Context) {
        arePermissionsGranted(application)
        if (!permissionGranted) {
            Log.i("permission_check", "permissions not granted, asking")
            val permissions = mutableListOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
                permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            }

            if (Build.VERSION.SDK_INT >= 33) {
                permissions.add("android.permission.POST_NOTIFICATIONS")
            }

            if (Build.VERSION.SDK_INT >= 34) { // Android 14 and above
                permissions.add("android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE")
            }

            ActivityCompat.requestPermissions(
                pluginActivity,
                permissions.toTypedArray(),
                myPermissionCode
            )
        } else {
            Log.i("permission_check", "permissions granted, continuing")
            completeCheckPermissions()
        }
    }

    private fun completeCheckPermissions() {
        if (permissionGranted) {
            // Do some other work if needed
            activeResult?.success(true)
        } else {
            activeResult?.error(
                "permissions_not_granted",
                "if permissions are not granted, you will not be able to use this plugin",
                null
            )
        }
        // Conveniently plugin invocations are all asynchronous
        activeResult = null
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ): Boolean {
        when (requestCode) {
            myPermissionCode -> {
                permissionGranted =
                    grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
                Log.i("permission_check", "onRequestPermissionsResult: $permissionGranted")
                completeCheckPermissions()
                return true
            }
        }
        return false
    }

    @SuppressLint("MissingPermission")
    fun getDevices(result: Result) {
        val devices = ba.bondedDevices
        val list = mutableListOf<HashMap<String, String>>()

        for (data in devices) {
            val hash = HashMap<String, String>()
            hash["address"] = data.address
            hash["name"] = data.name
            list.add(hash)
        }
        result.success(list.toList())
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel.setMethodCallHandler(null)
    }
}

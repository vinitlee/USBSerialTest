package com.example.usbserial

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.felhr.usbserial.UsbSerialDevice
import com.felhr.usbserial.UsbSerialInterface
import com.felhr.usbserial.UsbSerialInterface.UsbReadCallback
import kotlinx.android.synthetic.main.activity_main.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.random.Random


class MainActivity : AppCompatActivity() {
    private var keepRunning: Boolean = false

    lateinit var m_usbManager: UsbManager
    var m_device: UsbDevice? = null
    var m_serial: UsbSerialDevice? = null
    var m_connection: UsbDeviceConnection? = null
    private val ACTION_USB_PERMISSION: String = "com.vinitlee.USBSerial.USBPermission"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        m_usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        val filter = IntentFilter()
        filter.addAction(ACTION_USB_PERMISSION)
        filter.addAction(UsbManager.ACTION_USB_ACCESSORY_ATTACHED)
        filter.addAction(UsbManager.ACTION_USB_ACCESSORY_DETACHED)
        registerReceiver(broadcastReceiver, filter)
    }

    fun onClickStart(view: View) {
        when (view.id) {
            R.id.openBtn -> {
                startUsbConnect()
            }
            R.id.closeBtn -> {
                usbDisconnect()
            }
            R.id.updateBtn -> {
                readData()
            }
            R.id.bkUpdateBtn -> {
                if (keepRunning) {
                    keepRunning = false
                } else {
                    keepRunning = true
                    CoroutineScope(IO).launch {
                        updateReading()
                    }
                }
            }
        }
    }

    private fun startUsbConnect() {
        Log.i("Serial", "---------------------------------------------")
        val usbDevices: HashMap<String, UsbDevice>? = m_usbManager.deviceList
        if (usbDevices?.isNotEmpty()!!) {
            var keep = true
            usbDevices.forEach { entry ->
                m_device = entry.value
                Log.i("Serial", "See vendorID: ${m_device?.vendorId}")
                if (m_device?.vendorId == 5824) {
                    val intent: PendingIntent =
                        PendingIntent.getBroadcast(this, 0, Intent(ACTION_USB_PERMISSION), 0)
                    m_usbManager.requestPermission(m_device, intent)
                    keep = false
                    Log.i("Serial", "Connection successful to VID${m_device?.vendorId}")
                } else {
                    Log.i("Serial", "Did not connect to vendorID: ${m_device?.vendorId}")
                    m_connection = null
                    m_device = null
                }
            }
            if (!keep) {
                return
            }
        } else {
            Log.i("Serial", "No USB devices found")
        }
    }

    private fun readData() {
        m_serial?.read(mCallback)
    }
    private val mCallback = UsbReadCallback {
        val charset = Charsets.UTF_8
        Log.i("Serial","READ:${it.toString(charset)}")
    }

    private fun usbDisconnect() {
        if (m_serial != null) {
            m_serial!!.close()
        }
    }

    private val broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action!! == ACTION_USB_PERMISSION) {
                val granted: Boolean =
                    intent.extras!!.getBoolean(UsbManager.EXTRA_PERMISSION_GRANTED)
                if (granted) {
                    m_connection = m_usbManager.openDevice(m_device)
                    m_serial = UsbSerialDevice.createUsbSerialDevice(m_device, m_connection)
                    if (m_serial != null) {
                        if (m_serial!!.open()) {
                            m_serial!!.setBaudRate(115200)
                            m_serial!!.setDataBits(UsbSerialInterface.DATA_BITS_8)
                            m_serial!!.setStopBits(UsbSerialInterface.STOP_BITS_1)
                            m_serial!!.setParity(UsbSerialInterface.PARITY_NONE)
                            m_serial!!.setFlowControl(UsbSerialInterface.FLOW_CONTROL_OFF)
                        } else {
                            Log.i("Serial", "Port is not open")
                        }
                    } else {
                        Log.i("Serial", "Port is null")
                    }
                } else {
                    Log.v("Serial", "No extra permission")
                }
            } else if (intent.action!! == UsbManager.ACTION_USB_ACCESSORY_ATTACHED) {
                startUsbConnect()
            } else if (intent.action!! == UsbManager.ACTION_USB_ACCESSORY_DETACHED) {
                usbDisconnect()
            }
        }

    }

    private suspend fun updateReading() {
        val reading = getSerialData()
        withContext(Main) { setOutputText(reading) }
        if (keepRunning) {
            updateReading()
        }
    }

    private suspend fun setOutputText(newText: String) {
        outputText.text = "$newText"
    }

    private suspend fun getSerialData(): String {
//        logThread("getSerialData")

        // Wait for data to be ready
        delay(1000 / 80)
        // Get data
        val reading = Random.nextInt(1000, 5000).toString()
        // Return data
        return reading
    }

    private fun logThread(methodName: String) {
        Log.d("THREADS", "${methodName}:${Thread.currentThread().name}")
    }
}
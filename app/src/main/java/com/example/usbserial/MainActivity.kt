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
import kotlinx.android.synthetic.main.activity_main.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.launch
import java.util.*
import kotlin.collections.HashMap
import kotlin.math.abs
import kotlin.math.round


class MainActivity : AppCompatActivity() {
    private var calibrationMultiplier: Double = 4.8131056776462474E-4
    private var tareLevel: Double = 137612.0475

    private var rawValueHistory: Queue<Int> = LinkedList<Int>()
    private val maxHistory = 80 * 20

    private fun addToHistory(value: Int) {
        rawValueHistory.add(value)
        while (rawValueHistory.size > maxHistory) {
            rawValueHistory.remove()
        }
    }

    private fun clearHistory() {
        rawValueHistory = LinkedList<Int>()
    }

    private fun historyAverage(): Double {
        var sum: Double = 0.0
        var num: Int = 0
        rawValueHistory.forEach { value ->
            sum += value
            num += 1
        }

        return sum / num
    }

    private var connectedState: Boolean = false

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

        if (!connectedState) {
            startUsbConnect()
        }
    }

    fun onClickStart(view: View) {
        when (view) {
            connectionBtn -> {
                if (connectedState) {
                    usbDisconnect()
                } else {
                    startUsbConnect()
                }
            }
            tareBtn -> {
                tareLevel = historyAverage()
                Log.i("Calibration", "Tare level is now $tareLevel")
            }
            calibrateBtn -> {
                calibrationMultiplier = 20.0 / (historyAverage() - tareLevel)
                Log.i("Calibration", "Calibration N is now $calibrationMultiplier")
            }
        }
    }

    private fun updateConnectionState(connected: Boolean) {
        connectedState = connected
        if (connectedState) {
            connectionBtn.text = "Disconnect"
            calibrateBtn.isEnabled = true
            tareBtn.isEnabled = true
        } else {
            connectionBtn.text = "Connect"
            outputText.text = "---"
            calibrateBtn.isEnabled = false
            tareBtn.isEnabled = false
        }
    }

    private fun startUsbConnect() {
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
                    Log.i("WHERE", "------> 1")
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

    private fun startRead() {
        CoroutineScope(IO).launch { readData() }
    }

    private suspend fun readData() {
        m_serial?.read {
            // Convert to string and match pattern
            val charset = Charsets.UTF_8
            val matchResult = Regex("([0-9]+)").find(it.toString(charset))
            // If there is a match, then store
            var rawValue: Int? = null
            if (matchResult != null) rawValue = matchResult.groupValues[1].toInt()
            // If that all worked, pass along raw value for processing
            if (rawValue != null) {
                CoroutineScope(Main).launch {
                    if (abs(historyAverage() - rawValue) > 1 / calibrationMultiplier) {
                        clearHistory()
                    }

                    addToHistory(rawValue)

                    val printValue = applyTareAndCalibration(historyAverage()).toString()
                    val decimalFind = Regex("\\.[0-9]+").find(printValue)
                    var decimals = 3
                    if (decimalFind != null) {
                        decimals = decimalFind.value.length - 1
                    }
                    if (connectedState) {
                        outputText.text = "$printValue" + "0".repeat(3 - decimals)
                    }
                }
            }
        }
    }

    private fun applyTareAndCalibration(rawValue: Double): Double {
        return round(((rawValue - tareLevel) * calibrationMultiplier) * 1e3) / 1e3
    }


    private fun usbDisconnect() {
        if (m_serial != null) {
            m_serial!!.close()
        }
        updateConnectionState(false)
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
                            Log.i("WHERE", "------> 2")
                            updateConnectionState(true)
                            startRead()
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

}
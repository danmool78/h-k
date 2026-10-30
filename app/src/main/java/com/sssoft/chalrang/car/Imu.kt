package com.sssoft.chalrang.car

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.sssoft.chalrang.core.ImuFrame
import com.sssoft.chalrang.core.MotionEstimator

/**
 * 화면 기기의 가속도 센서 → 위아래 가속도와 길 경사.
 * 차량 SDK 에는 가속도 센서가 없어서 기기 센서를 쓴다 (없으면 아무것도 하지 않고 위아래 = 0).
 * 기기가 차에 어떤 방향으로 붙어 있는지는 [ImuFrame] 이 중력과 차의 앞뒤 가속으로 스스로 알아낸다.
 *
 * 화면이 보이는 동안만 켠다 ([start]/[stop]): 센서를 계속 붙잡아 두지 않는다.
 */
class Imu(context: Context) : SensorEventListener {
    private val manager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val sensor = manager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val frame = ImuFrame()
    private var listening = false

    var motion: MotionEstimator? = null
    var clock: () -> Double = { System.nanoTime() / 1e9 }

    val available: Boolean get() = sensor != null

    fun start() {
        if (listening || sensor == null) return
        listening = manager?.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME) == true
    }

    fun stop() {
        if (!listening) return
        manager?.unregisterListener(this)
        listening = false
    }

    override fun onSensorChanged(event: SensorEvent) {
        val m = motion ?: return
        val v = event.values
        val t = clock()
        frame.update(t, v[0].toDouble(), v[1].toDouble(), v[2].toDouble(), m.ax, m.ay, m.speed, m.mapGrade)
        m.onVertical(t, frame.vertical)
        if (frame.oriented) m.onGrade(frame.pitch, frame.roll)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}

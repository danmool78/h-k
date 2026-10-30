package com.sssoft.chalrang.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.sssoft.chalrang.audio.Voice
import com.sssoft.chalrang.car.CarLink
import com.sssoft.chalrang.car.Imu
import com.sssoft.chalrang.car.NaviLink
import com.sssoft.chalrang.data.ChildStore
import com.sssoft.chalrang.engine.ChalrangEngine

/** 차량 SDK(CarLink·NaviLink)·음성·저장을 공용 엔진에 연결한다. 루프는 MainActivity 가 화면이 보이는 동안만 돌린다 */
class ChalrangViewModel(app: Application) : AndroidViewModel(app) {

    private val car = CarLink(app)
    private val navi = NaviLink(app)
    private val voice = Voice(app)
    val engine = ChalrangEngine(car, navi, voice, ChildStore(app))

    /** 화면 기기 가속도 센서 (위아래·길 경사). 화면이 보이는 동안만 켠다 */
    val imu = Imu(app)

    init {
        car.motion = engine.motion
        navi.motion = engine.motion
        imu.motion = engine.motion
        // 차량·내비·센서 값은 엔진 시각으로 (끊김 판정이 같은 시계를 쓰게)
        car.clock = engine::now
        navi.clock = engine::now
        imu.clock = engine::now
        car.start()
        navi.start()
    }

    override fun onCleared() {
        engine.close()
        car.stop()
        navi.stop()
        imu.stop()
        voice.release()
    }
}

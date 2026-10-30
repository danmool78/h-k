package com.sssoft.chalrang.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.sssoft.chalrang.audio.Voice
import com.sssoft.chalrang.car.CarLink
import com.sssoft.chalrang.car.NaviLink
import com.sssoft.chalrang.data.ChildStore
import com.sssoft.chalrang.engine.ChalrangEngine

/** 차량 SDK(CarLink·NaviLink)·음성·저장을 공용 엔진에 연결한다. 루프는 MainActivity 가 화면이 보이는 동안만 돌린다 */
class ChalrangViewModel(app: Application) : AndroidViewModel(app) {

    private val car = CarLink(app)
    private val navi = NaviLink(app)
    private val voice = Voice(app)
    val engine = ChalrangEngine(car, navi, voice, ChildStore(app))

    init {
        car.motion = engine.motion
        navi.motion = engine.motion
        car.start()
        navi.start()
    }

    override fun onCleared() {
        engine.close()
        car.stop()
        navi.stop()
        voice.release()
    }
}

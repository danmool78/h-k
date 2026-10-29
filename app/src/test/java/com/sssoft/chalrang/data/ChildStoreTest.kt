package com.sssoft.chalrang.data

import com.sssoft.chalrang.core.Child
import com.sssoft.chalrang.core.Seat
import org.junit.Assert.assertEquals
import org.junit.Test

class ChildStoreTest {

    @Test
    fun `저장했다 읽으면 그대로`() {
        val list = listOf(Child("1", "민지", Seat.REAR_LEFT, 0.72, 2), Child("2", "준호", Seat.FRONT_RIGHT))
        assertEquals(list, ChildStore.decode(ChildStore.encode(list)))
    }

    @Test
    fun `깨진 값은 빈 목록`() {
        assertEquals(emptyList<Child>(), ChildStore.decode("{oops"))
        assertEquals(emptyList<Child>(), ChildStore.decode(null))
    }
}

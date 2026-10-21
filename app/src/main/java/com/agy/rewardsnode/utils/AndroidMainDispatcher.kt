package com.agy.rewardsnode.utils

import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CoroutineDispatcher
import java.lang.Runnable
import kotlin.coroutines.CoroutineContext

/**
 * CoroutineDispatcher điều phối luồng Main UI trên Android dựa trên Main Looper Handler.
 * Hoạt động độc lập 100% với public stable API của Kotlin Coroutines.
 */
object AndroidMainDispatcher : CoroutineDispatcher() {
    private val handler = Handler(Looper.getMainLooper())

    override fun isDispatchNeeded(context: CoroutineContext): Boolean {
        return Looper.myLooper() != Looper.getMainLooper()
    }

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        handler.post(block)
    }
}

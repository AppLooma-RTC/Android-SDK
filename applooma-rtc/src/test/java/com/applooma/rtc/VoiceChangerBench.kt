package com.applooma.rtc

import org.junit.Ignore
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/** CPU cost per preset. Run by hand: remove @Ignore, then gradlew testReleaseUnitTest --tests "*VoiceChangerBench" -i */
@Ignore("benchmark, run by hand")
class VoiceChangerBench {
    @Test fun bench() {
        val rate = 48_000
        val buf = FloatArray(480)
        for (p in listOf(AppVoiceChanger.CHIPMUNK, AppVoiceChanger.ROBOT, AppVoiceChanger.MONSTER)) {
            val vc = VoiceChanger(rate).apply { set(p) }
            var t = 0L
            repeat(3) { round ->
                val start = System.nanoTime()
                for (f in 0 until 1000) { // 10 s of audio
                    for (i in 0 until 480) buf[i] = (0.3 * sin(2 * PI * 200 * (f * 480 + i) / rate)).toFloat()
                    vc.process(buf, 480)
                }
                if (round == 2) t = System.nanoTime() - start
            }
            println("BENCH $p: ${t / 1_000_000} ms per 10 s of audio = ${"%.2f".format(t / 1e7 / 10)} % of one core")
        }
    }
}

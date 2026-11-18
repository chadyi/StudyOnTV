package com.studyontv.tv

import android.content.Context

object MandarinMathRegression {
    fun run(context: Context): String {
        DeviceRuntime.ensure(context)
        check(SpeechReader.settings(DeviceRuntime.store).getString("mode") == "system")
        val started = System.currentTimeMillis()
        SpeechReader.read(context, "18-18=", "zh-CN")
        while (System.currentTimeMillis() - started < 65000) {
            Thread.sleep(100)
            val status = SpeechReader.status()
            if (status.optLong("at") < started) continue
            check(status.optBoolean("ok")) { status.optString("message") }
            if (status.optString("reason") == "done") {
                check(SpeechReader.lastLocalSynthesisText == "十八减十八等于多少") {
                    "Incorrect text sent to local engine: ${SpeechReader.lastLocalSynthesisText}"
                }
                check(status.optString("language") == "zh-CN")
                return "PASS: 18-18= -> 十八减十八等于多少; zh-CN; real local playback completed\n"
            }
        }
        error("Math playback timed out")
    }
}

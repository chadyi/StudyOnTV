package com.studyontv.tv

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.io.File
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real installed-engine and media-mute regression. Does not change user settings. */
object LocalSpeechRegression {
    private const val TEXT = "小朋友，请认真听完整道题目。小明有三个苹果，妈妈又给了他两个苹果，请问小明现在一共有几个苹果？请听完以后再选择答案。"

    fun run(context: Context): String {
        val main = Handler(Looper.getMainLooper())
        val initialized = CountDownLatch(1)
        var engine: TextToSpeech? = null
        var initStatus = -1
        val done = CountDownLatch(1)
        var error = false
        val file = File(context.cacheDir, "local-speech-regression.wav")
        try {
            main.post { engine = TextToSpeech(context) { status -> initStatus = status; initialized.countDown() } }
            check(initialized.await(30, TimeUnit.SECONDS) && initStatus == TextToSpeech.SUCCESS) { "Local engine initialization failed: $initStatus" }
            check(engine!!.setLanguage(Locale.SIMPLIFIED_CHINESE) >= 0) { "Mandarin unavailable" }
            val voice = engine!!.voice
            check(voice.locale.language in listOf("zh", "cmn", "zho")) { "Wrong local voice: ${voice.locale}" }
            engine!!.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) = Unit
                override fun onDone(id: String?) { done.countDown() }
                @Deprecated("Deprecated in Java")
                override fun onError(id: String?) { error = true; done.countDown() }
            })
            check(engine!!.synthesizeToFile(TEXT, null, file, "local-file") == TextToSpeech.SUCCESS)
            check(done.await(90, TimeUnit.SECONDS) && !error) { "Full-sentence synthesis failed" }
            val pcm = WavAudio.parse(file.readBytes()) ?: error("Engine did not produce PCM WAV")
            val seconds = pcm.frames.toDouble() / pcm.sampleRate
            check(seconds >= 8) { "Long Mandarin sentence was truncated to ${seconds}s" }
            engine!!.shutdown(); engine = null

            DeviceRuntime.ensure(context)
            check(SpeechReader.settings(DeviceRuntime.store).getString("mode") == "system") { "Select local system speech before this real-engine test" }
            val start = System.currentTimeMillis()
            val writes = MediaPause.writeCount
            main.post { MediaPause.pause(context); SpeechReader.read(context, TEXT, "zh-CN") }
            var finished = false
            while (System.currentTimeMillis() - start < 100000) {
                main.post { MediaPause.enforce(context) }
                Thread.sleep(100)
                val state = SpeechReader.status()
                if (state.optLong("at") < start) continue
                check(state.optBoolean("ok")) { "Reading failed: ${state.optString("message")}" }
                if (state.optString("reason") == "done") { finished = true; break }
            }
            check(finished) { "Local playback did not finish" }
            check(MediaPause.writeCount == writes) { "Gate muted its own speech" }
            check(System.currentTimeMillis() - start >= 7000) { "Streaming playback ended prematurely" }
            check(SpeechReader.localFallbackLanguage("") == "zh-CN")
            check(SpeechReader.localFallbackLanguage("zh-HK") == "zh-CN")
            return "PASS: Mandarin ${voice.locale}, full synthesized audio %.1fs, complete playback under gate enforcement, no self-mute\n".format(seconds)
        } finally {
            main.post { SpeechReader.stop(); MediaPause.release(context); engine?.shutdown() }
            file.delete()
        }
    }
}

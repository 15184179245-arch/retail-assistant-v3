package com.retaildemo.v2.util

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * I17 语音输入封装：系统级 SpeechRecognizer（免费、多数 ROM 离线可用）。
 * 回调全部回主线程（recognizer 在创建线程的 looper 上回调，Compose 点击在主线程创建）。
 * 不可用（无语音服务）时调用方应隐藏按钮——手册设计：隐藏而非置灰报错。
 */
class SpeechHelper(private val context: Context) {

    fun available(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    private var recognizer: SpeechRecognizer? = null

    fun start(
        onPartial: (String) -> Unit,
        onFinal: (String) -> Unit,
        onError: (String) -> Unit,
    ) {
        if (!available()) {
            onError("unavailable")
            return
        }
        stop()
        val sr = SpeechRecognizer.createSpeechRecognizer(context)
        sr.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}

            override fun onPartialResults(partialResults: Bundle?) {
                val text = firstText(partialResults)
                if (text.isNotBlank()) onPartial(text)
            }

            override fun onResults(results: Bundle?) {
                val text = firstText(results)
                stop()
                if (text.isNotBlank()) onFinal(text) else onError("no_match")
            }

            override fun onError(error: Int) {
                val msg = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "没听清，再说一次？"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "需要麦克风权限"
                    else -> "语音识别失败($error)"
                }
                stop()
                onError(msg)
            }
        })
        recognizer = sr
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
        sr.startListening(intent)
    }

    fun stop() {
        recognizer?.destroy()
        recognizer = null
    }

    /** 长按松手：请求收尾出最终结果（触发 onResults），与 [stop] 的销毁语义不同。 */
    fun release() {
        recognizer?.stopListening()
    }

    private fun firstText(bundle: Bundle?): String =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull() ?: ""
}

package app.marxistlibrary.reader.reader

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale

data class SpeechState(
    val ready: Boolean = false,
    val localVoice: Boolean = false,
    val playing: Boolean = false,
    val index: Int = 0,
    val size: Int = 0,
    val problem: String = ""
)

// Keep the current paragraph when the user pauses. Android owns voice synthesis.
class Speech(context: Context, private val onAnchor: (String) -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private var closed = false
    private var sequence = 0
    private var rate = 1f
    private var paragraphs = emptyList<SpokenText>()
    var state by mutableStateOf(SpeechState())
        private set
    private val engine: TextToSpeech = TextToSpeech(context.applicationContext) { result ->
        main.post { if (!closed) initialize(result) }
    }

    private fun initialize(result: Int) {
        if (result != TextToSpeech.SUCCESS || engine.isLanguageAvailable(Locale.SIMPLIFIED_CHINESE) < 0) {
            state = state.copy(problem = "请在系统文字转语音设置中安装中文语音")
            return
        }
        engine.language = Locale.SIMPLIFIED_CHINESE
        val offline = engine.voices?.firstOrNull {
            it.locale.language == Locale.CHINESE.language && !it.isNetworkConnectionRequired &&
                "notInstalled" !in it.features.orEmpty()
        }
        if (offline != null) engine.voice = offline
        state = state.copy(ready = true, localVoice = engine.voice?.isNetworkConnectionRequired == false)
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) = Unit
            override fun onDone(utteranceId: String) = advance(utteranceId, false)
            @Deprecated("Android may invoke either error callback")
            override fun onError(utteranceId: String) = advance(utteranceId, true)
            override fun onError(utteranceId: String, errorCode: Int) = advance(utteranceId, true)
        })
    }

    fun start(values: List<SpokenText>) {
        if (!state.ready || values.isEmpty()) return
        paragraphs = values
        state = state.copy(index = 0, size = values.size, problem = "")
        say()
    }

    fun pause() {
        sequence++
        engine.stop()
        state = state.copy(playing = false)
    }

    fun resume() {
        if (state.ready && paragraphs.isNotEmpty() && state.index < paragraphs.size) say()
    }

    fun skip() {
        if (state.index + 1 < paragraphs.size) {
            val playing = state.playing
            pause()
            state = state.copy(index = state.index + 1)
            if (playing) say()
        }
    }

    fun rate(value: Int) {
        rate = value / 100f
        if (state.ready) engine.setSpeechRate(rate)
    }

    fun close() {
        closed = true
        pause()
        engine.shutdown()
    }

    private fun say() {
        val next = paragraphs[state.index]
        val token = (++sequence).toString()
        engine.setSpeechRate(rate)
        state = state.copy(playing = true)
        onAnchor(next.anchor)
        if (engine.speak(next.text, TextToSpeech.QUEUE_FLUSH, null, token) == TextToSpeech.ERROR) {
            state = state.copy(playing = false, problem = "系统语音暂时无法朗读")
        }
    }

    private fun advance(id: String, error: Boolean) {
        main.post {
            if (closed || !state.playing || id != sequence.toString()) return@post
            if (error) {
                state = state.copy(playing = false, problem = "系统语音暂时无法朗读")
            } else {
                state = state.copy(index = state.index + 1)
                if (state.index < state.size) say() else state = state.copy(playing = false)
            }
        }
    }
}

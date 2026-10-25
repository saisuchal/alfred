package com.hackquant.steward

import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.util.concurrent.Executors

/**
 * Wrapper around MediaPipe's on-device LLM runtime.
 *
 * The prompt is split into a prefix (instructions, profile, rules) that only changes when the user edits
 * their settings, and a per-message suffix. The prefix is fed once into a base session that is cloned for
 * every message, so its tokens aren't reprocessed each time.
 */
object Llm {
    private const val TAG = "StewardLlm"
    @Volatile private var engine: LlmInference? = null
    private var base: LlmInferenceSession? = null
    private var baseKey: String? = null
    @Volatile var modelName = "Qwen2.5-1.5B"
        private set
    @Volatile var contextTokens = 1280
        private set
    val status = MutableStateFlow("Model not loaded")
    private val loader = Executors.newSingleThreadExecutor()

    fun modelDir(ctx: Context): File = ctx.getExternalFilesDir(null)!!

    private fun candidates(ctx: Context) = listOf(
        File("/data/local/tmp/llm/model-4k.task"),
        File(modelDir(ctx), "model-4k.task"),
        File("/data/local/tmp/llm/model.task"),
        File(modelDir(ctx), "model.task"),
    )

    fun ready() = engine != null

    @Volatile private var loading = false

    /** Blocks until a load in progress finishes, so messages don't fall back to keywords during startup. */
    fun awaitReady(timeoutMs: Long) {
        val until = System.currentTimeMillis() + timeoutMs
        while (engine == null && loading && System.currentTimeMillis() < until) Thread.sleep(250)
    }

    fun load(ctx: Context) {
        if (engine != null) return
        val app = ctx.applicationContext
        loader.execute {
            if (engine != null) return@execute
            val path = candidates(app).firstOrNull { it.canRead() }
            if (path == null) {
                status.value = "No model installed"
                return@execute
            }
            contextTokens = if (path.name.contains("4k")) 4096 else 1280
            modelName = "Qwen2.5-1.5B · ${contextTokens / 1024.0}k ctx".replace(".0k", "k")
            status.value = "Loading $modelName…"
            loading = true
            try {
                val t0 = System.currentTimeMillis()
                val options = LlmInference.LlmInferenceOptions.builder()
                    .setModelPath(path.absolutePath)
                    .setMaxTokens(contextTokens)
                    .build()
                engine = LlmInference.createFromOptions(app, options)
                status.value = "$modelName · ready"
                loading = false
                Agent.warmSoon() // prepare the prompt now, not when the first message arrives
                Log.i(TAG, "loaded ${path.name} in ${System.currentTimeMillis() - t0}ms")
            } catch (e: Throwable) {
                status.value = "Model failed to load: ${e.message}"
                loading = false
            }
        }
    }

    private fun newSession(llm: LlmInference) = LlmInferenceSession.createFromOptions(
        llm,
        LlmInferenceSession.LlmInferenceSessionOptions.builder().setTopK(1).setTemperature(0.0f).build(),
    )

    /** Blocking. Call from a background thread. */
    @Synchronized
    fun generate(prefix: String, suffix: String): String {
        val llm = engine ?: error("model not loaded")
        val t0 = System.currentTimeMillis()
        val session = try {
            if (baseKey != prefix) {
                base?.close()
                base = newSession(llm).also { it.addQueryChunk(prefix) }
                baseKey = prefix
            }
            base!!.cloneSession()
        } catch (e: Throwable) {
            // Cloning isn't supported by every model build; fall back to a fresh session.
            Log.w(TAG, "clone failed, using fresh session", e)
            base?.close(); base = null; baseKey = null
            newSession(llm).also { it.addQueryChunk(prefix) }
        }
        try {
            session.addQueryChunk(suffix)
            val out = fixBytes(session.generateResponse())
            Log.i(TAG, "generated in ${System.currentTimeMillis() - t0}ms: $out")
            return out
        } finally {
            session.close()
        }
    }

    // GPT-2 style byte-to-unicode table used by Qwen's tokenizer, inverted.
    private val byteDecoder: Map<Char, Int> by lazy {
        val printable = ((33..126) + (161..172) + (174..255)).toSet()
        val map = HashMap<Char, Int>()
        printable.forEach { map[it.toChar()] = it }
        var n = 0
        for (b in 0..255) if (b !in printable) { map[(256 + n).toChar()] = b; n++ }
        map
    }

    private fun strictUtf8(bytes: ByteArray): String? = runCatching {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(bytes)).toString()
    }.getOrNull()

    /**
     * The runtime sometimes returns undecoded tokens, e.g. "Ġ" for a space and "ðŁĺĬ" for 😊.
     * Runs of such characters are turned back into bytes and decoded, if they form valid UTF-8.
     */
    fun fixBytes(s: String): String {
        fun encoded(c: Char) = c.code in 0xA1..0x143 && byteDecoder.containsKey(c)
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            if (!encoded(s[i])) { sb.append(s[i]); i++; continue }
            var j = i
            val bytes = java.io.ByteArrayOutputStream()
            while (j < s.length && encoded(s[j])) { bytes.write(byteDecoder.getValue(s[j])); j++ }
            sb.append(strictUtf8(bytes.toByteArray()) ?: s.substring(i, j))
            i = j
        }
        return sb.toString()
    }

    @Synchronized fun isWarm(prefix: String) = baseKey == prefix

    /** Process the prefix ahead of time so the first real message is fast. */
    fun warm(prefix: String) {
        loader.execute {
            if (engine == null || isWarm(prefix)) return@execute
            runCatching { generate(prefix, "Reply with {\"actions\":[]}<|im_end|>\n<|im_start|>assistant\n") }
        }
    }
}

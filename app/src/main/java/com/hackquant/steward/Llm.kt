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
        File("/data/local/tmp/llm/model-4b.litertlm"),
        File(modelDir(ctx), "model-4b.litertlm"),
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
            val paths = candidates(app).filter { it.canRead() }
            if (paths.isEmpty()) {
                status.value = "No model installed"
                return@execute
            }
            loading = true
            // Try the largest model first; if this runtime can't load it, fall back to the next one.
            for (path in paths) {
                val big = path.name.contains("4b")
                contextTokens = if (big || path.name.contains("4k")) 4096 else 1280
                modelName = (if (big) "Qwen3-4B" else "Qwen2.5-1.5B") + " · ${contextTokens / 1024}k ctx"
                status.value = "Loading $modelName…"
                try {
                    val t0 = System.currentTimeMillis()
                    val options = LlmInference.LlmInferenceOptions.builder()
                        .setModelPath(path.absolutePath)
                        .setMaxTokens(contextTokens)
                        .build()
                    engine = LlmInference.createFromOptions(app, options)
                    status.value = "$modelName · ready"
                    Log.i(TAG, "loaded ${path.name} in ${System.currentTimeMillis() - t0}ms")
                    break
                } catch (e: Throwable) {
                    Log.w(TAG, "couldn't load ${path.name}: ${e.message}")
                    status.value = "Model failed to load: ${e.message}"
                }
            }
            loading = false
            if (engine != null) Agent.warmSoon() // prepare the prompt now, not when the first message arrives
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
                Log.i(TAG, "preparing prompt: ${runCatching { newSession(llm).use { it.sizeInTokens(prefix) } }.getOrDefault(-1)} tokens")
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
            val out = fixBytes(stream(session, ::jsonComplete))
            Log.i(TAG, "generated in ${System.currentTimeMillis() - t0}ms: $out")
            return out
        } finally {
            session.close()
        }
    }

    /** True once the text holds one complete top-level JSON object. */
    private fun jsonComplete(text: String): Boolean {
        val start = text.indexOf('{')
        if (start < 0) return false
        var depth = 0
        var inString = false
        var escaped = false
        for (ch in text.substring(start)) {
            when {
                escaped -> escaped = false
                ch == '\\' && inString -> escaped = true
                ch == '"' -> inString = !inString
                !inString && ch == '{' -> depth++
                !inString && ch == '}' -> { depth--; if (depth == 0) return true }
            }
        }
        return false
    }

    /**
     * Streams the answer and stops the model the moment [done] says the answer is complete.
     * There's no output-length setting, and a small model otherwise keeps writing after its answer.
     */
    private fun stream(session: LlmInferenceSession, done: (String) -> Boolean): String {
        val sb = StringBuilder()
        val stopped = java.util.concurrent.atomic.AtomicBoolean(false)
        val future = session.generateResponseAsync { partial, _ ->
            synchronized(sb) { sb.append(partial) }
            if (!stopped.get() && done(synchronized(sb) { sb.toString() }) && stopped.compareAndSet(false, true)) {
                runCatching { session.cancelGenerateResponseAsync() }
            }
        }
        runCatching { future.get(180, java.util.concurrent.TimeUnit.SECONDS) }
        return synchronized(sb) { sb.toString() }
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

    /** A short standalone prompt, e.g. rewording the owner's answer. Doesn't touch the cached prompt. */
    @Synchronized
    fun generateOnce(system: String, user: String): String {
        val llm = engine ?: error("model not loaded")
        val session = newSession(llm)
        try {
            session.addQueryChunk("<|im_start|>system\n$system<|im_end|>\n<|im_start|>user\n$user<|im_end|>\n<|im_start|>assistant\n")
            // One message is one line: stop at the first line break after some text.
            return fixBytes(stream(session) { it.trim().contains('\n') || it.length > 300 }).trim()
        } finally {
            session.close()
        }
    }

    /** Process the prefix ahead of time so the first real message is fast. */
    fun warm(prefix: String) {
        loader.execute {
            if (engine == null || isWarm(prefix)) return@execute
            runCatching { generate(prefix, "Reply with {\"actions\":[]}<|im_end|>\n<|im_start|>assistant\n") }
        }
    }
}

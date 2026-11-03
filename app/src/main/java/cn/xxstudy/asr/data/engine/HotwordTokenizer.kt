package cn.xxstudy.asr.data.engine

import java.util.Locale

/**
 * 面向 `sherpa-onnx` 多架构词表（`cjkchar` / `SentencePiece ▁` / `Byte-BPE`）的自适应热词分词编码器。
 *
 * 工程背景：
 * 不同官方流式模型的 `tokens.txt` 建模粒度存在差异：
 * - `Zipformer 14M` 采用纯汉字单元（如 `唐`、`人`）；
 * - `Multi-zh`、`Zipformer-zh 2025` 与 `X-ASR` 采用带 `▁` 前缀的 SentencePiece 子词及字节回退（`<0xE5>`）。
 * 本类在模型加载时建立词表符号索引，将用户输入的自然热词（如 `"唐人,鸿蒙,API"`）安全编码为 100% 命中当前词表的符号序列，
 * 无需外部 `bpe.vocab` 文件即可跨模型驱动 `OnlineRecognizer.createStream(hotwords)` 上下文偏置。
 *
 * @property vocabSymbols 当前已加载模型 `tokens.txt` 中的合法符号集合
 */
class HotwordTokenizer(
    private val vocabSymbols: Set<String>,
) {

    /**
     * 将用户输入的自然语言热词串编码为 `sherpa-onnx` 原生流支持的按行分词格式。
     *
     * @param rawInput 逗号、顿号、分号或换行分隔的热词文本（支持可选的 `词条:分值` 独立权重写法）
     * @param defaultScore 未单独指定分值时使用的默认束搜索加权分（如 `2.0f`）
     * @return 编码后的多行热词字符串；若无有效热词则返回空串
     */
    fun encodeHotwords(rawInput: String, defaultScore: Float = 2.0f): String {
        if (rawInput.isBlank() || vocabSymbols.isEmpty()) return ""

        val encodedLines = mutableListOf<String>()
        val entries = rawInput.split(ENTRY_DELIMITER_REGEX)
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        for (entry in entries) {
            val (phrase, score) = parsePhraseAndScore(entry, defaultScore)
            if (phrase.isEmpty()) continue
            val tokens = tokenizePhrase(phrase) ?: continue
            if (tokens.isNotEmpty()) {
                val formattedScore = String.format(Locale.US, "%.1f", score)
                encodedLines.add("${tokens.joinToString(" ")} :$formattedScore")
            }
        }
        return encodedLines.joinToString("\n")
    }

    private fun parsePhraseAndScore(entry: String, defaultScore: Float): Pair<String, Float> {
        val colonIdx = entry.lastIndexOf(':')
        if (colonIdx <= 0 || colonIdx == entry.lastIndex) {
            return entry to defaultScore
        }
        val customScore = entry.substring(colonIdx + 1).trim().toFloatOrNull()
            ?: return entry to defaultScore
        return entry.substring(0, colonIdx).trim() to customScore.coerceIn(0.5f, 10.0f)
    }

    private fun tokenizePhrase(phrase: String): List<String>? {
        val result = mutableListOf<String>()
        var i = 0
        while (i < phrase.length) {
            val ch = phrase[i]
            if (ch.isWhitespace()) {
                i++
                continue
            }
            if (isCjkIdeograph(ch)) {
                val cjkTokens = encodeSingleCjkChar(ch) ?: return null
                result.addAll(cjkTokens)
                i++
            } else if (ch.isLetterOrDigit()) {
                val start = i
                while (i < phrase.length && phrase[i].isLetterOrDigit() && !isCjkIdeograph(phrase[i])) {
                    i++
                }
                val word = phrase.substring(start, i)
                val wordTokens = encodeAsciiWord(word) ?: return null
                result.addAll(wordTokens)
            } else {
                i++
            }
        }
        return result
    }

    private fun encodeSingleCjkChar(ch: Char): List<String>? {
        val spToken = "$SP_PREFIX$ch"
        if (spToken in vocabSymbols) return listOf(spToken)
        val plainToken = ch.toString()
        if (plainToken in vocabSymbols) return listOf(plainToken)
        return encodeUtf8ByteFallback(plainToken)
    }

    private fun encodeAsciiWord(word: String): List<String>? {
        val out = mutableListOf<String>()
        var pos = 0
        while (pos < word.length) {
            var matchedToken: String? = null
            var matchedLen = 0
            for (len in (word.length - pos) downTo 1) {
                val sub = word.substring(pos, pos + len)
                val candidates = if (pos == 0) {
                    listOf(
                        "$SP_PREFIX$sub",
                        "$SP_PREFIX${sub.lowercase(Locale.ROOT)}",
                        "$SP_PREFIX${sub.uppercase(Locale.ROOT)}",
                        sub,
                        sub.uppercase(Locale.ROOT),
                        sub.lowercase(Locale.ROOT),
                    )
                } else {
                    listOf(
                        sub,
                        sub.lowercase(Locale.ROOT),
                        sub.uppercase(Locale.ROOT),
                        "$SP_PREFIX$sub",
                        "$SP_PREFIX${sub.uppercase(Locale.ROOT)}",
                        "$SP_PREFIX${sub.lowercase(Locale.ROOT)}",
                    )
                }
                val hit = candidates.firstOrNull { it in vocabSymbols }
                if (hit != null) {
                    matchedToken = hit
                    matchedLen = len
                    break
                }
            }
            if (matchedToken != null) {
                out.add(matchedToken)
                pos += matchedLen
            } else {
                val byteTokens = encodeUtf8ByteFallback(word[pos].toString()) ?: return null
                out.addAll(byteTokens)
                pos += 1
            }
        }
        return out
    }

    private fun encodeUtf8ByteFallback(text: String): List<String>? {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val byteTokens = bytes.map { b ->
            String.format(Locale.US, "<0x%02X>", b.toInt() and 0xFF)
        }
        return if (byteTokens.all { it in vocabSymbols }) byteTokens else null
    }

    private fun isCjkIdeograph(ch: Char): Boolean {
        return ch.code in 0x4E00..0x9FFF
    }

    companion object {
        /** SentencePiece 词首前导下划线符号（U+2581）。 */
        private const val SP_PREFIX = '\u2581'

        private val IGNORED_SPECIAL_TOKENS = setOf("<blk>", "<sos/eos>", "<unk>")

        private val ENTRY_DELIMITER_REGEX = Regex("[,，、;；\\n]+")

        /**
         * 从 `tokens.txt` 行序列解析构建 [HotwordTokenizer] 实例。
         *
         * @param lines `tokens.txt` 的按行文本流（每行格式为 `symbol id`）
         * @return 词表分词器实例
         */
        fun fromTokensLines(lines: Sequence<String>): HotwordTokenizer {
            val symbols = HashSet<String>(4096)
            for (rawLine in lines) {
                val trimmed = rawLine.trim()
                if (trimmed.isEmpty()) continue
                val sepIdx = trimmed.lastIndexOf(' ')
                val token = if (sepIdx > 0) trimmed.substring(0, sepIdx).trim() else trimmed
                if (token.isNotEmpty() && token !in IGNORED_SPECIAL_TOKENS) {
                    symbols.add(token)
                }
            }
            return HotwordTokenizer(symbols)
        }
    }
}

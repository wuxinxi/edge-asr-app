package cn.xxstudy.asr.data.engine

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [HotwordTokenizer] 跨词表自适应热词分词单元测试。
 */
class HotwordTokenizerTest {

    @Test
    fun `encodes plain CJK characters for Zipformer 14M style tokens`() {
        val lines = sequenceOf(
            "<blk> 0",
            "<unk> 2",
            "唐 10",
            "人 11",
            "鸿 12",
            "蒙 13",
        )
        val tokenizer = HotwordTokenizer.fromTokensLines(lines)
        val encoded = tokenizer.encodeHotwords("唐人, 鸿蒙:3.5, OutOfVocab", defaultScore = 2.0f)

        assertEquals(
            "唐 人 :2.0\n鸿 蒙 :3.5",
            encoded,
        )
    }

    @Test
    fun `encodes SentencePiece prefixed CJK and English subwords for X-ASR style tokens`() {
        val lines = sequenceOf(
            "<blk> 0",
            "\u2581唐 15",
            "\u2581人 16",
            "\u2581A 100",
            "P 101",
            "I 102",
            "\u2581Kot 200",
            "lin 201",
        )
        val tokenizer = HotwordTokenizer.fromTokensLines(lines)
        val encoded = tokenizer.encodeHotwords("唐人，API、Kotlin", defaultScore = 2.5f)

        assertEquals(
            "\u2581唐 \u2581人 :2.5\n\u2581A P I :2.5\n\u2581Kot lin :2.5",
            encoded,
        )
    }
}

package com.bigtype.app

import java.util.regex.Matcher
import java.util.regex.Pattern

/** أدوات تحويل النصوص (تعمل على التحديد أو على كامل النص). */
object TextTools {
    private val diacritics =
        Regex("[\\u0610-\\u061A\\u064B-\\u065F\\u0670\\u06D6-\\u06DC\\u06DF-\\u06E8\\u06EA-\\u06ED\\u0640]")
    private val spaces = Regex("[ \\t\\u00A0]+")
    private val wordStart = Regex("(^|\\s)(\\p{L})")

    fun collapseSpaces(s: String): String =
        s.lines().joinToString("\n") { it.replace(spaces, " ").trim() }

    fun removeEmptyLines(s: String): String =
        s.lines().filter { it.isNotBlank() }.joinToString("\n")

    fun dedupeLines(s: String): String = s.lines().distinct().joinToString("\n")

    fun sortLines(s: String): String =
        s.lines().sortedWith(String.CASE_INSENSITIVE_ORDER).joinToString("\n")

    fun reverseLines(s: String): String = s.lines().reversed().joinToString("\n")

    fun joinLines(s: String): String =
        s.lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ")

    fun numberLines(s: String): String =
        s.lines().mapIndexed { i, l -> "${i + 1}. $l" }.joinToString("\n")

    fun upper(s: String): String = s.uppercase()
    fun lower(s: String): String = s.lowercase()

    fun title(s: String): String =
        wordStart.replace(s.lowercase()) { it.groupValues[1] + it.groupValues[2].uppercase() }

    fun removeDiacritics(s: String): String = s.replace(diacritics, "")

    /** توحيد الألف (أ إ آ ٱ ← ا) والألف المقصورة (ى ← ي). */
    fun normalizeArabic(s: String): String =
        s.replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا').replace('ٱ', 'ا').replace('ى', 'ي')

    fun toWesternDigits(s: String): String = buildString {
        for (c in s) {
            append(
                when (c) {
                    in '٠'..'٩' -> '0' + (c - '٠')
                    in '۰'..'۹' -> '0' + (c - '۰')
                    else -> c
                }
            )
        }
    }

    fun toArabicDigits(s: String): String = buildString {
        for (c in s) {
            append(if (c in '0'..'9') '٠' + (c - '0') else c)
        }
    }
}

data class Match(val start: Int, val end: Int)

data class SearchOptions(val regex: Boolean, val matchCase: Boolean, val wholeWord: Boolean)

class SearchTimeout : RuntimeException()

/** يرمي استثناء إن استغرق التعبير وقتاً طويلاً (حماية من التعابير البطيئة). */
private class DeadlineText(private val s: CharSequence, private val deadline: Long) : CharSequence {
    override val length: Int get() = s.length

    override fun get(index: Int): Char {
        if (System.nanoTime() > deadline) throw SearchTimeout()
        return s[index]
    }

    override fun subSequence(startIndex: Int, endIndex: Int): CharSequence =
        DeadlineText(s.subSequence(startIndex, endIndex), deadline)

    override fun toString(): String = s.toString()
}

object Finder {
    private const val TIMEOUT_NS = 1_500_000_000L

    private fun wrap(text: String): CharSequence =
        DeadlineText(text, System.nanoTime() + TIMEOUT_NS)

    fun compile(query: String, o: SearchOptions): Pattern {
        var p = if (o.regex) query else Pattern.quote(query)
        if (o.wholeWord) p = "(?<![\\p{L}\\p{N}_])(?:$p)(?![\\p{L}\\p{N}_])"
        var flags = Pattern.MULTILINE
        if (!o.matchCase) flags = flags or Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
        return Pattern.compile(p, flags)
    }

    fun findAll(p: Pattern, text: String, limit: Int = 20000): List<Match> {
        val m = p.matcher(wrap(text))
        val out = ArrayList<Match>()
        while (out.size < limit && m.find()) out.add(Match(m.start(), m.end()))
        return out
    }

    /** في وضع Regex: \n و \t في نص الاستبدال تصبح سطراً جديداً وTab، و $1 تشير للمجموعات. */
    private fun prepReplacement(rep: String, regex: Boolean): String =
        if (regex) rep.replace("\\n", "\n").replace("\\t", "\t") else Matcher.quoteReplacement(rep)

    fun replaceAll(p: Pattern, text: String, rep: String, regex: Boolean): String =
        p.matcher(wrap(text)).replaceAll(prepReplacement(rep, regex))

    /** يستبدل أول نتيجة عند الموضع [from] أو بعده. يرجع (بداية، نهاية، النص الجديد). */
    fun replaceAt(
        p: Pattern, text: String, from: Int, rep: String, regex: Boolean
    ): Triple<Int, Int, String>? {
        val m = p.matcher(wrap(text))
        if (!m.find(from)) return null
        val sb = StringBuffer()
        m.appendReplacement(sb, prepReplacement(rep, regex))
        return Triple(m.start(), m.end(), sb.substring(m.start()))
    }
}

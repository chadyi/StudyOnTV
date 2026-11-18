package com.studyontv.tv

/** Feed Chinese engines Chinese words, rather than relying on their ASCII/math phonemizer. */
object MandarinSpeech {
    private const val DIGITS = "零一二三四五六七八九"

    fun normalize(text: String, language: String): String {
        if (!language.startsWith("zh") && language !in listOf("cmn", "zho")) return text
        var value = text.map { if (it in '０'..'９') '0' + (it - '０') else it }.joinToString("")
        value = value.replace(Regex("\\\\(?:dfrac|tfrac|frac)\\s*\\{(\\d+)\\}\\s*\\{(\\d+)\\}")) {
            "${it.groupValues[2]}分之${it.groupValues[1]}"
        }
        value = value.replace("\\times", "乘以").replace("\\div", "除以")
            .replace("\\leq", "小于等于").replace("\\geq", "大于等于")
            .replace("\\neq", "不等于").replace("\\cdot", "乘以")
        // A trailing empty answer is a question, never a spoken English 'equals'.
        value = value.replace(Regex("\\s*[=＝]\\s*[?？_＿□]*\\s*$"), "等于多少")
        value = value.replace("!=", "不等于").replace("≠", "不等于")
            .replace("<=", "小于等于").replace("≤", "小于等于")
            .replace(">=", "大于等于").replace("≥", "大于等于")
        value = value.replace(Regex("(?<=[0-9)）])\\s*[-−－]\\s*(?=[0-9(（])"), "减")
        value = value.replace(Regex("(^|[(（=＝>＜<＞：:，,、\\s])[-−－]\\s*(?=\\d)")) { "${it.groupValues[1]}负" }
        value = value.replace(Regex("(?<=[0-9)）])\\s*[+＋]\\s*(?=[0-9(（负])"), "加")
        value = value.replace(Regex("(?<=[0-9)）])\\s*[×*＊]\\s*(?=[0-9(（负])"), "乘以")
        value = value.replace(Regex("(?<=[0-9)）])\\s*[÷/／]\\s*(?=[0-9(（负])"), "除以")
        value = value.replace("=", "等于").replace("＝", "等于")
            .replace("<", "小于").replace("＜", "小于").replace(">", "大于").replace("＞", "大于")
        value = value.replace(Regex("[（(]([0-9０-９加减乘除以负.\\s]+)[）)]")) { "左括号${it.groupValues[1]}右括号" }
        value = value.replace(Regex("(\\d+(?:\\.\\d+)?)\\s*[%％]")) { "百分之${it.groupValues[1]}" }
        return value.replace(Regex("\\d+(?:\\.\\d+)?")) {
            val parts = it.value.split('.')
            integer(parts[0]) + if (parts.size > 1) "点" + parts[1].map { digit -> DIGITS[digit - '0'] }.joinToString("") else ""
        }.trim()
    }

    private fun integer(raw: String): String {
        if (raw.length > 16 || (raw.length > 1 && raw[0] == '0')) return raw.map { DIGITS[it - '0'] }.joinToString("")
        var number = raw.toLong()
        if (number == 0L) return "零"
        val groups = ArrayList<Int>()
        while (number > 0) { groups.add((number % 10000).toInt()); number /= 10000 }
        val units = listOf("", "万", "亿", "兆")
        val result = StringBuilder()
        var gap = false
        for (i in groups.indices.reversed()) {
            val group = groups[i]
            if (group == 0) { if (result.isNotEmpty()) gap = true; continue }
            if (result.isNotEmpty() && (gap || group < 1000)) result.append('零')
            result.append(group(group)).append(units[i]); gap = false
        }
        return result.toString()
    }

    private fun group(number: Int): String {
        val result = StringBuilder()
        val places = listOf(1000 to "千", 100 to "百", 10 to "十", 1 to "")
        var zero = false
        for ((place, unit) in places) {
            val digit = number / place % 10
            if (digit == 0) { if (result.isNotEmpty()) zero = true; continue }
            if (zero) result.append('零')
            if (!(place == 10 && digit == 1 && result.isEmpty())) result.append(DIGITS[digit])
            result.append(unit); zero = false
        }
        return result.toString()
    }
}

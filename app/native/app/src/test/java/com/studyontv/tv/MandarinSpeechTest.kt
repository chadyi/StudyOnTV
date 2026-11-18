package com.studyontv.tv

import org.junit.Assert.assertEquals
import org.junit.Test

class MandarinSpeechTest {
    private fun read(text: String) = MandarinSpeech.normalize(text, "zh-CN")
    @Test fun emptyArithmeticAnswers() {
        assertEquals("十八减十八等于多少", read("18-18="))
        assertEquals("十八减十八等于多少", read("１８－１８＝？"))
        assertEquals("三加五等于多少", read("3 + 5 = ?"))
        assertEquals("十二除以三等于四", read("12÷3=4"))
        assertEquals("二乘以左括号三加四右括号", read("2×(3+4)"))
    }
    @Test fun negativesDecimalsAndFractions() {
        assertEquals("负四加二等于多少", read("-4 + 2 ="))
        assertEquals("三点一四", read("3.14"))
        assertEquals("零点零五", read("0.05"))
        assertEquals("百分之十二点五", read("12.5%"))
        assertEquals("二分之一", read("\\frac{1}{2}"))
    }
    @Test fun chineseNumberPlaces() {
        assertEquals("零，一，十，十一，二十，一百零一，一千零一十，一万零一，一亿零一", read("0，1，10，11，20，101，1010，10001，100000001"))
        assertEquals("零零七", read("007"))
    }
    @Test fun languageAndOrdinaryText() {
        assertEquals("小明有十八个苹果。", read("小明有18个苹果。"))
        assertEquals("18-18=", MandarinSpeech.normalize("18-18=", "en-US"))
        assertEquals("单词 A-B", read("单词 A-B"))
    }
}

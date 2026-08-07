package ru.queuejw.lumetro.components.utils

import net.sourceforge.pinyin4j.PinyinHelper
import net.sourceforge.pinyin4j.format.HanyuPinyinCaseType
import net.sourceforge.pinyin4j.format.HanyuPinyinOutputFormat
import net.sourceforge.pinyin4j.format.HanyuPinyinToneType
import net.sourceforge.pinyin4j.format.HanyuPinyinVCharType

object PinYinStringHelper {

    private val format = HanyuPinyinOutputFormat().apply {
        caseType = HanyuPinyinCaseType.UPPERCASE
        toneType = HanyuPinyinToneType.WITHOUT_TONE
        vCharType = HanyuPinyinVCharType.WITH_V
    }

    /**
     * 获取中文拼音首字母，例如："微信" -> "WX"
     * 返回大写字母字符串，非中文字符返回原字符的大写形式
     */
    fun getAlpha(str: String): String {
        if (str.isEmpty()) return ""
        
        val result = StringBuilder()
        for (char in str) {
            val charStr = char.toString()
            val pinyinArray = try {
                PinyinHelper.toHanyuPinyinStringArray(char, format)
            } catch (e: Exception) {
                null
            }
            
            if (pinyinArray != null && pinyinArray.isNotEmpty()) {
                // 中文字符，取首字母
                result.append(pinyinArray[0].first())
            } else {
                // 非中文字符，保留原字符（英文、数字等）
                result.append(char.uppercaseChar())
            }
        }
        return result.toString()
    }

    /**
     * 获取完整拼音，例如："微信" -> "WEIXIN"
     */
    fun getFullPinyin(str: String): String {
        if (str.isEmpty()) return ""
        
        val result = StringBuilder()
        for (char in str) {
            val charStr = char.toString()
            val pinyinArray = try {
                PinyinHelper.toHanyuPinyinStringArray(char, format)
            } catch (e: Exception) {
                null
            }
            
            if (pinyinArray != null && pinyinArray.isNotEmpty()) {
                result.append(pinyinArray[0])
            } else {
                result.append(char.uppercaseChar())
            }
        }
        return result.toString()
    }
}

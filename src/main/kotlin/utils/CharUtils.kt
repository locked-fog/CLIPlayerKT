package com.lockedfog.clip.utils

import java.lang.Character

object CharUtils {

    /**
     * 获取字符在终端显示的宽度
     * 返回 0: 不占位 (如零宽字符、组合标记)
     * 返回 1: 半角字符 (英文、数字)
     * 返回 2: 全角字符 (汉字、Emoji 等)
     */
    fun getCharWidth(c: Char): Int {
        if (c.code == 0) return 0

        // 1. 判断是否为不可见/零宽字符
        val type = Character.getType(c)
        if (type == Character.NON_SPACING_MARK.toInt() ||
            type == Character.ENCLOSING_MARK.toInt() ||
            type == Character.FORMAT.toInt()) {
            return 0
        }

        // 2. CJK 及全角符号判断
        if (isWideCharacter(c)) {
            return 2
        }

        // 3. 默认为半角
        return 1
    }

    private fun isWideCharacter(c: Char): Boolean {
        val ub = Character.UnicodeBlock.of(c) ?: return false

        // 包含主要的中日韩字符集、全角标点、注音符号等
        return ub == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
                ub == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A ||
                ub == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B ||
                ub == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_C ||
                ub == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_D ||
                ub == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS ||
                ub == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS_SUPPLEMENT ||
                ub == Character.UnicodeBlock.HIRAGANA ||
                ub == Character.UnicodeBlock.KATAKANA ||
                ub == Character.UnicodeBlock.HANGUL_SYLLABLES ||
                ub == Character.UnicodeBlock.HANGUL_JAMO ||
                ub == Character.UnicodeBlock.HANGUL_COMPATIBILITY_JAMO ||
                ub == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS || // 全角标点
                ub == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION ||
                ub == Character.UnicodeBlock.GENERAL_PUNCTUATION || // 部分标点可能是全角，视字体而定，这里简化处理
                ub == Character.UnicodeBlock.ENCLOSED_CJK_LETTERS_AND_MONTHS
    }
}
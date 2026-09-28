package com.mindtrace.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * NPC 台词排版归一化测试。
 * <p>
 * 钉住两件事：
 * <ol>
 *   <li><b>中文里的半角标点必须变全角</b> —— 实测 128 轮真实对话里有 2 轮漏出半角逗号
 *       （「可以拿出来,我帮你看」），一眼就像机器生成的文本，直接破坏角色感。
 *       提示词约束是概率性的，所以这里用代码保证。</li>
 *   <li><b>非中文语境的半角标点一个都不能动</b> —— 时间、小数、千分位、URL 里的
 *       {@code : , .} 是内容的一部分，改掉就是数据损坏。</li>
 * </ol>
 * 纯函数，无需 Mock。
 */
class TextStyleTest {

    @Test
    @DisplayName("中文里的半角逗号、冒号、问号、感叹号、分号都换成全角")
    void convertsHalfWidthPunctuationBetweenChinese() {
        assertEquals("可以拿出来，我帮你看合不合。",
                TextStyle.toFullWidthPunctuation("可以拿出来,我帮你看合不合。"));
        assertEquals("我的态度一直是：能分就分，分不了就存疑。",
                TextStyle.toFullWidthPunctuation("我的态度一直是:能分就分,分不了就存疑。"));
        assertEquals("你要问这个？", TextStyle.toFullWidthPunctuation("你要问这个?"));
        assertEquals("别乱说！", TextStyle.toFullWidthPunctuation("别乱说!"));
        assertEquals("报纸点过名；跟有罪是两码事。",
                TextStyle.toFullWidthPunctuation("报纸点过名;跟有罪是两码事。"));
    }

    @Test
    @DisplayName("已经是全角标点的原样保留（幂等）")
    void keepsFullWidthUntouched() {
        String text = "嗯……我的态度一直是：能分就分，分不了就存疑。";
        assertEquals(text, TextStyle.toFullWidthPunctuation(text));
        assertEquals(text, TextStyle.toFullWidthPunctuation(TextStyle.toFullWidthPunctuation(text)));
    }

    @Test
    @DisplayName("时间、小数、千分位、URL 里的半角标点一律不动")
    void doesNotTouchNonChineseContext() {
        assertEquals("12:30 左右", TextStyle.toFullWidthPunctuation("12:30 左右"));
        assertEquals("涨幅 1.5 倍", TextStyle.toFullWidthPunctuation("涨幅 1.5 倍"));
        assertEquals("1,000 美元", TextStyle.toFullWidthPunctuation("1,000 美元"));
        assertEquals("https://example.com/a?x=1", TextStyle.toFullWidthPunctuation("https://example.com/a?x=1"));
        assertEquals("案号 CASE-001/2:3", TextStyle.toFullWidthPunctuation("案号 CASE-001/2:3"));
    }

    @Test
    @DisplayName("真实漏出的两句必须被修掉")
    void fixesTheTwoRealLeaks() {
        // 2026-09-21 真实对话里漏出来的两句，原文存档在 artifacts/npc-voice-report-npc2.html
        assertEquals("你手上要是有对得上的具体线索，可以拿出来，我帮你看合不合。",
                TextStyle.toFullWidthPunctuation("你手上要是有对得上的具体线索，可以拿出来,我帮你看合不合。"));
        assertEquals("我要是指一个，那和那些报纸有什么区别。",
                TextStyle.toFullWidthPunctuation("我要是指一个,那和那些报纸有什么区别。"));
    }

    @Test
    @DisplayName("null 与空串安全返回")
    void handlesNullAndEmpty() {
        assertNull(TextStyle.toFullWidthPunctuation(null));
        assertEquals("", TextStyle.toFullWidthPunctuation(""));
    }

    @Test
    @DisplayName("英文句子里的半角标点不受影响")
    void leavesPureEnglishAlone() {
        String text = "He said, \"I don't know.\"";
        assertEquals(text, TextStyle.toFullWidthPunctuation(text));
    }
}

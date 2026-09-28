package com.mindtrace.agent;

/**
 * NPC 台词的排版归一化。
 *
 * <p>为什么要有这个类：提示词里已经写了「标点一律用中文全角」，但那是**概率性**约束 ——
 * 实测 128 轮真实对话里仍有 2 轮混进半角逗号（「可以拿出来,我帮你看」、
 * 「我要是指一个,那和那些报纸有什么区别」）。这种问题不需要让模型再努力一次：
 * 它是**确定性**的展示缺陷，在服务层做一次归一化就能 100% 消除。
 * <b>提示词负责「尽量写对」，这里负责「保证不错」。</b>
 *
 * <p>安全边界：**只在中日韩文字相邻时替换**。所以数字、时间、URL、代码里的半角标点
 * 一律原样保留 —— {@code "1.5"}、{@code "12:30"}、{@code "http://a.b"} 都不会被改动。
 *
 * <p>⚠️ 只可用于**纯文本**输出（NPC 对话）。JSON 模式的返回值里 {@code ,} 和 {@code :}
 * 是结构分隔符，对原始 JSON 串做替换会直接把报文改坏。
 */
final class TextStyle {

    private static final char[] HALF = {',', ':', '?', '!', ';'};
    private static final char[] FULL = {'，', '：', '？', '！', '；'};

    private TextStyle() {
    }

    /**
     * 把夹在中文里的半角标点换成全角；其余位置原样保留。
     *
     * @param text 模型返回的原始文本，可为 null
     * @return 归一化后的文本；入参为 null 时返回 null
     */
    static String toFullWidthPunctuation(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        StringBuilder builder = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char current = text.charAt(i);
            int index = indexOf(HALF, current);
            if (index >= 0 && touchesHan(text, i)) {
                builder.append(FULL[index]);
            } else {
                builder.append(current);
            }
        }
        return builder.toString();
    }

    /** 半角标点的左右邻居只要有一个是汉字，就说明它出现在中文语境里。 */
    private static boolean touchesHan(String text, int index) {
        return isHan(text, index - 1) || isHan(text, index + 1);
    }

    private static boolean isHan(String text, int index) {
        return index >= 0 && index < text.length()
                && Character.UnicodeScript.of(text.charAt(index)) == Character.UnicodeScript.HAN;
    }

    private static int indexOf(char[] pool, char target) {
        for (int i = 0; i < pool.length; i++) {
            if (pool[i] == target) {
                return i;
            }
        }
        return -1;
    }
}

package io.codex.haloaiassistant.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;

class ArticleToolMarkdownTest {

    @Test
    void rendersMarkdownBlockquoteAsHtmlBlockquote() throws Exception {
        String html = toReadableHtml("""
                # 标题
                > **引用**里的 `code`
                > 第二行

                普通段落 3 > 2
                """);

        assertEquals("""
                <h1>标题</h1><blockquote><strong>引用</strong>里的 <code>code</code><br>第二行</blockquote><p>普通段落 3 &gt; 2</p>""", html);
    }

    @Test
    void rendersCommonAutoOpsMarkdownWithoutLeakingSyntax() throws Exception {
        String html = toReadableHtml("""
                ## 技术前沿日报
                日期：2026-07-10 | 来源：巫师前沿站
                ---
                ### 头条新闻
                #### OpenAI 发布新工具
                **事实**：
                - OpenAI 发布 Codex 工具。[来源](arstechnica.com/ai/example)
                **判断**：值得持续观察。

                1. 第一条
                2. 第二条
                """);

        assertEquals("""
                <h2>技术前沿日报</h2><p>日期：2026-07-10 | 来源：巫师前沿站</p><hr><h3>头条新闻</h3><h4>OpenAI 发布新工具</h4><p><strong>事实</strong>：</p><ul><li>OpenAI 发布 Codex 工具。<a href="https://arstechnica.com/ai/example" target="_blank" rel="noopener noreferrer">来源</a></li></ul><p><strong>判断</strong>：值得持续观察。</p><ol><li>第一条</li><li>第二条</li></ol>""", html);
    }

    private static String toReadableHtml(String markdown) throws Exception {
        Method method = ArticleTool.CreateArticleTool.class.getDeclaredMethod("toReadableHtml", String.class);
        method.setAccessible(true);
        return (String) method.invoke(null, markdown);
    }
}

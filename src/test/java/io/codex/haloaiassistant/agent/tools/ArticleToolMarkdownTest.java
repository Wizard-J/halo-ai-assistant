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

    private static String toReadableHtml(String markdown) throws Exception {
        Method method = ArticleTool.CreateArticleTool.class.getDeclaredMethod("toReadableHtml", String.class);
        method.setAccessible(true);
        return (String) method.invoke(null, markdown);
    }
}

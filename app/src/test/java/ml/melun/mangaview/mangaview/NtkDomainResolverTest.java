package ml.melun.mangaview.mangaview;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class NtkDomainResolverTest {
    @Test
    public void parsesCurrentAddressFromTelegramMessage() {
        String html = "<div class=\"tgme_widget_message_text\">"
                + "뉴토끼 현재주소<br>"
                + "<a href=\"https://sbxh2.com\">sbxh2.com</a><br>"
                + "뉴토끼 주소 안내페이지<br>"
                + "<a href=\"https://xn--h10b90bi5zuhh79k.net\">안내</a>"
                + "</div>";

        assertEquals("https://sbxh2.com", NtkDomainResolver.parseLatestRoot(html));
    }

    @Test
    public void keepsNewestMatchingTelegramMessage() {
        String html = "<div class=\"tgme_widget_message_text\">뉴토끼 현재주소 "
                + "<a href=\"https://sbxh1.com\">old</a></div>"
                + "<div class=\"tgme_widget_message_text\">뉴토끼 현재주소 "
                + "<a href=\"https://sbxh3.com\">new</a></div>";

        assertEquals("https://sbxh3.com", NtkDomainResolver.parseLatestRoot(html));
    }
}

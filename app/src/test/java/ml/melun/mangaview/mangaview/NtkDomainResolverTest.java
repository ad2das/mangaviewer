package ml.melun.mangaview.mangaview;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class NtkDomainResolverTest {
    @Test
    public void parsesCurrentAddressFromTelegramMessage() {
        String html = "<div class=\"tgme_widget_message_text\">"
                + "\uB274\uD1A0\uB07C \uD604\uC7AC\uC8FC\uC18C<br>"
                + "<a href=\"https://sbxh2.com\">sbxh2.com</a><br>"
                + "\uB274\uD1A0\uB07C \uC8FC\uC18C \uC548\uB0B4\uD398\uC774\uC9C0<br>"
                + "<a href=\"https://xn--h10b90bi5zuhh79k.net\">\uC548\uB0B4</a>"
                + "</div>";

        assertEquals("https://sbxh2.com", NtkDomainResolver.parseLatestRoot(html));
    }

    @Test
    public void keepsNewestMatchingTelegramMessage() {
        String html = "<div class=\"tgme_widget_message_text\">\uB274\uD1A0\uB07C \uD604\uC7AC\uC8FC\uC18C "
                + "<a href=\"https://sbxh1.com\">old</a></div>"
                + "<div class=\"tgme_widget_message_text\">\uB274\uD1A0\uB07C \uCD5C\uC2E0 \uC8FC\uC18C "
                + "<a href=\"https://sbxh3.com\">new</a></div>";

        assertEquals("https://sbxh3.com", NtkDomainResolver.parseLatestRoot(html));
    }

    @Test
    public void acceptsLiveTelegramCurrentAddressFormat() {
        String html = "<div class=\"tgme_widget_message_text js-message_text\" dir=\"auto\">"
                + "5\uC6D4 11\uC77C\uBD80\uD130 \uC811\uC18D\uC774 \uC5B4\uB824\uC6B8\uC218 \uC788\uC73C\uB2C8 <br/>"
                + "\uC2E4\uC2DC\uAC04 \uC811\uC18D \uC8FC\uC18C\uB97C \uBC1B\uC544 \uBCF4\uC2DC\uB824\uBA74 "
                + "\uBCF8 \uD154\uB808\uADF8\uB7A8 \uCC44\uB110 \uAD6C\uB3C5 \uBD80\uD0C1\uB4DC\uB9BD\uB2C8\uB2E4.<br/><br/>"
                + "\uB274\uD1A0\uB07C \uD604\uC7AC\uC8FC\uC18C <br/>"
                + "<a href=\"https://sbxh1.com/\">https://sbxh1.com</a><br/><br/>"
                + "\uB274\uD1A0\uB07C \uC8FC\uC18C \uC548\uB0B4\uD398\uC774\uC9C0<br/>"
                + "<a href=\"https://%EB%89%B4%ED%86%A0%EB%81%BC%EC%A3%BC%EC%86%8C.net/\">https://\uB274\uD1A0\uB07C\uC8FC\uC18C.net</a>"
                + "</div>";

        assertEquals("https://sbxh1.com", NtkDomainResolver.parseLatestRoot(html));
    }
}

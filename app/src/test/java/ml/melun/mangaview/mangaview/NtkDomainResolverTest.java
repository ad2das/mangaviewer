package ml.melun.mangaview.mangaview;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

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

    @Test
    public void normalizesCommaInTelegramAddress() {
        String html = "<div class=\"tgme_widget_message_text\">"
                + "\uB274\uD1A0\uB07C \uD604\uC7AC\uC8FC\uC18C "
                + "<a href=\"https://sbxh1,com/\">https://sbxh1,com</a>"
                + "</div>";

        assertEquals("https://sbxh1.com", NtkDomainResolver.parseLatestRoot(html));
    }

    @Test
    public void parsesPlainTextTelegramAddressWithoutAnchor() {
        String html = "<div class=\"tgme_widget_message_text\">"
                + "\uB274\uD1A0\uB07C \uCD5C\uC2E0 \uC8FC\uC18C (https://sbxh10.com)."
                + "</div>";

        assertEquals("https://sbxh10.com", NtkDomainResolver.parseLatestRoot(html));
    }

    @Test
    public void acceptsAlternateNewtokiChannelFormat() {
        String html = "<div class=\"tgme_widget_message_text\">"
                + "\uB274\uD1A0\uB07C \uD604\uC7AC\uC8FC\uC18C <a href=\"https://Newtoki552.com/\">Newtoki552.com</a>"
                + "</div>";

        assertEquals("https://newtoki552.com", NtkDomainResolver.parseLatestRoot(html));
    }

    @Test
    public void keepsNewestNewtoCandidateFirst() {
        String html = "<div class=\"tgme_widget_message_text\">\uB274\uD1A0\uB07C \uD604\uC7AC\uC8FC\uC18C "
                + "<a href=\"https://sbxh1.com\">old</a></div>"
                + "<div class=\"tgme_widget_message_text\">\uB274\uD1A0\uB07C \uD604\uC7AC \uC8FC\uC18C "
                + "<a href=\"https://newto03.com/\">new</a></div>";

        assertEquals("https://newto03.com", NtkDomainResolver.parseLatestRoot(html));
        assertTrue(NtkDomainResolver.parseLatestRoots(html).contains("https://sbxh1.com"));
    }

    @Test
    public void acceptsUnexpectedOfficialAddressHost() {
        String html = "<div class=\"tgme_widget_message_text\">\uB274\uD1A0\uB07C \uD604\uC7AC\uC8FC\uC18C "
                + "<a href=\"https://odd-address.example/\">odd-address.example</a></div>";

        assertEquals("https://odd-address.example", NtkDomainResolver.parseLatestRoot(html));
    }

    @Test
    public void generatesLikelySbxhSuccessorsFromCurrentRoot() {
        List<String> roots = NtkDomainResolver.generatedSbxhRoots("https://sbxh9.com");

        assertEquals("https://sbxh12.com", roots.get(0));
        assertTrue(roots.contains("https://sbxh10.com"));
        assertTrue(roots.contains("https://sbxh9.com"));
        assertTrue(roots.contains("https://sbxh1.com"));
    }

    @Test
    public void findsAddressGuideLinksFromTelegramMessage() {
        String html = "<div class=\"tgme_widget_message_text\">"
                + "\uB274\uD1A0\uB07C \uD604\uC7AC\uC8FC\uC18C <a href=\"https://sbxh1.com\">sbxh1.com</a><br>"
                + "\uB274\uD1A0\uB07C \uC8FC\uC18C \uC548\uB0B4\uD398\uC774\uC9C0 "
                + "<a href=\"https://xn--h10b90bi5zuhh79k.net/\">\uB274\uD1A0\uB07C\uC8FC\uC18C.net</a>"
                + "</div>";

        List<String> guides = NtkDomainResolver.parseAddressGuideUrls(html);

        assertEquals("https://xn--h10b90bi5zuhh79k.net/", guides.get(0));
    }

    @Test
    public void parsesCurrentAddressFromAddressGuidePage() {
        String html = "<a href=\"https://ntk01.com\">\uB274\uD1A0\uB07C \uBC14\uB85C\uAC00\uAE30</a>"
                + "<p>\uCD5C\uADFC `ntk01.com` \uD604\uC7AC \uC8FC\uC18C</p>"
                + "<p>1\uC8FC \uC804 `newtoki469.com` \uCC28\uB2E8</p>";

        List<String> roots = NtkDomainResolver.parseAddressGuideRoots(html);

        assertEquals("https://ntk01.com", roots.get(0));
        assertTrue(roots.contains("https://newtoki469.com"));
    }
}

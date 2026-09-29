package com.ethlo.r7.status;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * The dashboard's script is allowed by hash, so the policy is only right if it hashes exactly
 * the bytes the browser will hash, and if nothing else on the page needs to run.
 */
class StatusHandlerCspTest
{
    @Test
    void thePolicyAllowsExactlyTheInlineScriptByItsHash() throws Exception
    {
        final String html = "<html><script>\n  run();\n</script></html>";
        final String expected = Base64.getEncoder().encodeToString(
                MessageDigest.getInstance("SHA-256").digest("\n  run();\n".getBytes(StandardCharsets.UTF_8)));

        assertThat(StatusHandler.contentSecurityPolicy(html))
                .contains("script-src 'sha256-" + expected + "'")
                .contains("default-src 'none'")
                .doesNotContain("script-src 'unsafe-inline'");
    }

    @Test
    void aSecondScriptBlockIsRefusedRatherThanSilentlyBlocked()
    {
        assertThatThrownBy(() -> StatusHandler.contentSecurityPolicy("<script>a()</script><script>b()</script>"))
                .isInstanceOf(IllegalStateException.class);
    }

    /**
     * Inline handlers are not covered by a script hash: one left in the page would silently do
     * nothing under the policy.
     */
    @Test
    void theShippedDashboardHasNoInlineHandlers() throws Exception
    {
        try (final InputStream in = StatusHandler.class.getResourceAsStream("/dashboard/default/page.html"))
        {
            final String html = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            // Any event-handler attribute, whatever its case or quoting: onclick="", onClick='', onload=x.
            assertThat(html).doesNotContainPattern(Pattern.compile("<[^>]*\\son[a-z]+\\s*=", Pattern.CASE_INSENSITIVE));
            assertThat(StatusHandler.contentSecurityPolicy(html)).contains("script-src 'sha256-");
        }
    }
}

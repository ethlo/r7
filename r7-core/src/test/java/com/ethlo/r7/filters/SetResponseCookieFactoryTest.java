package com.ethlo.r7.filters;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.ethlo.r7.validation.ValidationResult;

/**
 * A gateway-set cookie is typically a token: unless configured otherwise it is kept off
 * plaintext connections, away from script and out of cross-site requests.
 */
class SetResponseCookieFactoryTest
{
    private static SetResponseCookieFactory.Config config(final Boolean secure, final Boolean httpOnly, final String sameSite)
    {
        return new SetResponseCookieFactory.Config("session", "tok", null, null, null, secure, httpOnly, sameSite);
    }

    @Test
    void attributesDefaultToSafe()
    {
        final SetResponseCookieFactory.Config config = config(null, null, null);

        assertThat(config.secure()).isTrue();
        assertThat(config.httpOnly()).isTrue();
        assertThat(config.sameSite()).isEqualTo("Lax");
    }

    @Test
    void eachAttributeCanBeOptedOut()
    {
        final SetResponseCookieFactory.Config config = config(false, false, "Strict");

        assertThat(config.secure()).isFalse();
        assertThat(config.httpOnly()).isFalse();
        assertThat(config.sameSite()).isEqualTo("Strict");
    }

    @Test
    void sameSiteNoneWithoutSecureIsRefused()
    {
        final ValidationResult result = new ValidationResult();
        config(false, null, "None").validate(result);

        assertThat(result.getErrors()).singleElement().asString().contains("sameSite").contains("secure");
    }

    @Test
    void sameSiteNoneWithSecureIsAccepted()
    {
        final ValidationResult result = new ValidationResult();
        config(null, null, "None").validate(result);

        assertThat(result.getErrors()).isEmpty();
    }
}

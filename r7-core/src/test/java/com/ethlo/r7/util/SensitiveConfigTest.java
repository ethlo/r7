package com.ethlo.r7.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.ethlo.r7.filters.BasicAuthFactory;
import com.ethlo.r7.filters.InjectBasicAuthFactory;
import com.ethlo.r7.filters.SetRequestHeaderFactory;
import com.ethlo.r7.filters.StripPathPrefixFactory;
import com.ethlo.r7.journal.JournalSecurity;

class SensitiveConfigTest
{
    private static Object mask(final Class<?> configClass, final Object args)
    {
        return SensitiveConfig.mask(configClass, args, JournalSecurity.SAFE_REQUEST_HEADERS);
    }

    @Test
    void masksCredentialsButKeepsTheRest()
    {
        assertThat(mask(InjectBasicAuthFactory.Config.class, Map.of("username", "svc", "password", "s3cret")))
                .isEqualTo(Map.of("username", "svc", "password", SensitiveConfig.MASK));
        assertThat(mask(BasicAuthFactory.Config.class, Map.of("realm", "r", "users", List.of("alice:$2y$10$hash"))))
                .isEqualTo(Map.of("realm", "r", "users", SensitiveConfig.MASK));
    }

    @Test
    void showsAHeaderValueOnlyWhenTheJournalWouldToo()
    {
        assertThat(mask(SetRequestHeaderFactory.Config.class, Map.of("name", "Authorization", "value", "Bearer abc")))
                .isEqualTo(Map.of("name", "Authorization", "value", SensitiveConfig.MASK));
        assertThat(mask(SetRequestHeaderFactory.Config.class, Map.of("name", "X-Api-Key", "value", "k")))
                .isEqualTo(Map.of("name", "X-Api-Key", "value", SensitiveConfig.MASK));
        assertThat(mask(SetRequestHeaderFactory.Config.class, Map.of("name", "accept", "value", "application/json")))
                .isEqualTo(Map.of("name", "accept", "value", "application/json"));
    }

    @Test
    void leavesConfigWithoutSensitiveValuesUntouched()
    {
        final Map<String, Object> args = Map.of("parts", 1);
        assertThat(mask(StripPathPrefixFactory.Config.class, args)).isSameAs(args);
        assertThat(mask(InjectBasicAuthFactory.Config.class, null)).isNull();
    }

    @Test
    void convertsComponentNamesTheWayTheYamlMapperDoes()
    {
        assertThat(SensitiveConfig.snakeCase("maxSize")).isEqualTo("max_size");
        assertThat(SensitiveConfig.snakeCase("password")).isEqualTo("password");
    }

    @Test
    void masksAResponseCookieValue()
    {
        assertThat(mask(com.ethlo.r7.filters.SetResponseCookieFactory.Config.class, Map.of("name", "session", "value", "tok")))
                .isEqualTo(Map.of("name", "session", "value", SensitiveConfig.MASK));
    }

    @Test
    void redactsCredentialsInUrlsWhereverTheyAreRendered()
    {
        assertThat(SensitiveConfig.redactUrlCredentials("[TargetConfig[url=http://svc:p4ss@api:8080/x], TargetConfig[url=https://token@b]]"))
                .isEqualTo("[TargetConfig[url=http://******@api:8080/x], TargetConfig[url=https://******@b]]");
        assertThat(SensitiveConfig.redactUrlCredentials("http://api:8080/a@b")).isEqualTo("http://api:8080/a@b");
        assertThat(SensitiveConfig.redactUrlCredentials("http://api:8080")).isEqualTo("http://api:8080");
        assertThat(SensitiveConfig.redactUrlCredentials(null)).isNull();
    }
}

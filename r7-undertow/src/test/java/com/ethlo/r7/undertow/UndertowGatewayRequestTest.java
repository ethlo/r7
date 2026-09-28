package com.ethlo.r7.undertow;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class UndertowGatewayRequestTest
{
    @Test
    void recognisesOnlyALeadingSchemeAsAbsoluteForm()
    {
        assertThat(UndertowGatewayRequest.isAbsoluteForm("http://host/a")).isTrue();
        assertThat(UndertowGatewayRequest.isAbsoluteForm("HTTPS://host")).isTrue();
        assertThat(UndertowGatewayRequest.isAbsoluteForm("svn+ssh://host/a")).isTrue();

        assertThat(UndertowGatewayRequest.isAbsoluteForm("/redirect?next=http://example.test/a")).isFalse();
        assertThat(UndertowGatewayRequest.isAbsoluteForm("/foo://bar")).isFalse();
        assertThat(UndertowGatewayRequest.isAbsoluteForm("*")).isFalse();
        assertThat(UndertowGatewayRequest.isAbsoluteForm("")).isFalse();
        assertThat(UndertowGatewayRequest.isAbsoluteForm("mailto:a@b")).isFalse();
        assertThat(UndertowGatewayRequest.isAbsoluteForm("1http://host")).isFalse();
        assertThat(UndertowGatewayRequest.isAbsoluteForm("a b://host")).isFalse();
    }
}

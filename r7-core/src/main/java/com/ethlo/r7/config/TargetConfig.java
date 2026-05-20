package com.ethlo.r7.config;

import java.net.URI;
import java.util.StringJoiner;

import com.ethlo.r7.util.ValidatorUtils;
import com.ethlo.r7.validation.ValidatableConfig;
import com.ethlo.r7.validation.ValidationResult;

public record TargetConfig(String url) implements ValidatableConfig
{
    @Override
    public void validate(final ValidationResult result)
    {
        final ValidatorUtils validator = new ValidatorUtils(result).required("url", this.url());

        if (this.url() != null && !this.url().isBlank())
        {
            try
            {
                final URI uri = URI.create(this.url());

                if (uri.getScheme() == null || (!uri.getScheme().equalsIgnoreCase("http") && !uri.getScheme().equalsIgnoreCase("https")))
                {
                    validator.invalid("url", this.url(), "Target URL must begin with 'http://' or 'https://'");
                }

                if (uri.getHost() == null)
                {
                    validator.invalid("url", this.url(), "Target URL must contain a valid host");
                }
            }
            catch (final IllegalArgumentException e)
            {
                validator.invalid("url", this.url(), "Invalid URL format");
            }
        }
    }

    @Override
    public String toString()
    {
        return new StringJoiner(", ", TargetConfig.class.getSimpleName() + "[", "]")
                .add("url='" + this.url() + "'")
                .toString();
    }
}
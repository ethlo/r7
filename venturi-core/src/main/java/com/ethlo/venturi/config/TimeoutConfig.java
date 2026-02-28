package com.ethlo.venturi.config;

/**
 * Networking limits for the upstream proxy connection
 *
 * @param connect Maximum time to establish the TCP handshake
 * @param read    Maximum time to wait for a response from the target
 */
public record TimeoutConfig(
        int connect,
        int read
)
{
}
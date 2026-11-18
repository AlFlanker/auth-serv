package com.otr.portal.identity.token;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

@ConfigurationProperties("identity.token.registry")
public record TokenRegistryProperties(
        @DefaultValue("5s") Duration transactionTimeout,
        @DefaultValue("500") int purgeBatchSize
) {
    public TokenRegistryProperties {
        if (transactionTimeout.isNegative() || transactionTimeout.isZero() || transactionTimeout.getNano() != 0
                || transactionTimeout.toSeconds() > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("transaction-timeout must be a positive whole number of seconds");
        }
        if (purgeBatchSize < 1 || purgeBatchSize > 10000) {
            throw new IllegalArgumentException("purge-batch-size must be between 1 and 10000");
        }
    }
}

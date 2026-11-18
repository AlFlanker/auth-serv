package com.otr.portal.config.source.etcd;

import com.otr.portal.config.source.ConfigSource;
import com.otr.portal.config.source.autoconfigure.ConfigSourceAutoConfiguration;
import com.otr.portal.config.source.cache.CachingConfigSource;
import com.otr.portal.config.source.cache.ConfigCacheProperties;
import com.otr.portal.etcd.autoconfigure.EtcdClientAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ConfigSourceAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    EtcdClientAutoConfiguration.class,
                    ConfigSourceAutoConfiguration.class
            ));

    @Test
    void shouldWrapInCachingConfigSourceByDefault() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(ConfigSource.class);
            assertThat(context.getBean(ConfigSource.class)).isInstanceOf(CachingConfigSource.class);
        });
    }

    @Test
    void shouldUseRawEtcdConfigSourceWhenCacheDisabled() {
        runner.withPropertyValues("config.source.cache.enabled=false")
                .run(context -> {
                    assertThat(context).hasSingleBean(ConfigSource.class);
                    assertThat(context.getBean(ConfigSource.class)).isInstanceOf(EtcdConfigSource.class);
                });
    }

    @Test
    void shouldBindEtcdProperties() {
        runner.withPropertyValues(
                        "config.source.etcd.key-prefix=nav/",
                        "config.source.etcd.timeout=5s",
                        "config.source.etcd.watch-require-leader=false"
                )
                .run(context -> {
                    EtcdConfigProperties properties = context.getBean(EtcdConfigProperties.class);
                    assertThat(properties.getKeyPrefix()).isEqualTo("nav/");
                    assertThat(properties.getTimeout()).isEqualTo(Duration.ofSeconds(5));
                    assertThat(properties.isWatchRequireLeader()).isFalse();
                });
    }

    @Test
    void shouldBindCacheProperties() {
        runner.withPropertyValues(
                        "config.source.cache.watch-prefix=trees/",
                        "config.source.cache.maximum-size=500",
                        "config.source.cache.expire-after-write=2m",
                        "config.source.cache.watch-retry-backoff=1s",
                        "config.source.cache.watch-retry-max-backoff=30s"
                )
                .run(context -> {
                    ConfigCacheProperties properties = context.getBean(ConfigCacheProperties.class);
                    assertThat(properties.getWatchPrefix()).isEqualTo("trees/");
                    assertThat(properties.getMaximumSize()).isEqualTo(500L);
                    assertThat(properties.getExpireAfterWrite()).isEqualTo(Duration.ofMinutes(2));
                    assertThat(properties.getWatchRetryBackoff()).isEqualTo(Duration.ofSeconds(1));
                    assertThat(properties.getWatchRetryMaxBackoff()).isEqualTo(Duration.ofSeconds(30));
                });
    }

    @Test
    void shouldBackOffWhenCustomConfigSourceDefined() {
        runner.withBean("customConfigSource", ConfigSource.class, () -> mock(ConfigSource.class))
                .run(context -> {
                    assertThat(context).hasSingleBean(ConfigSource.class);
                    assertThat(context.getBean(ConfigSource.class)).isNotInstanceOf(CachingConfigSource.class);
                    assertThat(context.getBean(ConfigSource.class)).isNotInstanceOf(EtcdConfigSource.class);
                });
    }
}

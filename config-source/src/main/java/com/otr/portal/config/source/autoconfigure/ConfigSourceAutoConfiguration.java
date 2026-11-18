package com.otr.portal.config.source.autoconfigure;

import com.otr.portal.config.source.ConfigKey;
import com.otr.portal.config.source.ConfigSource;
import com.otr.portal.config.source.WatchableConfigSource;
import com.otr.portal.config.source.cache.CachingConfigSource;
import com.otr.portal.config.source.cache.ConfigCacheProperties;
import com.otr.portal.config.source.etcd.EtcdConfigProperties;
import com.otr.portal.config.source.etcd.EtcdConfigSource;
import com.otr.portal.etcd.ReactiveEtcdClient;
import com.otr.portal.etcd.autoconfigure.EtcdClientAutoConfiguration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Автоконфигурация read-only источника данных.
 * <p>
 * По умолчанию поднимает ETCD-реализацию {@link EtcdConfigSource} и, если включено кэширование
 * ({@code config.source.cache.enabled=true}, по умолчанию), оборачивает её в {@link CachingConfigSource}
 * (гибрид pull-кэш + push-watch).
 * <p>
 * Бины {@link ConfigKey}, объявленные приложением
 *
 * @author alexraspopin
 */
@AutoConfiguration(after = EtcdClientAutoConfiguration.class)
@EnableConfigurationProperties({EtcdConfigProperties.class, ConfigCacheProperties.class})
public class ConfigSourceAutoConfiguration {

    @Bean
    @ConditionalOnBean(ReactiveEtcdClient.class)
    @ConditionalOnMissingBean(ConfigSource.class)
    public WatchableConfigSource configSource(
            ReactiveEtcdClient reactiveEtcdClient,
            EtcdConfigProperties etcdProperties,
            ConfigCacheProperties cacheProperties,
            ObjectProvider<ConfigKey<?>> trackedKeys
    ) {
        EtcdConfigSource source = new EtcdConfigSource(reactiveEtcdClient, etcdProperties);
        if (!cacheProperties.isEnabled()) {
            return source;
        }
        return new CachingConfigSource(source, cacheProperties, trackedKeys.orderedStream().toList());
    }
}

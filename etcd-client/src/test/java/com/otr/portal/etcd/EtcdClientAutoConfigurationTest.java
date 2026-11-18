package com.otr.portal.etcd;

import com.otr.portal.etcd.autoconfigure.EtcdClientAutoConfiguration;
import io.etcd.jetcd.Client;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class EtcdClientAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(EtcdClientAutoConfiguration.class));

    @Test
    void shouldCreateClientAndReactiveWrapperBeans() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(Client.class);
            assertThat(context).hasSingleBean(ReactiveEtcdClient.class);
        });
    }

    @Test
    void shouldBindEndpointsFromProperties() {
        runner.withPropertyValues("etcd.endpoints=http://etcd-1:2379,http://etcd-2:2379")
                .run(context -> {
                    EtcdProperties properties = context.getBean(EtcdProperties.class);
                    assertThat(properties.getEndpoints())
                            .containsExactly("http://etcd-1:2379", "http://etcd-2:2379");
                });
    }

    @Test
    void shouldUseDefaultEndpointWhenNotConfigured() {
        runner.run(context -> {
            EtcdProperties properties = context.getBean(EtcdProperties.class);
            assertThat(properties.getEndpoints()).containsExactly("http://127.0.0.1:2379");
            assertThat(properties.getStartup().isVerifyConnection()).isFalse();
        });
    }

    @Test
    void shouldBackOffWhenClientBeanAlreadyDefined() {
        runner.withBean(Client.class, () -> Client.builder().endpoints("http://custom:2379").build())
                .run(context -> assertThat(context).hasSingleBean(Client.class));
    }
}

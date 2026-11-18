package com.otr.portal.identity.health;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.micrometer.tracing.opentelemetry.autoconfigure.otlp.OtlpTracingAutoConfiguration;
import org.springframework.boot.micrometer.tracing.opentelemetry.autoconfigure.otlp.OtlpTracingConnectionDetails;
import org.springframework.boot.micrometer.tracing.opentelemetry.autoconfigure.otlp.Transport;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class OtlpExportConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withConfiguration(AutoConfigurations.of(OtlpTracingAutoConfiguration.class));

    @Test
    void exportingToAnOtlpCollectorRequiresExplicitEnablement() {
        runner.run(context -> assertThat(context).doesNotHaveBean("otlpGrpcSpanExporter"));
        runner.withPropertyValues(
                "management.tracing.export.otlp.enabled=true",
                "management.opentelemetry.tracing.export.otlp.endpoint=http://localhost:4321")
                .run(context -> {
                    assertThat(context).hasBean("otlpGrpcSpanExporter");
                    var target = context.getBean(OtlpTracingConnectionDetails.class);
                    assertThat(target.getUrl(Transport.GRPC)).isEqualTo("http://localhost:4321");
                });
    }
}

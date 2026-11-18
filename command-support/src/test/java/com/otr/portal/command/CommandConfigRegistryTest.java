package com.otr.portal.command;

import com.otr.portal.command.config.CommandProperties;
import com.otr.portal.command.model.CommandContext;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class CommandConfigRegistryTest {

    private final BulkheadRegistry bulkheads = BulkheadRegistry.ofDefaults();
    private final TimeLimiterRegistry limiters = TimeLimiterRegistry.ofDefaults();
    private final CommandConfigRegistry registry = new CommandConfigRegistry(bulkheads, limiters,
            CircuitBreakerRegistry.ofDefaults(), RetryRegistry.ofDefaults());

    @Test
    void changedSettingsApplyToNewCallsAndKeepBreakerState() {
        var first = registry.getOrCreate(context(2, Duration.ofSeconds(3)));
        assertThat(registry.getOrCreate(context(2, Duration.ofSeconds(3)))).isSameAs(first);
        first.getCircuitBreaker().transitionToOpenState();
        assertThat(first.getBulkhead().tryAcquirePermission()).isTrue();
        assertThat(first.getBulkhead().tryAcquirePermission()).isTrue();

        var reduced = registry.getOrCreate(context(1, Duration.ofSeconds(3)));
        assertThat(reduced.getTimeLimiter()).isSameAs(first.getTimeLimiter());
        assertThat(reduced.getBulkhead().tryAcquirePermission()).isTrue();
        assertThat(reduced.getBulkhead().tryAcquirePermission()).isFalse();
        first.getBulkhead().onComplete();
        first.getBulkhead().onComplete();
        assertThat(reduced.getBulkhead().tryAcquirePermission()).isFalse();

        var changed = registry.getOrCreate(context(1, Duration.ofSeconds(8)));
        assertThat(changed.getBulkhead()).isSameAs(reduced.getBulkhead());
        assertThat(changed.getTimeLimiter().getTimeLimiterConfig().getTimeoutDuration()).isEqualTo(Duration.ofSeconds(8));
        assertThat(changed.getCircuitBreaker()).isSameAs(first.getCircuitBreaker());
        assertThat(changed.getRetry()).isSameAs(first.getRetry());
        assertThat(changed.getCircuitBreaker().tryAcquirePermission()).isFalse();
    }

    @Test
    void removingOverridesRestoresRegistryDefaults() {
        registry.getOrCreate(context(1, Duration.ofSeconds(12)));
        var restored = registry.getOrCreate(context(0, null));

        assertThat(restored.getTimeLimiter().getTimeLimiterConfig()).isSameAs(limiters.getDefaultConfig());
        assertThat(restored.getBulkhead().getBulkheadConfig()).isSameAs(bulkheads.getDefaultConfig());
        assertThat(limiters.timeLimiter("userDetails")).isSameAs(restored.getTimeLimiter());
        assertThat(bulkheads.bulkhead("userDetails")).isSameAs(restored.getBulkhead());
    }

    private CommandContext context(int concurrency, Duration timeout) {
        return CommandContext.builder().commandName("userDetails").properties(CommandProperties.builder()
                .maxConcurrency(concurrency).timeout(timeout).build()).build();
    }
}

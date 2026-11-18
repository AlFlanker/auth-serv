package com.otr.portal.command;

import com.otr.portal.command.model.CommandContext;
import com.otr.portal.command.model.CommandResilienceHolder;
import io.github.resilience4j.reactor.bulkhead.operator.BulkheadOperator;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import io.github.resilience4j.reactor.retry.RetryOperator;
import io.github.resilience4j.reactor.timelimiter.TimeLimiterOperator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Декоратор: навешивает обвязку Resilience4j (TimeLimiter, Bulkhead, CircuitBreaker, Retry)
 * на реактивный вызов {@link Mono}.
 * <p>
 * Порядок операторов (снаружи внутрь): {@code Retry -> CircuitBreaker -> TimeLimiter -> Bulkhead} —
 * дефолтный для Resilience4j. Каждая retry-попытка проходит через CircuitBreaker:
 * проверяет его состояние и регистрирует в нём свой сбой. {@code CallNotPermittedException}
 * не ретраится (исключён в конфигурации Retry).
 *
 * @author alexraspopin
 * @since 04.02.2026
 */
@Component
@RequiredArgsConstructor
public class CommandResilienceDecorator {
    private final CommandConfigRegistry commandConfigRegistry;

    /**
     * Гарантирует наличие конфига Resilience4j и декорирует Mono.
     * <p>
     * Новые вызовы получают лимиты из своего контекста. Уже начатый вызов завершится
     * со своим набором операторов, в том числе при смене настроек в etcd.
     *
     * @return декорированный Mono
     */
    <T> Mono<T> decorate(Mono<T> action, CommandContext commandContext) {
        var holder = commandConfigRegistry.getOrCreate(commandContext);
        return applyOperators(action, holder);
    }

    private <T> Mono<T> applyOperators(Mono<T> action, CommandResilienceHolder holder) {
        return action
                .transformDeferred(BulkheadOperator.of(holder.getBulkhead()))
                .transformDeferred(TimeLimiterOperator.of(holder.getTimeLimiter()))
                .transformDeferred(CircuitBreakerOperator.of(holder.getCircuitBreaker()))
                .transformDeferred(RetryOperator.of(holder.getRetry()));
    }

}

package com.otr.portal.command;

import com.otr.portal.command.config.CommandProperties;
import com.otr.portal.command.model.CommandContext;
import com.otr.portal.command.model.CommandResilienceHolder;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Централизованный реестр конфигураций Resilience4j.
 * По имени команды выдаёт {@link CommandResilienceHolder} с Bulkhead, TimeLimiter, CircuitBreaker и Retry
 * для декорирования вызовов.
 *
 * @author alexraspopin
 * @since 04.02.2026
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CommandConfigRegistry implements CommandResilienceEvictor {
    private final BulkheadRegistry bulkheadRegistry;
    private final TimeLimiterRegistry timeLimiterRegistry;
    private final CircuitBreakerRegistry circuitBreakerRegistry;
    private final RetryRegistry retryRegistry;

    private record CachedCommand(CommandProperties properties, CommandResilienceHolder holder) {}

    private final Map<String, CachedCommand> commandConfigs = new ConcurrentHashMap<>();

    CommandResilienceHolder getOrCreate(CommandContext context) {
        var cached = commandConfigs.get(context.getCommandName());
        if (cached != null && cached.properties().equals(context.getProperties())) {
            return cached.holder();
        }
        return refresh(context.getCommandName(), context.getProperties());
    }

    private synchronized CommandResilienceHolder refresh(String name, CommandProperties properties) {
        var previous = commandConfigs.get(name);
        if (previous != null && previous.properties().equals(properties)) {
            return previous.holder();
        }
        var bulkhead = previous != null && previous.properties().getMaxConcurrency() == properties.getMaxConcurrency()
                ? previous.holder().getBulkhead()
                : resolveBulkhead(name, properties, previous != null);
        var limiter = previous != null && Objects.equals(previous.properties().getTimeout(), properties.getTimeout())
                ? previous.holder().getTimeLimiter()
                : resolveTimeLimiter(name, properties, previous != null);
        var holder = CommandResilienceHolder.builder()
                .bulkhead(bulkhead)
                .timeLimiter(limiter)
                .circuitBreaker(circuitBreakerRegistry.circuitBreaker(name))
                .retry(retryRegistry.retry(name))
                .build();
        commandConfigs.put(name, new CachedCommand(properties, holder));
        return holder;
    }

    /**
     * Очищает весь реестр: все конфиги команд и все записи в Resilience4j-реестрах
     * (Bulkhead, TimeLimiter, CircuitBreaker, Retry).
     */
    public synchronized void clearAllCaches() {
        commandConfigs.clear();

        bulkheadRegistry.getAllBulkheads()
                .forEach(bulkhead -> bulkheadRegistry.remove(bulkhead.getName()));
        timeLimiterRegistry.getAllTimeLimiters()
                .forEach(timeLimiter -> timeLimiterRegistry.remove(timeLimiter.getName()));
        circuitBreakerRegistry.getAllCircuitBreakers()
                .forEach(cb -> circuitBreakerRegistry.remove(cb.getName()));
        retryRegistry.getAllRetries()
                .forEach(retry -> retryRegistry.remove(retry.getName()));

        log.info("Resilience4j регистр очищен");
    }

    /**
     * Удаляет конфиг и записи Resilience4j только для команды начинающихся с <b>prefix</b>.
     *
     * @param prefix имя команды
     */
    @Override
    public synchronized void evictByCommandPrefix(String prefix) {
        commandConfigs.keySet()
                .stream()
                .filter(command ->
                        command.startsWith(prefix))
                .forEach(commandConfigs::remove);


        bulkheadRegistry.getAllBulkheads().stream()
                .filter(bulkhead -> bulkhead.getName().startsWith(prefix))
                .forEach(bulkhead -> bulkheadRegistry.remove(bulkhead.getName()));
        timeLimiterRegistry.getAllTimeLimiters().stream()
                .filter(timeLimiter -> timeLimiter.getName().startsWith(prefix))
                .forEach(timeLimiter -> timeLimiterRegistry.remove(timeLimiter.getName()));
        circuitBreakerRegistry.getAllCircuitBreakers().stream()
                .filter(cb -> cb.getName().startsWith(prefix))
                .forEach(cb -> circuitBreakerRegistry.remove(cb.getName()));
        retryRegistry.getAllRetries().stream()
                .filter(retry -> retry.getName().startsWith(prefix))
                .forEach(retry -> retryRegistry.remove(retry.getName()));

        log.info("Resilience4j для команд(-ы) \"{}\" регистр очищен", prefix);
    }

    private Bulkhead resolveBulkhead(String commandKey, CommandProperties properties, boolean replacing) {
        if (replacing || properties.getMaxConcurrency() > 0) {
            bulkheadRegistry.remove(commandKey);
        }
        if (properties.getMaxConcurrency() > 0) {
            BulkheadConfig config = BulkheadConfig.custom()
                    .maxConcurrentCalls(properties.getMaxConcurrency())
                    .maxWaitDuration(Duration.ZERO)
                    .build();
            return bulkheadRegistry.bulkhead(commandKey, config);
        }

        return bulkheadRegistry.bulkhead(commandKey);
    }

    private TimeLimiter resolveTimeLimiter(String commandKey, CommandProperties properties, boolean replacing) {
        if (replacing || properties.getTimeout() != null) {
            timeLimiterRegistry.remove(commandKey);
        }
        if (properties.getTimeout() != null) {
            TimeLimiterConfig config = TimeLimiterConfig.custom()
                    .timeoutDuration(properties.getTimeout())
                    .cancelRunningFuture(true)
                    .build();
            return timeLimiterRegistry.timeLimiter(commandKey, config);
        }

        return timeLimiterRegistry.timeLimiter(commandKey);
    }

}

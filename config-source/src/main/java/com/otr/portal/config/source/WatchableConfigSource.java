package com.otr.portal.config.source;

import reactor.core.Disposable;
import reactor.core.publisher.ConnectableFlux;
import reactor.core.publisher.Flux;

import java.util.function.Function;

/**
 * Расширение {@link ConfigSource}, позволяющее подписаться на изменения (push-модель).
 * Даёт проактивную реакцию на изменения в хранилище в дополнение к обычному чтению.
 *
 * @author alexraspopin
 */
public interface WatchableConfigSource extends ConfigSource {

    /**
     * Поток изменений по префиксу логического ключа.
     * "Горячий" и бесконечный: эмитит события по мере их появления в хранилище.
     * <p>
     * Источники, умеющие воспроизводить историю изменений с зафиксированного момента,
     * первым событием эмитят {@link ConfigChangeType#SYNC}: все изменения после него
     * гарантированно попадут в поток, даже если случились до фактического установления
     * подписки в хранилище. Потребитель, читающий значения после SYNC, может быть уверен,
     * что не пропустит их обновления (закрывает гонку load-before-watch).
     *
     * @param keyPrefix префикс логического ключа
     * @return поток изменений
     */
    Flux<ConfigChange> watch(String keyPrefix);

    /**
     * Поток изменений одного конкретного ключа (точное совпадение, а не префикс:
     * события соседних ключей вида {@code key + "..."} отфильтровываются,
     * инфраструктурный {@link ConfigChangeType#SYNC} тоже).
     *
     * @param key логический ключ
     * @return поток изменений ключа (PUT и DELETE)
     */
    default Flux<ConfigChange> watchKey(String key) {
        return watch(key)
                .filter(change ->
                        change.type() != ConfigChangeType.SYNC && change.key().equals(key));
    }

    /**
     * Поток изменений ключа по типизированному дескриптору.
     */
    default Flux<ConfigChange> watchKey(ConfigKey<?> key) {
        return watchKey(key.key());
    }

    /**
     * Текущее значение ключа и все последующие его обновления, распарсенные в объект.
     * Эмитит начальное значение (если ключ существует), затем новое значение после каждого PUT.
     * DELETE не эмитится — если важно реагировать на удаление, используйте {@link #watchKey(String)}.
     * <p>
     * Обновления, пришедшие во время чтения начального значения, не теряются: они буферизуются
     * (последнее актуальное — промежуточные состояния устаревают) и доставляются следом за начальным.
     * Из-за этого при гонке подписки с конкурентным обновлением возможен дубликат значения,
     * поэтому обработчик должен быть идемпотентен.
     * Ошибка парсера завершает поток ошибкой — подписчик сам решает, ретраить или остановиться.
     *
     * @param key    логический ключ
     * @param type   класс результата (см. контракт {@link ConfigSource#get(String, Class, Function)})
     * @param parser детерминированный парсер строки в объект
     * @return "горячий" бесконечный поток актуальных значений ключа
     */
    default <T> Flux<T> watchValue(String key, Class<T> type, Function<String, T> parser) {
        return Flux.defer(() -> {
            ConnectableFlux<T> updates = watchKey(key)
                    .filter(change -> change.type() == ConfigChangeType.PUT && change.value() != null)
                    .map(change -> parser.apply(change.value()))
                    .replay(1);
            Disposable connection = updates.connect();
            return Flux.concat(get(key, type, parser), updates)
                    .doFinally(signal -> connection.dispose());
        });
    }

    /**
     * Текущее значение ключа и все его обновления по типизированному дескриптору.
     */
    default <T> Flux<T> watchValue(ConfigKey<T> key) {
        return watchValue(key.key(), key.type(), key.parser());
    }

    /**
     * Текущее значение ключа как строка и все последующие его обновления.
     */
    default Flux<String> watchValue(String key) {
        return watchValue(key, String.class, Function.identity());
    }
}

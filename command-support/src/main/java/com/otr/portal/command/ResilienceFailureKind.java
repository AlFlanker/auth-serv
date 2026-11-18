package com.otr.portal.command;

/**
 * Вид сбоя отказоустойчивой команды. Имена совпадают с контрактом API навигации.
 */
public enum ResilienceFailureKind {
    TIMEOUT,
    CIRCUIT_OPEN,
    BULKHEAD_FULL,
    RETRY_EXHAUSTED,
    OTHER
}

package com.otr.portal.etcd;

import io.etcd.jetcd.ByteSequence;
import io.etcd.jetcd.KV;
import io.etcd.jetcd.Txn;
import io.etcd.jetcd.kv.CompactResponse;
import io.etcd.jetcd.kv.DeleteResponse;
import io.etcd.jetcd.kv.GetResponse;
import io.etcd.jetcd.kv.PutResponse;
import io.etcd.jetcd.options.CompactOption;
import io.etcd.jetcd.options.DeleteOption;
import io.etcd.jetcd.options.GetOption;
import io.etcd.jetcd.options.PutOption;
import io.etcd.jetcd.options.TxnOption;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.trace.Tracer;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static io.opentelemetry.api.common.AttributeKey.longKey;
import static io.opentelemetry.api.common.AttributeKey.stringKey;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.springframework.util.StringUtils.hasText;

/**
 * Декоратор над {@link KV}, добавляющий трассировку OpenTelemetry к операциям key-value в ETCD.
 *
 * @author alexraspopin
 * @since 15.05.2026
 */
class TracedEtcdKV implements KV {
    private final KV delegate;
    private final Tracer tracer;
    private final List<String> endpoints;

    TracedEtcdKV(KV delegate, Tracer tracer, List<String> endpoints) {
        this.delegate = delegate;
        this.tracer = tracer;
        this.endpoints = endpoints;
    }

    @Override
    public CompletableFuture<PutResponse> put(ByteSequence key, ByteSequence value) {
        return EtcdTracingSupport.traceFuture(
                tracer,
                "etcd put",
                attributes("put", key),
                () -> delegate.put(key, value)
        );
    }

    @Override
    public CompletableFuture<PutResponse> put(ByteSequence key, ByteSequence value, PutOption option) {
        return EtcdTracingSupport.traceFuture(
                tracer,
                "etcd put",
                attributes("put", key),
                () -> delegate.put(key, value, option)
        );
    }

    @Override
    public CompletableFuture<GetResponse> get(ByteSequence key) {
        return EtcdTracingSupport.traceFuture(
                tracer,
                "etcd get",
                attributes("get", key),
                () -> delegate.get(key)
        );
    }

    @Override
    public CompletableFuture<GetResponse> get(ByteSequence key, GetOption option) {
        return EtcdTracingSupport.traceFuture(
                tracer,
                "etcd get",
                attributes("get", key),
                () -> delegate.get(key, option)
        );
    }

    @Override
    public CompletableFuture<DeleteResponse> delete(ByteSequence key) {
        return EtcdTracingSupport.traceFuture(
                tracer,
                "etcd delete",
                attributes("delete", key),
                () -> delegate.delete(key)
        );
    }

    @Override
    public CompletableFuture<DeleteResponse> delete(ByteSequence key, DeleteOption option) {
        return EtcdTracingSupport.traceFuture(
                tracer,
                "etcd delete",
                attributes("delete", key),
                () -> delegate.delete(key, option)
        );
    }

    @Override
    public CompletableFuture<CompactResponse> compact(long rev) {
        return EtcdTracingSupport.traceFuture(
                tracer,
                "etcd compact",
                baseAttributes("compact")
                        .put(longKey("etcd.revision"), rev)
                        .build(),
                () -> delegate.compact(rev)
        );
    }

    @Override
    public CompletableFuture<CompactResponse> compact(long rev, CompactOption option) {
        return EtcdTracingSupport.traceFuture(
                tracer,
                "etcd compact",
                baseAttributes("compact")
                        .put(longKey("etcd.revision"), rev)
                        .build(),
                () -> delegate.compact(rev, option)
        );
    }

    @Override
    public Txn txn() {
        return new TracedTxn(this.delegate.txn(), tracer, endpoints);
    }

    @Override
    public Txn txn(TxnOption option) {
        return new TracedTxn(this.delegate.txn(option), tracer, endpoints);
    }

    @Override
    public void close() {
        delegate.close();
    }

    private Attributes attributes(String operation, ByteSequence key) {
        return baseAttributes(operation)
                .put(longKey("etcd.key.length"), key.size())
                .put(stringKey("etcd.key.prefix"), safePrefix(key))
                .build();
    }

    private AttributesBuilder baseAttributes(String operation) {
        return Attributes.builder()
                .put(stringKey("db.system.name"), "etcd")
                .put(stringKey("db.operation.name"), operation)
                .put(stringKey("server.address"), String.join(",", endpoints));
    }

    private static String safePrefix(ByteSequence key) {
        var value = key.toString(UTF_8);

        if (!hasText(value)) {
            return "";
        }

        int secondSlash = value.indexOf('/', 1);
        if (secondSlash > 0) {
            return value.substring(0, secondSlash);
        }

        return value.length() <= 32
                ? value
                : value.substring(0, 32);
    }
}

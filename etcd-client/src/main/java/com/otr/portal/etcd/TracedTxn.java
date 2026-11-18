package com.otr.portal.etcd;

import io.etcd.jetcd.Txn;
import io.etcd.jetcd.kv.TxnResponse;
import io.etcd.jetcd.op.Cmp;
import io.etcd.jetcd.op.Op;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static io.opentelemetry.api.common.AttributeKey.longKey;
import static io.opentelemetry.api.common.AttributeKey.stringKey;

/**
 * Декоратор над {@link Txn}, добавляющий трассировку OpenTelemetry к транзакциям в ETCD.
 *
 * @author alexraspopin
 * @since 15.05.2026
 */
class TracedTxn implements Txn {
    private final Txn delegate;
    private final Tracer tracer;
    private final String endpoints;

    private long compareCount;

    private long thenCount;
    private long thenPutCount;
    private long thenGetCount;
    private long thenDeleteCount;
    private long thenTxnCount;

    private long elseCount;
    private long elsePutCount;
    private long elseGetCount;
    private long elseDeleteCount;
    private long elseTxnCount;

    TracedTxn(Txn delegate, Tracer tracer, List<String> endpoints) {
        this.delegate = delegate;
        this.tracer = tracer;
        this.endpoints = String.join(",", endpoints);
    }

    @Override
    public Txn If(Cmp... cmps) {
        delegate.If(cmps);

        if (cmps != null) {
            compareCount += cmps.length;
        }

        return this;
    }

    @Override
    public Txn Then(Op... ops) {
        delegate.Then(ops);

        OpCounts counts = countOps(ops);
        thenCount += counts.total;
        thenPutCount += counts.put;
        thenGetCount += counts.get;
        thenDeleteCount += counts.delete;
        thenTxnCount += counts.txn;

        return this;
    }

    @Override
    public Txn Else(Op... ops) {
        delegate.Else(ops);

        OpCounts counts = countOps(ops);
        elseCount += counts.total;
        elsePutCount += counts.put;
        elseGetCount += counts.get;
        elseDeleteCount += counts.delete;
        elseTxnCount += counts.txn;

        return this;
    }

    @Override
    public CompletableFuture<TxnResponse> commit() {
        return EtcdTracingSupport.traceFuture(
                tracer,
                "etcd txn",
                attributes(),
                delegate::commit,
                this::onSuccess
        );
    }

    private void onSuccess(Span span, TxnResponse response) {
        span.setAttribute(
                "etcd.txn.succeeded",
                response.isSucceeded()
        );
    }

    private Attributes attributes() {
        return Attributes.builder()
                .put(stringKey("db.system.name"), "etcd")
                .put(stringKey("db.operation.name"), "txn")
                .put(stringKey("server.address"), endpoints)

                .put(longKey("etcd.txn.compare.count"), compareCount)

                .put(longKey("etcd.txn.then.count"), thenCount)
                .put(longKey("etcd.txn.then.put.count"), thenPutCount)
                .put(longKey("etcd.txn.then.get.count"), thenGetCount)
                .put(longKey("etcd.txn.then.delete.count"), thenDeleteCount)
                .put(longKey("etcd.txn.then.txn.count"), thenTxnCount)

                .put(longKey("etcd.txn.else.count"), elseCount)
                .put(longKey("etcd.txn.else.put.count"), elsePutCount)
                .put(longKey("etcd.txn.else.get.count"), elseGetCount)
                .put(longKey("etcd.txn.else.delete.count"), elseDeleteCount)
                .put(longKey("etcd.txn.else.txn.count"), elseTxnCount)

                .build();
    }

    private static OpCounts countOps(Op... ops) {
        OpCounts result = new OpCounts();

        if (ops == null) {
            return result;
        }

        for (Op op : ops) {
            if (op == null) {
                continue;
            }

            result.total++;

            if (op instanceof Op.PutOp) {
                result.put++;
            } else if (op instanceof Op.GetOp) {
                result.get++;
            } else if (op instanceof Op.DeleteOp) {
                result.delete++;
            } else if (op instanceof Op.TxnOp) {
                result.txn++;
            }
        }

        return result;
    }

    private static final class OpCounts {
        private long total;
        private long put;
        private long get;
        private long delete;
        private long txn;
    }
}

package com.otr.portal.etcd;

import io.etcd.jetcd.Auth;
import io.etcd.jetcd.Client;
import io.etcd.jetcd.Cluster;
import io.etcd.jetcd.Election;
import io.etcd.jetcd.KV;
import io.etcd.jetcd.Lease;
import io.etcd.jetcd.Lock;
import io.etcd.jetcd.Maintenance;
import io.etcd.jetcd.Watch;
import io.opentelemetry.api.OpenTelemetry;

import java.util.List;

/**
 * Декоратор над {@link Client}, добавляющий трассировку OpenTelemetry к запросам ETCD.
 *
 * @author alexraspopin
 * @since 15.05.2026
 */
class TracedEtcdClient implements Client {
    private final Client delegate;
    private final KV tracedKvClient;

    TracedEtcdClient(Client delegate,
                     OpenTelemetry openTelemetry,
                     List<String> endpoints
    ) {
        this.delegate = delegate;
        var tracer = openTelemetry.getTracer("com.otr.portal.etcd");
        this.tracedKvClient = new TracedEtcdKV(this.delegate.getKVClient(), tracer, endpoints);
    }

    @Override
    public KV getKVClient() {
        return this.tracedKvClient;
    }

    @Override
    public Auth getAuthClient() {
        return this.delegate.getAuthClient();
    }

    @Override
    public Cluster getClusterClient() {
        return this.delegate.getClusterClient();
    }

    @Override
    public Maintenance getMaintenanceClient() {
        return this.delegate.getMaintenanceClient();
    }

    @Override
    public Lease getLeaseClient() {
        return this.delegate.getLeaseClient();
    }

    @Override
    public Watch getWatchClient() {
        return this.delegate.getWatchClient();
    }

    @Override
    public Lock getLockClient() {
        return this.delegate.getLockClient();
    }

    @Override
    public Election getElectionClient() {
        return this.delegate.getElectionClient();
    }

    @Override
    public void close() {
        this.delegate.close();
    }
}

package com.instaclustr.icarus.operations.restart;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.inject.assistedinject.Assisted;
import com.instaclustr.cassandra.service.CassandraWaiter;
import com.instaclustr.cassandra.service.CqlSessionService;
import com.instaclustr.icarus.operations.drain.DrainOperation;
import com.instaclustr.icarus.operations.drain.DrainOperationRequest;
import com.instaclustr.icarus.service.CassandraStatusService;
import com.instaclustr.kubernetes.KubernetesHelper;
import com.instaclustr.kubernetes.KubernetesSecretsReader;
import com.instaclustr.operations.Operation;
import com.instaclustr.operations.OperationFailureException;
import io.kubernetes.client.Exec;
import io.kubernetes.client.apis.CoreV1Api;
import jmx.org.apache.cassandra.service.CassandraJMXService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RestartOperation extends Operation<RestartOperationRequest> {

    private static final Logger logger = LoggerFactory.getLogger(RestartOperation.class);

    private final CassandraJMXService cassandraJMXService;
    private final CassandraStatusService statusService;
    private final Provider<CoreV1Api> coreV1ApiProvider;
    private final CqlSessionService cqlSessionService;
    private final CassandraWaiter cassandraWaiter;

    @Inject
    public RestartOperation(final CassandraJMXService cassandraJMXService,
                            final CassandraStatusService statusService,
                            final Provider<CoreV1Api> coreV1ApiProvider,
                            final CqlSessionService cqlSessionService,
                            final CassandraWaiter cassandraWaiter,
                            @Assisted final RestartOperationRequest request) {
        super(request);
        this.cassandraJMXService = cassandraJMXService;
        this.statusService = statusService;
        this.coreV1ApiProvider = coreV1ApiProvider;
        this.cqlSessionService = cqlSessionService;
        this.cassandraWaiter = cassandraWaiter;
    }

    // this constructor is not meant to be instantiated manually
    // and it fulfills the purpose of deserialisation from JSON string to an Operation object, currently just for testing purposes
    @JsonCreator
    private RestartOperation(@JsonProperty("type") final String type,
                             @JsonProperty("id") final UUID id,
                             @JsonProperty("creationTime") final Instant creationTime,
                             @JsonProperty("state") final State state,
                             @JsonProperty("errors") final List<Error> errors,
                             @JsonProperty("progress") final float progress,
                             @JsonProperty("startTime") final Instant startTime) {
        super(type, id, creationTime, state, errors, progress, startTime, new RestartOperationRequest(type));
        this.cassandraJMXService = null;
        this.statusService = null;
        this.coreV1ApiProvider = null;
        this.cqlSessionService = null;
        this.cassandraWaiter = null;
    }

    @Override
    protected void run0() throws Exception {

        if (!KubernetesHelper.isRunningInKubernetes()) {
            throw new OperationFailureException("Sidecar is not running in Kubernetes.");
        }

        assert cassandraJMXService != null;
        assert statusService != null;
        assert coreV1ApiProvider != null;
        assert cqlSessionService != null;
        assert cassandraWaiter != null;

        // drain

        logger.info("Starting restart operation.");

        final DrainOperation drainOperation = new DrainOperation(statusService, cassandraJMXService, new DrainOperationRequest());

        drainOperation.run();

        if (!drainOperation.errors.isEmpty()) {
            addErrors(drainOperation.errors);
            return;
        }

        // kill, killing will cause restart

        final CoreV1Api coreV1Api = coreV1ApiProvider.get();

        final Process killCassandraProcess = new Exec(coreV1Api.getApiClient()).exec(KubernetesSecretsReader.readNamespace(),
                                                                                     KubernetesHelper.getPodName(),
                                                                                     new String[]{"sh", "-c", "/bin/kill 1"},
                                                                                     "cassandra",
                                                                                     false,
                                                                                     false);


        killCassandraProcess.waitFor();
        killCassandraProcess.destroy();

        cassandraWaiter.waitUntilAvailable();

        logger.info("Restart operation finished.");
    }
}

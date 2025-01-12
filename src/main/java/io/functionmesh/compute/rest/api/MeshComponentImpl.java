/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package io.functionmesh.compute.rest.api;

import static com.google.common.base.Preconditions.checkNotNull;
import static io.functionmesh.compute.util.CommonUtil.COMPONENT_LABEL_CLAIM;
import static io.functionmesh.compute.util.CommonUtil.COMPONENT_LABEL_CLAIM_DEPRECATED;
import static io.functionmesh.compute.util.CommonUtil.INSECURE_PLUGIN_NAME;
import static io.functionmesh.compute.util.CommonUtil.getCustomLabelClaimsSelector;
import static io.functionmesh.compute.util.CommonUtil.getCustomLabelClaimsSelectorLegacy;
import static io.functionmesh.compute.util.PackageManagementServiceUtil.getPackageTypeFromComponentType;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.TimeUnit.SECONDS;
import static javax.ws.rs.core.Response.Status.BAD_REQUEST;
import static javax.ws.rs.core.Response.Status.CONFLICT;
import static javax.ws.rs.core.Response.Status.INTERNAL_SERVER_ERROR;
import static javax.ws.rs.core.Response.Status.NOT_FOUND;
import static javax.ws.rs.core.Response.Status.REQUEST_TIMEOUT;
import static javax.ws.rs.core.Response.Status.SERVICE_UNAVAILABLE;
import static javax.ws.rs.core.Response.Status.UNAUTHORIZED;
import static org.apache.bookkeeper.common.concurrent.FutureUtils.result;
import static org.apache.commons.lang3.StringUtils.isEmpty;
import static org.apache.pulsar.functions.utils.FunctionCommon.getStateNamespace;
import static org.apache.pulsar.functions.worker.rest.RestUtils.throwUnavailableException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.functionmesh.compute.MeshWorkerService;
import io.functionmesh.compute.auth.AuthHandler;
import io.functionmesh.compute.functions.models.V1alpha1Function;
import io.functionmesh.compute.functions.models.V1alpha1FunctionList;
import io.functionmesh.compute.functions.models.V1alpha1FunctionSpecInputSourceSpecs;
import io.functionmesh.compute.sinks.models.V1alpha1Sink;
import io.functionmesh.compute.sinks.models.V1alpha1SinkSpecInputSourceSpecs;
import io.functionmesh.compute.sources.models.V1alpha1Source;
import io.functionmesh.compute.util.CommonUtil;
import io.functionmesh.compute.util.KubernetesUtils;
import io.functionmesh.compute.util.PackageManagementServiceUtil;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.kubernetes.client.openapi.models.V1Pod;
import io.kubernetes.client.util.generic.GenericKubernetesApi;
import io.kubernetes.client.util.generic.KubernetesApiResponse;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import javax.ws.rs.core.StreamingOutput;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Call;
import okhttp3.Response;
import org.apache.bookkeeper.api.StorageClient;
import org.apache.bookkeeper.api.kv.Table;
import org.apache.bookkeeper.api.kv.result.KeyValue;
import org.apache.bookkeeper.clients.StorageClientBuilder;
import org.apache.bookkeeper.clients.admin.StorageAdminClient;
import org.apache.bookkeeper.clients.config.StorageClientSettings;
import org.apache.bookkeeper.clients.exceptions.NamespaceNotFoundException;
import org.apache.bookkeeper.clients.exceptions.StreamNotFoundException;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.pulsar.broker.authentication.AuthenticationParameters;
import org.apache.pulsar.client.admin.PulsarAdminException;
import org.apache.pulsar.client.api.Message;
import org.apache.pulsar.client.api.MessageId;
import org.apache.pulsar.client.api.Producer;
import org.apache.pulsar.client.api.Reader;
import org.apache.pulsar.client.api.Schema;
import org.apache.pulsar.client.api.SchemaSerializationException;
import org.apache.pulsar.common.functions.FunctionConfig;
import org.apache.pulsar.common.functions.FunctionState;
import org.apache.pulsar.common.functions.Resources;
import org.apache.pulsar.common.io.ConnectorDefinition;
import org.apache.pulsar.common.naming.NamespaceName;
import org.apache.pulsar.common.naming.TopicName;
import org.apache.pulsar.common.policies.data.FunctionInstanceStatsDataImpl;
import org.apache.pulsar.common.policies.data.FunctionInstanceStatsImpl;
import org.apache.pulsar.common.policies.data.FunctionStatsImpl;
import org.apache.pulsar.common.policies.data.SubscriptionStats;
import org.apache.pulsar.common.policies.data.TenantInfo;
import org.apache.pulsar.common.policies.data.TopicStats;
import org.apache.pulsar.common.util.ObjectMapperFactory;
import org.apache.pulsar.common.util.RestException;
import org.apache.pulsar.functions.instance.InstanceUtils;
import org.apache.pulsar.functions.proto.Function;
import org.apache.pulsar.functions.proto.InstanceCommunication.MetricsData;
import org.apache.pulsar.functions.proto.InstanceControlGrpc;
import org.apache.pulsar.functions.proto.InstanceControlGrpc.InstanceControlFutureStub;
import org.apache.pulsar.functions.utils.Actions;
import org.apache.pulsar.functions.utils.ComponentTypeUtils;
import org.apache.pulsar.functions.utils.FunctionCommon;
import org.apache.pulsar.functions.utils.SourceConfigUtils;
import org.apache.pulsar.functions.worker.WorkerService;
import org.apache.pulsar.functions.worker.service.api.Component;

@Slf4j
public abstract class MeshComponentImpl<T extends io.kubernetes.client.common.KubernetesObject,
        K extends io.kubernetes.client.common.KubernetesListObject> implements Component<MeshWorkerService> {

    static final String API_GROUP = "compute.functionmesh.io";
    protected final Supplier<MeshWorkerService> meshWorkerServiceSupplier;
    protected final Function.FunctionDetails.ComponentType componentType;
    protected String apiVersion = "v1alpha1";
    protected String apiKind = "Function";
    protected String apiPlural = "functions";
    @Getter
    protected GenericKubernetesApi<T, K> resourceApi;

    private final AtomicReference<StorageClient> storageClient = new AtomicReference<>();

    MeshComponentImpl(Supplier<MeshWorkerService> meshWorkerServiceSupplier,
                      Function.FunctionDetails.ComponentType componentType) {
        this.meshWorkerServiceSupplier = meshWorkerServiceSupplier;
        // If you want to support function-mesh, this type needs to be changed
        this.componentType = componentType;
    }

    @Override
    public FunctionConfig getFunctionInfo(final String tenant,
                                          final String namespace,
                                          final String componentName,
                                          final AuthenticationParameters authenticationParameters) {

        FunctionConfig functionConfig = new FunctionConfig();
        return functionConfig;
    }

    @Override
    public void deregisterFunction(final String tenant,
                                   final String namespace,
                                   final String componentName,
                                   final AuthenticationParameters authenticationParameters) {
        this.validateGetInfoRequestParams(tenant, namespace, componentName, apiKind);

        this.validatePermission(tenant,
                namespace,
                authenticationParameters,
                ComponentTypeUtils.toString(componentType));
        try {
            String clusterName = worker().getWorkerConfig().getPulsarFunctionsCluster();
            String nameSpaceName = worker().getJobNamespace();
            String hashName = CommonUtil.createObjectName(clusterName, tenant, namespace, componentName);
            // get component before delete it
            KubernetesApiResponse response = getResourceApi().get(nameSpaceName, hashName);
            getResourceApi().delete(nameSpaceName, hashName);

            // clean up component after delete
            try {
                cleanupComponent(response, tenant, namespace, hashName);
            } catch (PulsarAdminException e) {
                // ignore not found error
                if (!e.getClass().isAssignableFrom(PulsarAdminException.NotFoundException.class)) {
                    throw e;
                }
            }

            if (worker().getMeshWorkerServiceCustomConfig().isUploadEnabled()) {
                PackageManagementServiceUtil.deletePackageFromPackageService(
                        worker().getBrokerAdmin(), getPackageTypeFromComponentType(componentType),
                        tenant, namespace, componentName);
            }

            String authPluginName = worker().getWorkerConfig().getBrokerClientAuthenticationPlugin();
            if (worker().getMeshWorkerServiceCustomConfig().isUsingInsecureAuth()) {
                authPluginName = INSECURE_PLUGIN_NAME;
            }
            if (!StringUtils.isEmpty(authPluginName)) {
                AuthHandler handler = CommonUtil.AUTH_HANDLERS.get(authPluginName);
                if (handler != null) {
                    try {
                        handler.cleanUp(worker(), authenticationParameters, apiKind, clusterName, tenant,
                                namespace, componentName);
                    } catch (RuntimeException e) {
                        log.error("clean up auth for {}/{}/{} failed", tenant, namespace, componentName, e);
                    }
                }
            }
            if (worker().getWorkerConfig().getTlsEnabled()) {
                Call deleteTlsSecretCall = worker().getCoreV1Api()
                        .deleteNamespacedSecretCall(
                                KubernetesUtils.getUniqueSecretName(
                                        apiKind.toLowerCase(),
                                        "tls",
                                        DigestUtils.sha256Hex(
                                                KubernetesUtils.getSecretName(
                                                        clusterName, tenant, namespace, componentName))),
                                worker().getJobNamespace(),
                                null,
                                null,
                                30,
                                false,
                                null,
                                null,
                                null
                        );
                executeCall(deleteTlsSecretCall, null);
            }
        } catch (Exception e) {
            log.error("deregister {}/{}/{} {} failed", tenant, namespace, componentName, apiPlural, e);
            throw new RestException(INTERNAL_SERVER_ERROR, e.getMessage());
        } finally {
            deleteStatestoreTableAsync(getStateNamespace(tenant, namespace), componentName);
        }
    }

    public <R> R executeCall(Call call, Class<R> c) throws Exception {
        Response response;
        response = call.execute();
        if (response.isSuccessful() && response.body() != null) {
            String data = response.body().string();
            if (c == null) {
                return null;
            }
            return worker().getApiClient().getJSON().getGson().fromJson(data, c);
        } else if (response.code() == 409) {
            throw new RestException(CONFLICT,
                    "This resource already exists, please change the name");
        } else {
            String body = response.body() != null ? response.body().string() : "";
            String err = String.format(
                    "failed to perform the request: responseCode: %s, responseMessage: %s, responseBody: %s",
                    response.code(), response.message(), body);
            throw new RestException(BAD_REQUEST, err);
        }
    }

    public T extractResponse(KubernetesApiResponse<T> response) throws RestException {
        if (response.isSuccess()) {
            return response.getObject();
        } else if (response.getHttpStatusCode() == 409) {
            throw new RestException(CONFLICT,
                    "This resource already exists, please change the name");
        } else {
            String err = String.format(
                    "failed to perform the request: responseCode: %s, responseMessage: %s",
                    response.getHttpStatusCode(), response.getStatus().getMessage());
            throw new RestException(BAD_REQUEST, err);
        }
    }

    @Override
    public MeshWorkerService worker() {
        try {
            return checkNotNull(meshWorkerServiceSupplier.get());
        } catch (Throwable t) {
            log.info("Failed to get worker service", t);
            throw t;
        }
    }

    @Override
    public void stopFunctionInstance(final String tenant,
                                     final String namespace,
                                     final String componentName,
                                     final String instanceId,
                                     final URI uri,
                                     final AuthenticationParameters authenticationParameters) {
            throw new RestException(javax.ws.rs.core.Response.Status.SERVICE_UNAVAILABLE,
                    "stop the specified function instance is not supported via mesh worker service");
    }

    @Override
    public void startFunctionInstance(final String tenant,
                                      final String namespace,
                                      final String componentName,
                                      final String instanceId,
                                      final URI uri,
                                      final AuthenticationParameters authenticationParameters) {
            throw new RestException(javax.ws.rs.core.Response.Status.SERVICE_UNAVAILABLE,
                    "start the specified function instance is not supported via mesh worker service");
    }

    @Override
    public void restartFunctionInstance(final String tenant,
                                        final String namespace,
                                        final String componentName,
                                        final String instanceId,
                                        final URI uri,
                                        final AuthenticationParameters authenticationParameters) {
        throw new RestException(javax.ws.rs.core.Response.Status.SERVICE_UNAVAILABLE,
                "restart the specified function instance is not supported via mesh worker service");
    }

    @Override
    public void startFunctionInstances(final String tenant,
                                       final String namespace,
                                       final String componentName,
                                       final AuthenticationParameters authenticationParameters) {

    }

    @Override
    public void stopFunctionInstances(final String tenant,
                                      final String namespace,
                                      final String componentName,
                                      final AuthenticationParameters authenticationParameters) {

    }

    @Override
    public void restartFunctionInstances(final String tenant,
                                         final String namespace,
                                         final String componentName,
                                         final AuthenticationParameters authenticationParameters) {

    }

    @Override
    public FunctionStatsImpl getFunctionStats(final String tenant,
                                              final String namespace,
                                              final String componentName,
                                              final URI uri,
                                              final AuthenticationParameters authenticationParameters) {
        if (!isWorkerServiceAvailable()) {
            throwUnavailableException();
        }

        this.validatePermission(tenant,
                namespace,
                authenticationParameters,
                ComponentTypeUtils.toString(componentType));
        this.validateTenantIsExist(tenant, namespace, componentName, authenticationParameters);
        this.validateGetInfoRequestParams(tenant, namespace, componentName, ComponentTypeUtils.toString(componentType));

        FunctionStatsImpl functionStats = new FunctionStatsImpl();
        try {
            List<FunctionInstanceStatsImpl> instanceStatsList =
                    getComponentInstancesStats(tenant, namespace, componentName);
            for (FunctionInstanceStatsImpl instanceStats : instanceStatsList) {
                if (instanceStats != null) {
                    functionStats.addInstance(instanceStats);
                }
            }

            return functionStats.calculateOverall();
        } catch (Exception e) {
            log.error("{}/{}/{} Got Exception Getting Stats", tenant, namespace, componentName, e);
            throw new RestException(INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    @Override
    public FunctionInstanceStatsDataImpl getFunctionsInstanceStats(final String tenant,
                                                                   final String namespace,
                                                                   final String componentName,
                                                                   final String instanceId,
                                                                   final URI uri,
                                                                   final AuthenticationParameters authenticationParameters) {
        return new FunctionInstanceStatsDataImpl();
    }

    @Override
    public String triggerFunction(final String tenant,
                                  final String namespace,
                                  final String functionName,
                                  final String input,
                                  final InputStream uploadedInputStream,
                                  final String topic,
                                  final AuthenticationParameters authenticationParameters) {
        if (componentType != Function.FunctionDetails.ComponentType.FUNCTION) {
            throw new RestException(BAD_REQUEST, "This request is not supported on " + componentType);
        }
        if (!isWorkerServiceAvailable()) {
            throwUnavailableException();
        }

        this.validatePermission(tenant,
                namespace,
                authenticationParameters,
                ComponentTypeUtils.toString(componentType));

        // validate parameters
        try {
            validateTriggerRequestParams(tenant, namespace, functionName, topic, input, uploadedInputStream);
        } catch (IllegalArgumentException e) {
            log.error("Invalid trigger function request @ /{}/{}/{}", tenant, namespace, functionName, e);
            throw new RestException(BAD_REQUEST, e.getMessage());
        }

        String clusterName = worker().getWorkerConfig().getPulsarFunctionsCluster();
        String nameSpaceName = worker().getJobNamespace();
        String hashName = CommonUtil.createObjectName(clusterName, tenant, namespace, functionName);

        V1alpha1Function v1alpha1Function =
                (V1alpha1Function) extractResponse(getResourceApi().get(nameSpaceName, hashName));

        String inputTopicToWrite;
        if (topic != null) {
            inputTopicToWrite = topic;
        } else if (v1alpha1Function.getSpec().getInput().getSourceSpecs().size() == 1) {
            inputTopicToWrite = v1alpha1Function.getSpec().getInput().getSourceSpecs()
                    .keySet().iterator().next();
        } else {
            log.error("Function in trigger function has more than 1 input topics @ /{}/{}/{}", tenant, namespace,
                    functionName);
            throw new RestException(BAD_REQUEST, "Function in trigger function has more than 1 input topics");
        }
        if (v1alpha1Function.getSpec().getInput().getSourceSpecs().size() == 0
                || !v1alpha1Function.getSpec().getInput().getSourceSpecs()
                .containsKey(inputTopicToWrite)) {
            log.error("Function in trigger function has unidentified topic @ /{}/{}/{} {}", tenant, namespace,
                    functionName, inputTopicToWrite);
            throw new RestException(BAD_REQUEST, "Function in trigger function has unidentified topic");
        }
        try {
            worker().getBrokerAdmin().topics().getSubscriptions(inputTopicToWrite);
        } catch (PulsarAdminException e) {
            log.error("Function in trigger function is not ready @ /{}/{}/{}", tenant, namespace, functionName);
            throw new RestException(BAD_REQUEST, "Function in trigger function is not ready");
        }
        String outputTopic = v1alpha1Function.getSpec().getOutput().getTopic();
        Reader<byte[]> reader = null;
        Producer<byte[]> producer = null;
        try {
            if (!isEmpty(outputTopic)) {
                reader = worker().getBrokerClient().newReader()
                        .topic(outputTopic)
                        .startMessageId(MessageId.latest)
                        .readerName(worker().getWorkerConfig().getWorkerId() + "-trigger-"
                                + FunctionCommon.getFullyQualifiedName(tenant, namespace, functionName))
                        .create();
            }
            producer = worker().getBrokerClient().newProducer(Schema.AUTO_PRODUCE_BYTES())
                    .topic(inputTopicToWrite)
                    .producerName(worker().getWorkerConfig().getWorkerId() + "-trigger-"
                            + FunctionCommon.getFullyQualifiedName(tenant, namespace, functionName))
                    .create();
            byte[] targetArray;
            if (uploadedInputStream != null) {
                targetArray = new byte[uploadedInputStream.available()];
                uploadedInputStream.read(targetArray);
            } else {
                targetArray = input.getBytes();
            }
            MessageId msgId = producer.send(targetArray);
            if (reader == null) {
                return null;
            }
            long curTime = System.currentTimeMillis();
            long maxTime = curTime + 1000;
            while (curTime < maxTime) {
                Message msg = reader.readNext(10000, TimeUnit.MILLISECONDS);
                if (msg == null) {
                    break;
                }
                if (msg.getProperties().containsKey("__pfn_input_msg_id__")
                        && msg.getProperties().containsKey("__pfn_input_topic__")) {
                    MessageId newMsgId = MessageId.fromByteArray(
                            Base64.getDecoder().decode((String) msg.getProperties().get("__pfn_input_msg_id__")));

                    if (msgId.equals(newMsgId)
                            && msg.getProperties().get("__pfn_input_topic__")
                            .equals(TopicName.get(inputTopicToWrite).toString())) {
                        return new String(msg.getData());
                    }
                }
                curTime = System.currentTimeMillis();
            }
            throw new RestException(REQUEST_TIMEOUT, "Request Timed Out");
        } catch (SchemaSerializationException e) {
            throw new RestException(BAD_REQUEST, String.format(
                    "Failed to serialize input with error: %s. Please check"
                            + "if input data conforms with the schema of the input topic.",
                    e.getMessage()));
        } catch (IOException e) {
            throw new RestException(INTERNAL_SERVER_ERROR, e.getMessage());
        } finally {
            if (reader != null) {
                reader.closeAsync();
            }
            if (producer != null) {
                producer.closeAsync();
            }
        }
    }

    @Override
    public List<String> listFunctions(final String tenant,
                                      final String namespace,
                                      final AuthenticationParameters authenticationParameters) {
        Set<String> result = new HashSet<>();
        try {
            String labelSelector;
            String cluster = worker().getWorkerConfig().getPulsarFunctionsCluster();
            labelSelector = getCustomLabelClaimsSelector(cluster, tenant, namespace);
            Call call = worker().getCustomObjectsApi().listNamespacedCustomObjectCall(
                    API_GROUP,
                    apiVersion,
                    worker().getJobNamespace(), apiPlural,
                    "false",
                    null,
                    null,
                    labelSelector,
                    null,
                    null,
                    null,
                    false,
                    null);

            V1alpha1FunctionList list = executeCall(call, V1alpha1FunctionList.class);

            labelSelector = getCustomLabelClaimsSelectorLegacy(cluster, tenant, namespace);
            call = worker().getCustomObjectsApi().listNamespacedCustomObjectCall(
                    API_GROUP,
                    apiVersion,
                    worker().getJobNamespace(), apiPlural,
                    "false",
                    null,
                    null,
                    labelSelector,
                    null,
                    null,
                    null,
                    false,
                    null);
            V1alpha1FunctionList listLegacy = executeCall(call, V1alpha1FunctionList.class);
            List<V1alpha1Function> functions = list.getItems();
            functions.addAll(listLegacy.getItems());
            functions.forEach(n -> {
                if (n.getMetadata() == null
                        || n.getMetadata().getLabels() == null || n.getMetadata().getLabels().isEmpty()) {
                    return;
                }
                String comp = n.getMetadata().getLabels().get(COMPONENT_LABEL_CLAIM);
                if (StringUtils.isEmpty(comp)) {
                    comp = n.getMetadata().getLabels().get(COMPONENT_LABEL_CLAIM_DEPRECATED);
                }
                if (StringUtils.isNotEmpty(comp)) {
                    result.add(comp);
                }
            });
        } catch (Exception e) {
            log.error("failed to fetch functions list from namespace {}", namespace, e);
        }

        return new ArrayList<>(result);
    }

    @Override
    public FunctionState getFunctionState(final String tenant,
                                          final String namespace,
                                          final String componentName,
                                          final String key,
                                          final AuthenticationParameters authenticationParameters) {
        if (!isWorkerServiceAvailable()) {
            throwUnavailableException();
        }

        this.validatePermission(tenant,
                namespace,
                authenticationParameters,
                ComponentTypeUtils.toString(componentType));
        this.validateTenantIsExist(tenant, namespace, componentName, authenticationParameters);
        this.validateGetInfoRequestParams(tenant, namespace, componentName, ComponentTypeUtils.toString(componentType));

        if (null == worker().getStateStoreAdminClient()) {
            throwStateStoreUnvailableResponse();
        }

        // validate parameters
        try {
            validateFunctionStateParams(tenant, namespace, componentName, key);
        } catch (IllegalArgumentException e) {
            log.error("Invalid getFunctionState request @ /{}/{}/{}/{}",
                    tenant, namespace, componentName, key, e);
            throw new RestException(BAD_REQUEST, e.getMessage());
        }

        String clusterName = worker().getWorkerConfig().getPulsarFunctionsCluster();
        String tableNs = getStateNamespace(tenant, namespace);
        String tableName = CommonUtil.createObjectName(clusterName, tenant, namespace, componentName);

        String stateStorageServiceUrl = worker().getWorkerConfig().getStateStorageServiceUrl();

        if (storageClient.get() == null) {
            storageClient.compareAndSet(null, StorageClientBuilder.newBuilder()
                    .withSettings(StorageClientSettings.newBuilder()
                            .serviceUri(stateStorageServiceUrl)
                            .clientName("functions-admin")
                            .build())
                    .withNamespace(tableNs)
                    .build());
        }

        FunctionState value;
        try (Table<ByteBuf, ByteBuf> table = result(storageClient.get().openTable(tableName))) {
            try (KeyValue<ByteBuf, ByteBuf> kv = result(table.getKv(Unpooled.wrappedBuffer(key.getBytes(UTF_8))))) {
                if (null == kv) {
                    throw new RestException(NOT_FOUND, "key '" + key + "' doesn't exist.");
                } else {
                    if (kv.isNumber()) {
                        value = new FunctionState(key, null, null, kv.numberValue(), kv.version());
                    } else {
                        try {
                            value = new FunctionState(key, new String(
                                    ByteBufUtil.getBytes(kv.value(), kv.value().readerIndex(),
                                            kv.value().readableBytes()), UTF_8), null, null, kv.version());
                        } catch (Exception e) {
                            value = new FunctionState(key, null, ByteBufUtil.getBytes(kv.value()), null, kv.version());
                        }
                    }
                }
            }
        } catch (RestException e) {
            throw e;
        } catch (NamespaceNotFoundException | StreamNotFoundException e) {
            log.debug("State not found while processing getFunctionState request @ /{}/{}/{}/{}",
                    tenant, namespace, componentName, key, e);
            throw new RestException(NOT_FOUND, e.getMessage());
        } catch (Exception e) {
            log.error("Error while getFunctionState request @ /{}/{}/{}/{}",
                    tenant, namespace, componentName, key, e);
            throw new RestException(INTERNAL_SERVER_ERROR, e.getMessage());
        }
        return value;
    }

    @Override
    public void putFunctionState(final String tenant,
                                 final String namespace,
                                 final String componentName,
                                 final String key,
                                 final FunctionState state,
                                 final AuthenticationParameters authenticationParameters) {

        if (!isWorkerServiceAvailable()) {
            throwUnavailableException();
        }

        this.validatePermission(tenant,
                namespace,
                authenticationParameters,
                ComponentTypeUtils.toString(componentType));
        this.validateTenantIsExist(tenant, namespace, componentName, authenticationParameters);
        this.validateGetInfoRequestParams(tenant, namespace, componentName, ComponentTypeUtils.toString(componentType));

        if (null == worker().getStateStoreAdminClient()) {
            throwStateStoreUnvailableResponse();
        }

        // validate parameters
        try {
            validateFunctionStateParams(tenant, namespace, componentName, key);
        } catch (IllegalArgumentException e) {
            log.error("Invalid getFunctionState request @ /{}/{}/{}/{}",
                    tenant, namespace, componentName, key, e);
            throw new RestException(BAD_REQUEST, e.getMessage());
        }

        String clusterName = worker().getWorkerConfig().getPulsarFunctionsCluster();
        String tableNs = getStateNamespace(tenant, namespace);
        String tableName = CommonUtil.createObjectName(clusterName, tenant, namespace, componentName);

        String stateStorageServiceUrl = worker().getWorkerConfig().getStateStorageServiceUrl();

        if (storageClient.get() == null) {
            storageClient.compareAndSet(null, StorageClientBuilder.newBuilder()
                    .withSettings(StorageClientSettings.newBuilder()
                            .serviceUri(stateStorageServiceUrl)
                            .clientName("functions-admin")
                            .build())
                    .withNamespace(tableNs)
                    .build());
        }

        ByteBuf value;
        if (!isEmpty(state.getStringValue())) {
            value = Unpooled.wrappedBuffer(state.getStringValue().getBytes());
        } else {
            value = Unpooled.wrappedBuffer(state.getByteValue());
        }
        try (Table<ByteBuf, ByteBuf> table = result(storageClient.get().openTable(tableName))) {
            result(table.put(Unpooled.wrappedBuffer(key.getBytes(UTF_8)), value));
        } catch (NamespaceNotFoundException | StreamNotFoundException e) {
            log.debug("State not found while processing putFunctionState request @ /{}/{}/{}/{}",
                    tenant, namespace, componentName, key, e);
            throw new RestException(NOT_FOUND, e.getMessage());
        } catch (Exception e) {
            log.error("Error while putFunctionState request @ /{}/{}/{}/{}",
                    tenant, namespace, componentName, key, e);
            throw new RestException(INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    @Override
    public void uploadFunction(final InputStream uploadedInputStream,
                               final String path,
                               final AuthenticationParameters authenticationParameters) {

    }

    @Override
    public StreamingOutput downloadFunction(String path,
                                            final AuthenticationParameters authenticationParameters) {
        // To do
        return null;
    }

    @Override
    public StreamingOutput downloadFunction(String tenant,
                                            String namespace,
                                            String componentName,
                                            final AuthenticationParameters authenticationParameters,
                                            boolean transformFunction) {
        // To do
        return null;
    }

    @Override
    public List<ConnectorDefinition> getListOfConnectors() {
        return meshWorkerServiceSupplier.get().getConnectorsManager().getConnectorDefinitions();
    }

    @Override
    public void reloadConnectors(final AuthenticationParameters authenticationParameters) {
        meshWorkerServiceSupplier.get().getConnectorsManager().reloadConnectors();
    }

    public boolean isSuperUser(final AuthenticationParameters authenticationParameters) {
        if (authenticationParameters != null && StringUtils.isNotEmpty(authenticationParameters.getClientRole())) {
            try {
                if ((worker().getWorkerConfig().getSuperUserRoles() != null
                        && worker().getWorkerConfig().getSuperUserRoles()
                        .contains(authenticationParameters.getClientRole()))) {
                    return true;
                }
                return worker().getAuthorizationService().isSuperUser(authenticationParameters)
                        .get(worker().getWorkerConfig().getZooKeeperOperationTimeoutSeconds(), SECONDS);
            } catch (InterruptedException e) {
                log.warn("Time-out {} sec while checking the role {} is a super user role ",
                        worker().getWorkerConfig().getZooKeeperOperationTimeoutSeconds(),
                        authenticationParameters.getClientRole());
                throw new RestException(INTERNAL_SERVER_ERROR, e.getMessage());
            } catch (Exception e) {
                log.warn("Admin-client with Role - failed to check the role {} is a super user role {} ",
                        authenticationParameters.getClientRole(),
                        e.getMessage(), e);
                throw new RestException(INTERNAL_SERVER_ERROR, e.getMessage());
            }
        }
        return false;
    }

    public boolean isAuthorizedRole(String tenant, String namespace, AuthenticationParameters authenticationParameters)
            throws PulsarAdminException {
        if (worker().getWorkerConfig().isAuthorizationEnabled()) {
            // skip authorization if client role is super-user
            if (isSuperUser(authenticationParameters)) {
                return true;
            }

            if (authenticationParameters != null && StringUtils.isNotEmpty(authenticationParameters.getClientRole())) {
                try {
                    TenantInfo tenantInfo = worker().getBrokerAdmin().tenants().getTenantInfo(tenant);
                    if (tenantInfo != null && worker().getAuthorizationService()
                            .isTenantAdmin(tenant, authenticationParameters.getClientRole(), tenantInfo,
                                    authenticationParameters.getClientAuthenticationDataSource()).get()) {
                        return true;
                    }
                } catch (PulsarAdminException.NotFoundException | InterruptedException | ExecutionException e) {

                }
            }

            // check if role has permissions granted
            if (authenticationParameters != null && StringUtils.isNotEmpty(authenticationParameters.getClientRole())) {
                return allowFunctionOps(NamespaceName.get(tenant, namespace), authenticationParameters);
            } else {
                return false;
            }
        }
        return true;
    }

    public boolean allowFunctionOps(NamespaceName namespaceName, AuthenticationParameters authenticationParameters) {
        String clientRole = authenticationParameters == null ? null : authenticationParameters.getClientRole();
        try {
            switch (componentType) {
                case SINK:
                    return worker().getAuthorizationService().allowSinkOpsAsync(
                                    namespaceName, authenticationParameters)
                            .get(worker().getWorkerConfig().getZooKeeperOperationTimeoutSeconds(), SECONDS);
                case SOURCE:
                    return worker().getAuthorizationService().allowSourceOpsAsync(
                                    namespaceName, authenticationParameters)
                            .get(worker().getWorkerConfig().getZooKeeperOperationTimeoutSeconds(), SECONDS);
                case FUNCTION:
                default:
                    return worker().getAuthorizationService().allowFunctionOpsAsync(
                                    namespaceName, authenticationParameters)
                            .get(worker().getWorkerConfig().getZooKeeperOperationTimeoutSeconds(), SECONDS);
            }
        } catch (InterruptedException e) {
            log.warn("Time-out {} sec while checking function authorization on {} ",
                    worker().getWorkerConfig().getZooKeeperOperationTimeoutSeconds(), namespaceName);
            throw new RestException(INTERNAL_SERVER_ERROR, e.getMessage());
        } catch (Exception e) {
            log.warn("Admin-client with Role - {} failed to get function permissions for namespace - {}. {}",
                    clientRole,
                    namespaceName,
                    e.getMessage(), e);
            throw new RestException(INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    void validatePermission(String tenant,
                            String namespace,
                            AuthenticationParameters authenticationParameters,
                            String componentName) {
        String clientRole = authenticationParameters == null ? null : authenticationParameters.getClientRole();
        try {
            if (!isAuthorizedRole(tenant, namespace, authenticationParameters)) {
                log.warn("{}/{}/{} Client [{}] is not authorized to get {}", tenant, namespace,
                        componentName, clientRole, ComponentTypeUtils.toString(componentType));
                throw new RestException(UNAUTHORIZED,
                        "client is not authorize to perform operation");
            }
        } catch (PulsarAdminException e) {
            log.error("{}/{}/{} Failed to authorize", tenant, namespace, componentName, e);
            throw new RestException(INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    void validateGetInfoRequestParams(
            String tenant, String namespace, String name, String type) {
        if (tenant == null) {
            throw new RestException(BAD_REQUEST, "Tenant is not provided");
        }
        if (namespace == null) {
            throw new RestException(BAD_REQUEST, "Namespace is not provided");
        }
        if (name == null) {
            throw new RestException(BAD_REQUEST, type + " name is not provided");
        }
    }

    void validateTenantIsExist(String tenant, String namespace, String name,
                               final AuthenticationParameters authenticationParameters) {
        String clientRole = authenticationParameters == null ? null : authenticationParameters.getClientRole();
        try {
            // Check tenant exists
            worker().getBrokerAdmin().tenants().getTenantInfo(tenant);

        } catch (PulsarAdminException.NotAuthorizedException e) {
            log.error("{}/{}/{} Client [{}] is not authorized to operate {} on tenant", tenant, namespace,
                    name, clientRole, ComponentTypeUtils.toString(componentType));
            throw new RestException(UNAUTHORIZED,
                    "client is not authorize to perform operation");
        } catch (PulsarAdminException.NotFoundException e) {
            log.error("{}/{}/{} Tenant {} does not exist", tenant, namespace, name, tenant);
            throw new RestException(BAD_REQUEST, "Tenant does not exist");
        } catch (PulsarAdminException e) {
            log.error("{}/{}/{} Issues getting tenant data", tenant, namespace, name, e);
            throw new RestException(INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    void validateResources(Resources componentResources, Resources minResource, Resources maxResource) {
        if (componentResources != null) {
            if (minResource != null && (componentResources.getCpu() < minResource.getCpu()
                    || componentResources.getRam() < minResource.getRam())) {
                throw new RestException(BAD_REQUEST,
                        "Resource is less than minimum requirement");
            }
            if (maxResource != null && (componentResources.getCpu() > maxResource.getCpu()
                    || componentResources.getRam() > maxResource.getRam())) {
                throw new RestException(BAD_REQUEST,
                        "Resource is larger than max requirement");
            }
        }
    }

    boolean isWorkerServiceAvailable() {
        WorkerService workerService = meshWorkerServiceSupplier.get();
        if (workerService == null) {
            return false;
        }
        return workerService.isInitialized();
    }

    abstract List<FunctionInstanceStatsImpl> getComponentInstancesStats(String tenant, String namespace,
                                                                        String componentName);

    abstract void validateResourceObject(T obj) throws IllegalArgumentException;

    public Set<CompletableFuture<MetricsData>> fetchStatsFromGRPC(List<V1Pod> pods,
                                                                  String subdomain,
                                                                  String statefulSetName,
                                                                  String nameSpaceName,
                                                                  List<FunctionInstanceStatsImpl> statsList,
                                                                  ManagedChannel[] channel,
                                                                  InstanceControlFutureStub[] stub) {
        Set<CompletableFuture<MetricsData>> completableFutureSet = new HashSet<>();
        pods.forEach(pod -> {
            String podName = KubernetesUtils.getPodName(pod);
            int shardId = CommonUtil.getShardIdFromPodName(podName);
            int podIndex = pods.indexOf(pod);
            String address = KubernetesUtils.getServiceUrl(podName, subdomain, nameSpaceName);
            if (shardId == -1) {
                log.warn("shardId invalid {}", podName);
                return;
            }
            final FunctionInstanceStatsImpl functionInstanceStats =
                    statsList.stream().filter(v -> v.getInstanceId() == shardId).findFirst()
                            .orElse(null);
            if (functionInstanceStats != null) {
                // get status from grpc
                if (channel[podIndex] == null && stub[podIndex] == null) {
                    channel[podIndex] = ManagedChannelBuilder.forAddress(address, 9093)
                            .usePlaintext()
                            .build();
                    stub[podIndex] = InstanceControlGrpc.newFutureStub(channel[podIndex]);
                }
                CompletableFuture<MetricsData> future =
                        CommonUtil.getFunctionMetricsAsync(stub[podIndex]);
                future.whenComplete((fs, e) -> {
                    if (channel[podIndex] != null) {
                        log.debug("closing channel {}", podIndex);
                        channel[podIndex].shutdown();
                    }
                    if (e != null) {
                        log.warn("Get {}-{} stats from grpc failed from namespace {}",
                                statefulSetName,
                                shardId,
                                nameSpaceName,
                                e);
                    } else if (fs != null) {
                        CommonUtil.convertFunctionMetricsToFunctionInstanceStats(fs, functionInstanceStats);
                    }
                });
                completableFutureSet.add(future);
            } else {
                log.warn("Get {}-{} stats failed from namespace {}, cannot find status for shardId {}",
                        statefulSetName,
                        shardId,
                        nameSpaceName,
                        shardId);
            }
        });
        return completableFutureSet;
    }

    private void throwStateStoreUnvailableResponse() {
        throw new RestException(SERVICE_UNAVAILABLE,
                "State storage client is not done initializing. " + "Please try again in a little while.");
    }

    private void validateFunctionStateParams(final String tenant,
                                             final String namespace,
                                             final String functionName,
                                             final String key)
            throws IllegalArgumentException {

        if (tenant == null) {
            throw new IllegalArgumentException("Tenant is not provided");
        }
        if (namespace == null) {
            throw new IllegalArgumentException("Namespace is not provided");
        }
        if (functionName == null) {
            throw new IllegalArgumentException(ComponentTypeUtils.toString(componentType) + " name is not provided");
        }
        if (key == null) {
            throw new IllegalArgumentException("Key is not provided");
        }
    }

    private void deleteStatestoreTableAsync(String namespace, String table) {
        StorageAdminClient adminClient = worker().getStateStoreAdminClient();
        if (adminClient != null) {
            adminClient.deleteStream(namespace, table).whenComplete((res, throwable) -> {
                if ((throwable == null && res)
                        || ((throwable instanceof NamespaceNotFoundException
                        || throwable instanceof StreamNotFoundException))) {
                    log.info("{}/{} table deleted successfully", namespace, table);
                } else {
                    if (throwable != null) {
                        log.error("{}/{} table deletion failed {}  but moving on", namespace, table, throwable);
                    } else {
                        log.error("{}/{} table deletion failed but moving on", namespace, table);
                    }
                }
            });
        }
    }

    private void cleanupComponent(KubernetesApiResponse response, String tenant, String namespace, String componentName)
            throws PulsarAdminException, InterruptedException {
        Boolean cleanup = false;
        String subscriptionName = null;
        List<String> inputTopics = new ArrayList<>();
        String topicPattern = null;
        switch (componentType) {
            case FUNCTION:
                V1alpha1Function v1alpha1Function = (V1alpha1Function) extractResponse(response);
                if (v1alpha1Function.getSpec() != null && v1alpha1Function.getSpec().getInput() != null) {
                    cleanup = v1alpha1Function.getSpec().getCleanupSubscription();
                    subscriptionName = v1alpha1Function.getSpec().getSubscriptionName();
                    topicPattern = v1alpha1Function.getSpec().getInput().getTopicPattern();
                    inputTopics = v1alpha1Function.getSpec().getInput().getTopics();
                    if (v1alpha1Function.getSpec().getInput().getSourceSpecs() != null) {
                        for (Map.Entry<String, V1alpha1FunctionSpecInputSourceSpecs> entry :
                                v1alpha1Function.getSpec().getInput().getSourceSpecs().entrySet()) {
                            if (entry.getValue().getIsRegexPattern() != null && entry.getValue().getIsRegexPattern()) {
                                topicPattern = entry.getKey();
                            } else {
                                inputTopics.add(entry.getKey());
                            }
                        }
                    }
                }
                break;
            case SINK:
                V1alpha1Sink v1alpha1Sink = (V1alpha1Sink) extractResponse(response);
                if (v1alpha1Sink.getSpec() != null && v1alpha1Sink.getSpec().getInput() != null) {
                    cleanup = v1alpha1Sink.getSpec().getCleanupSubscription();
                    subscriptionName = v1alpha1Sink.getSpec().getSubscriptionName();
                    topicPattern = v1alpha1Sink.getSpec().getInput().getTopicPattern();
                    inputTopics = v1alpha1Sink.getSpec().getInput().getTopics();
                    if (v1alpha1Sink.getSpec().getInput().getSourceSpecs() != null) {
                        for (Map.Entry<String, V1alpha1SinkSpecInputSourceSpecs> entry :
                                v1alpha1Sink.getSpec().getInput().getSourceSpecs().entrySet()) {
                            if (entry.getValue().getIsRegexPattern() != null && entry.getValue().getIsRegexPattern()) {
                                topicPattern = entry.getKey();
                            } else {
                                inputTopics.add(entry.getKey());
                            }
                        }
                    }
                }
                break;
            case SOURCE:
                V1alpha1Source v1alpha1Source = (V1alpha1Source) extractResponse(response);
                if (v1alpha1Source.getSpec() != null && v1alpha1Source.getSpec().getBatchSourceConfig() != null) {
                    cleanup = StringUtils.isNotEmpty(
                            v1alpha1Source.getSpec().getBatchSourceConfig().getDiscoveryTriggererClassName());
                    subscriptionName =
                            SourceConfigUtils.computeBatchSourceInstanceSubscriptionName(tenant,
                                    namespace, componentName);
                    inputTopics = List.of(SourceConfigUtils.computeBatchSourceIntermediateTopicName(tenant, namespace,
                            componentName).toString());
                }
                break;
            default:
                break;
        }
        if (Boolean.TRUE.equals(cleanup)) {
            if (StringUtils.isEmpty(subscriptionName)) {
                subscriptionName = InstanceUtils.getDefaultSubscriptionName(tenant, namespace, componentName);
            }
            if (StringUtils.isNotEmpty(topicPattern)) {
                log.info("Cleanup subscription {} for topic pattern {}", subscriptionName, topicPattern);
                deleteSubscription(topicPattern, true, subscriptionName, String.format("Cleaning up subscriptions for function %s", componentName));
            } else {
                log.info("Cleanup subscription {} for topics {}", subscriptionName,
                        StringUtils.join(inputTopics, ","));
                Map<String, Boolean> processed = new HashMap<>();
                for (String topic : inputTopics) {
                    if (!processed.containsKey(topic)) {
                        deleteSubscription(topic, false, subscriptionName, String.format("Cleaning up subscriptions for function %s", componentName));
                        processed.put(topic, true);
                    }
                }
            }
            if (componentType == Function.FunctionDetails.ComponentType.SOURCE) {
                Actions.newBuilder()
                        .addAction(
                                // Unsubscribe and allow time for consumers to close
                                Actions.Action.builder()
                                        .actionName(String.format(
                                                "Removing intermediate topic subscription %s for Batch Source %s",
                                                subscriptionName, componentName))
                                        .numRetries(10)
                                        .sleepBetweenInvocationsMs(1000)
                                        .supplier(
                                                getDeleteSubscriptionSupplier(inputTopics.get(0),
                                                        false,
                                                        subscriptionName)
                                        )
                                        .build())
                        .addAction(
                                // Delete topic forcibly regardless whether unsubscribe succeeded or not
                                Actions.Action.builder()
                                        .actionName(String.format("Deleting intermediate topic %s for Batch Source %s",
                                                subscriptionName, componentName))
                                        .numRetries(10)
                                        .sleepBetweenInvocationsMs(1000)
                                        .supplier(getDeleteTopicSupplier(inputTopics.get(0)))
                                        .build())
                        .run();
            }
        }
    }

    private void deleteSubscription(String topic, boolean isRegexPattern,
                                    String subscriptionName, String msg) {
        try {
            Actions.newBuilder()
                    .addAction(
                            Actions.Action.builder()
                                    .actionName(msg)
                                    .numRetries(10)
                                    .sleepBetweenInvocationsMs(1000)
                                    .supplier(
                                            getDeleteSubscriptionSupplier(topic,
                                                    isRegexPattern,
                                                    subscriptionName)
                                    )
                                    .build())
                    .run();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    private Supplier<Actions.ActionResult> getDeleteSubscriptionSupplier(
            String topic, boolean isRegex, String subscriptionName) {
        return () -> {
            try {
                if (isRegex) {
                    worker().getBrokerAdmin().namespaces().unsubscribeNamespace(TopicName
                            .get(topic).getNamespace(), subscriptionName);
                } else {
                    worker().getBrokerAdmin().topics().deleteSubscription(topic,
                            subscriptionName);
                }
            } catch (PulsarAdminException e) {
                if (e instanceof PulsarAdminException.NotFoundException) {
                    return Actions.ActionResult.builder()
                            .success(true)
                            .build();
                } else {
                    String errorMsg = e.getHttpError() != null ? e.getHttpError() : e.getMessage();
                    if (isRegex) {
                        return Actions.ActionResult.builder()
                                .success(false)
                                .errorMsg(errorMsg)
                                .build();
                    }
                    // for debugging purposes
                    List<Map<String, String>> existingConsumers = Collections.emptyList();
                    SubscriptionStats sub = null;
                    try {
                        TopicStats stats = worker().getBrokerAdmin().topics().getStats(topic);
                        sub = stats.getSubscriptions().get(subscriptionName);
                        if (sub != null) {
                            existingConsumers = sub.getConsumers().stream()
                                    .map(consumerStats -> consumerStats.getMetadata())
                                    .collect(Collectors.toList());
                        }
                    } catch (PulsarAdminException e1) {

                    }

                    String finalErrorMsg;
                    if (sub != null) {
                        try {
                            ObjectMapper objectMapper = new ObjectMapper();
                            finalErrorMsg = String.format("%s - existing consumers: %s",
                                    errorMsg, objectMapper.writer().writeValueAsString(existingConsumers));
                        } catch (JsonProcessingException jsonProcessingException) {
                            finalErrorMsg = errorMsg;
                        }
                    } else {
                        finalErrorMsg = errorMsg;
                    }
                    return Actions.ActionResult.builder()
                            .success(false)
                            .errorMsg(finalErrorMsg)
                            .build();
                }
            }

            return Actions.ActionResult.builder()
                    .success(true)
                    .build();
        };
    }

    private Supplier<Actions.ActionResult> getDeleteTopicSupplier(String topic) {
        return () -> {
            try {
                worker().getBrokerAdmin().topics().delete(topic, true);
            } catch (PulsarAdminException e) {
                if (e instanceof PulsarAdminException.NotFoundException) {
                    return Actions.ActionResult.builder()
                            .success(true)
                            .build();
                } else {
                    // for debugging purposes
                    TopicStats stats = null;
                    try {
                        stats = worker().getBrokerAdmin().topics().getStats(topic);
                    } catch (PulsarAdminException e1) {

                    }

                    String errorMsg = e.getHttpError() != null ? e.getHttpError() : e.getMessage();
                    String finalErrorMsg;
                    if (stats != null) {
                        try {
                            finalErrorMsg = String.format("%s - topic stats: %s",
                                    errorMsg, ObjectMapperFactory.getMapper().writer().writeValueAsString(stats));
                        } catch (JsonProcessingException jsonProcessingException) {
                            finalErrorMsg = errorMsg;
                        }
                    } else {
                        finalErrorMsg = errorMsg;
                    }

                    return Actions.ActionResult.builder()
                            .success(false)
                            .errorMsg(finalErrorMsg)
                            .build();
                }
            }

            return Actions.ActionResult.builder()
                    .success(true)
                    .build();
        };
    }

    private void validateTriggerRequestParams(final String tenant,
                                              final String namespace,
                                              final String functionName,
                                              final String topic,
                                              final String input,
                                              final InputStream uploadedInputStream) {
        // Note : Checking topic is not required it can be null

        if (tenant == null) {
            throw new IllegalArgumentException("Tenant is not provided");
        }
        if (namespace == null) {
            throw new IllegalArgumentException("Namespace is not provided");
        }
        if (functionName == null) {
            throw new IllegalArgumentException("Function name is not provided");
        }
        if (uploadedInputStream == null && input == null) {
            throw new IllegalArgumentException("Trigger Data is not provided");
        }
    }
}

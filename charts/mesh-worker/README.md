# mesh-worker

![Version: 0.1.0](https://img.shields.io/badge/Version-0.1.0-informational?style=flat-square) ![Type: application](https://img.shields.io/badge/Type-application-informational?style=flat-square) ![AppVersion: 0.11.0](https://img.shields.io/badge/AppVersion-0.11.0-informational?style=flat-square)

A Helm chart for Kubernetes

## Values

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| fullnameOverride | string | `""` |  |
| id | string | `"standalone"` |  |
| image.pullPolicy | string | `"IfNotPresent"` |  |
| image.repository | string | `"streamnative/sn-pulsar"` |  |
| image.tag | string | `"2.9.3.18"` |  |
| imagePullSecrets | list | `[]` |  |
| nameOverride | string | `""` |  |
| pulsar.runnerImages.go | string | `"streamnative/pulsar-functions-go-runner:2.9.3.18"` |  |
| pulsar.runnerImages.java | string | `"streamnative/pulsar-functions-java-runner:2.9.3.18"` |  |
| pulsar.runnerImages.python | string | `"streamnative/pulsar-functions-python-runner:2.9.3.18"` |  |
| pulsar.serviceUrl | string | `"pulsar://sn-platform-pulsar-broker.default.svc.cluster.local:6650"` |  |
| pulsar.stateStoreServers | string | `"sn-platform-pulsar-zookeeper.default.svc.cluster.local:2181"` |  |
| pulsar.webServiceUrl | string | `"http://sn-platform-pulsar-broker.default.svc.cluster.local:8080"` |  |
| replicaCount | int | `1` |  |
| service.ports[0].name | string | `"pulsar-worker"` |  |
| service.ports[0].port | int | `6750` |  |
| service.ports[0].protocol | string | `"TCP"` |  |
| service.ports[0].targetPort | int | `6750` |  |
| service.ports[1].name | string | `"pulsar-worker-tls"` |  |
| service.ports[1].port | int | `6751` |  |
| service.ports[1].protocol | string | `"TCP"` |  |
| service.ports[1].targetPort | int | `6751` |  |
| service.type | string | `"ClusterIP"` |  |
| serviceAccount.functionMeshClusterRole | string | `"function-mesh-function-mesh-controller-manager"` |  |
| worker.extraVolumeMounts[0].mountPath | string | `"/pulsar/lib/mesh-worker-service.nar"` |  |
| worker.extraVolumeMounts[0].name | string | `"mesh-worker-service"` |  |
| worker.extraVolumes[0].hostPath.path | string | `"/opt/workspaces/mesh-worker-service/issue-530/target/mesh-worker-service-2.9.1.2.nar"` |  |
| worker.extraVolumes[0].name | string | `"mesh-worker-service"` |  |
| worker.functionsWorkerServiceNarPackage | string | `"/pulsar/lib/mesh-worker-service.nar"` |  |
| worker.workerPort | int | `6750` |  |
| worker.workerPortTls | int | `6751` |  |

----------------------------------------------
Autogenerated from chart metadata using [helm-docs v1.11.0](https://github.com/norwoodj/helm-docs/releases/v1.11.0)

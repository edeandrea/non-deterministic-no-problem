#!/bin/zsh -e

# Some cleanup
oc delete clusterrole langfuse-s3-rw-cr --ignore-not-found
oc delete clusterrolebinding $(oc get clusterrolebinding -o name | grep langfuse-s3 | sed 's|.*/||') --ignore-not-found 2>/dev/null || true

# Need to install requirements according to https://github.com/langfuse/langfuse-k8s/tree/main/examples/minimal-installation

##################
# Cert manager
#helm install cert-manager oci://quay.io/jetstack/charts/cert-manager \
#  --version v1.20.2 \
#  --namespace cert-manager \
#  --create-namespace \
#  --set crds.enabled=true
#
#oc wait --for=condition=Established \
#  crd/certificates.cert-manager.io \
#  crd/issuers.cert-manager.io \
#  --timeout=600s

##################
# ClickHouse operator
helm upgrade --install clickhouse-operator oci://ghcr.io/clickhouse/clickhouse-operator-helm \
  --version 0.0.5 \
  --namespace clickhouse-operator \
  --create-namespace \
  --rollback-on-failure \
  --wait

oc wait --for=condition=Established \
  crd/clickhouseclusters.clickhouse.com \
  crd/keeperclusters.clickhouse.com \
  --timeout=600s

# Need to helm install langfuse according to https://langfuse.com/self-hosting/deployment/kubernetes-helm#deploy-the-helm-chart
helm repo add langfuse https://langfuse.github.io/langfuse-k8s
helm repo update langfuse
helm upgrade --install langfuse langfuse/langfuse -f langfuse-helm.values.yml

oc delete secret parasol-app-creds || true
oc create secret generic parasol-app-creds \
  --from-literal=OPENAI_API_KEY=${OPENAI_API_KEY} \
  --from-literal=COHERE_API_KEY=${COHERE_API_KEY} \
  --from-literal=GEMINI_API_KEY=${GEMINI_API_KEY}
oc delete deployment parasol-app || true

oc apply -f src/main/kubernetes/dependencies.yml
./mvnw clean package -DskipTests \
  -Dquarkus.kubernetes.deploy=true \
  -Dquarkus.profile=openshift \
  -Dquarkus.container-image.group=$(oc project -q)
#!/bin/zsh -e

# Required environment variables - fail fast with a clear message
for required_var in DOCKERHUB_READONLY_PAT OPENAI_API_KEY COHERE_API_KEY GEMINI_API_KEY; do
  if [[ -z "${(P)required_var}" ]]; then
    echo "ERROR: required environment variable '${required_var}' is not set (or not exported)." >&2
    exit 1
  fi
done

# Some cleanup
oc delete clusterrole langfuse-s3-rw-cr --ignore-not-found
oc delete clusterrolebinding $(oc get clusterrolebinding -o name | grep langfuse-s3 | sed 's|.*/||') --ignore-not-found 2>/dev/null || true

# Need to install requirements according to https://github.com/langfuse/langfuse-k8s/tree/main/examples/minimal-installation

##################
# Cert manager
#helm install cert-manager oci://quay.io/jetstack/charts/cert-manager \
#  --version v1.21.2 \
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
  --version 0.0.8 \
  --namespace clickhouse-operator \
  --create-namespace \
  --rollback-on-failure \
  --wait

oc wait --for=condition=Established \
  crd/clickhouseclusters.clickhouse.com \
  crd/keeperclusters.clickhouse.com \
  --timeout=600s

# Set up docker pull secret.
# The same Docker Hub credentials are registered for `docker.langfuse.com` (Langfuse's Docker Hub
# mirror) as well as `docker.io`/`index.docker.io`, because the langfuse chart also pulls postgres,
# valkey, seaweedfs & clickhouse images straight from Docker Hub. Without credentials for those
# registries the pulls are anonymous and hit the unauthenticated pull rate limit.
DOCKER_USERNAME=edeandrea
DOCKER_EMAIL=eric.deandrea@gmail.com
DOCKER_AUTH=$(printf '%s:%s' "${DOCKER_USERNAME}" "${DOCKERHUB_READONLY_PAT}" | base64 | tr -d '\n')
DOCKER_CONFIG_JSON='{"auths":{'
for registry in docker.langfuse.com docker.io index.docker.io 'https://index.docker.io/v1/'; do
  DOCKER_CONFIG_JSON+="\"${registry}\":{\"username\":\"${DOCKER_USERNAME}\",\"password\":\"${DOCKERHUB_READONLY_PAT}\",\"email\":\"${DOCKER_EMAIL}\",\"auth\":\"${DOCKER_AUTH}\"},"
done
DOCKER_CONFIG_JSON="${DOCKER_CONFIG_JSON%,}}}"

oc delete secret dockerhub-auth --ignore-not-found
oc create secret generic dockerhub-auth \
  --type=kubernetes.io/dockerconfigjson \
  --from-literal=.dockerconfigjson="${DOCKER_CONFIG_JSON}"
oc secrets link default dockerhub-auth --for=pull

# Need to helm install langfuse according to https://langfuse.com/self-hosting/deployment/kubernetes-helm#deploy-the-helm-chart
helm repo add langfuse https://langfuse.github.io/langfuse-k8s
helm repo update langfuse
helm upgrade --install langfuse langfuse/langfuse \
  --version 2.1.3 \
  -f langfuse-helm.values.yml

oc delete secret parasol-app-creds --ignore-not-found
oc create secret generic parasol-app-creds \
  --from-literal=OPENAI_API_KEY="${OPENAI_API_KEY}" \
  --from-literal=COHERE_API_KEY="${COHERE_API_KEY}" \
  --from-literal=GEMINI_API_KEY="${GEMINI_API_KEY}"
oc delete deployment parasol-app --ignore-not-found

oc apply -f src/main/kubernetes/dependencies.yml
./mvnw clean package -DskipTests \
  -Dquarkus.kubernetes.deploy=true \
  -Dquarkus.profile=openshift \
  -Dquarkus.container-image.group=$(oc project -q)

echo
echo "Roundcube webmail (log in as any address, any password): https://$(oc get route roundcube -o jsonpath='{.spec.host}')"
#!/bin/zsh -e

# Need to helm install langfuse according to https://langfuse.com/self-hosting/deployment/kubernetes-helm#deploy-the-helm-chart
helm repo add langfuse https://langfuse.github.io/langfuse-k8s
helm repo update
helm upgrade --install langfuse langfuse/langfuse -f langfuse-helm.values.yml
oc service expose langfuse-web

oc delete secret parasol-app-creds
oc create secret generic parasol-app-creds --from-literal=OPENAI_API_KEY=${OPENAI_API_KEY} --from-literal=COHERE_API_KEY=${COHERE_API_KEY}
#oc delete deployment parasol-app

oc apply -f src/main/kubernetes/dependencies.yml
./mvnw clean package -DskipTests \
  -Dquarkus.kubernetes.deploy=true \
  -Dquarkus.profile=openshift \
  -Dquarkus.container-image.group=$(oc project -q)
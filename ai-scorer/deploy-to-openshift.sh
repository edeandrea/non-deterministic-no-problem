#!/bin/zsh

oc delete secret scorer-app-creds
oc create secret generic scorer-app-creds --from-literal=COHERE_API_KEY=${COHERE_API_KEY} --from-literal=GEMINI_API_KEY=${GEMINI_API_KEY}
oc delete deployment ai-scorer

oc apply -f src/main/kubernetes/dependencies.yml
./mvnw clean package -DskipTests \
  -Dquarkus.kubernetes.deploy=true \
  -Dquarkus.profile=openshift \
  -Dquarkus.container-image.group=$(oc project -q)
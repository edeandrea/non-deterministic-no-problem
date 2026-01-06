#!/bin/zsh

oc delete secret app-creds
oc create secret generic app-creds --from-literal=OPENAI_API_KEY=${OPENAI_API_KEY}
oc delete deployment non-deterministic

./mvnw clean package -DskipTests \
  -Dquarkus.kubernetes.deploy=true \
  -Dquarkus.profile=openshift \
  -Dquarkus.container-image.group=$(oc project -q)
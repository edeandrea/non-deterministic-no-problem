#!/bin/zsh

cd parasol-app
deploy-to-openshift.sh

cd ../ai-scorer
deploy-to-openshift.sh
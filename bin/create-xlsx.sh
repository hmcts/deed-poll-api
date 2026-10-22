#!/usr/bin/env bash
# Converts the generated DEEDPOLL definition (build/definitions/DEEDPOLL, from `generateCCDConfig`) into the
# spreadsheet the "High Level Data Setup" stage imports: build/definitions/CCD_Definition_DEEDPOLL_<env>.xlsx.
# As pcs-api's bin/create-xlsx.sh, with CCD's definition-processor image.
#
# Usage: bin/create-xlsx.sh <env>   (aat, ithc, prod, local, ...)
set -euo pipefail

env_name="${1:-}"
if [ -z "$env_name" ]; then
  echo "Usage: bin/create-xlsx.sh <env>  (the importer reads CCD_Definition_DEEDPOLL_<env>.xlsx)" >&2
  exit 2
fi
case_type="DEEDPOLL"
definitions_dir="$(pwd)/build/definitions"

if [ ! -d "$definitions_dir/$case_type" ]; then
  echo "No $definitions_dir/$case_type: run generateCCDConfig with CCD_CASE_TYPE_ID=$case_type first." >&2
  exit 1
fi

# On a CNP Jenkins agent the image registry needs the agent's managed identity, signed in under the agent's own
# Azure config. An environment agent (which library 2.12.2 runs this stage on) keeps it in
# /opt/jenkins/.azure-<DEPLOYMENT_ENVIRONMENT>, not .azure-nonprod. Locally, `az acr login --name hmctsprod`
# beforehand is enough. On Jenkins, a missing config is an error rather than a skipped login: skipping it leaves
# an unauthenticated pull that fails with a misleading registry message.
config_dir=""
for candidate in "${AZURE_CONFIG_DIR:-}" "/opt/jenkins/.azure-${DEPLOYMENT_ENVIRONMENT:-}" /opt/jenkins/.azure-nonprod; do
  if [ -n "$candidate" ] && [ "$candidate" != "/opt/jenkins/.azure-" ] && [ -d "$candidate" ]; then
    config_dir="$candidate"
    break
  fi
done
if [ -n "$config_dir" ]; then
  echo "Signing in to hmctsprod with the agent identity under $config_dir"
  export AZURE_CONFIG_DIR="$config_dir"
  az login --identity >/dev/null
  az acr login --name hmctsprod --subscription DCD-CNP-PROD
elif [ -n "${JENKINS_URL:-}" ]; then
  echo "No agent Azure config found (AZURE_CONFIG_DIR, /opt/jenkins/.azure-${DEPLOYMENT_ENVIRONMENT:-<unset>}, /opt/jenkins/.azure-nonprod)" >&2
  exit 1
fi

docker run --rm \
  -v "$definitions_dir/$case_type:/tmp/ccd-input" \
  -v "$definitions_dir:/tmp/ccd-output" \
  hmctsprod.azurecr.io/ccd/definition-processor:latest \
  json2xlsx -D /tmp/ccd-input -o "/tmp/ccd-output/CCD_Definition_${case_type}_${env_name}.xlsx"

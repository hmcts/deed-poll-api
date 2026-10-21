#!/usr/bin/env bash
# Converts the generated DEEDPOLL definition (build/definitions/DEEDPOLL, from `generateCCDConfig`) into the
# spreadsheet the "High Level Data Setup" stage imports: build/definitions/CCD_Definition_DEEDPOLL_<env>.xlsx.
# As pcs-api's bin/create-xlsx.sh, with CCD's definition-processor image.
#
# Usage: bin/create-xlsx.sh <env>   (aat, ithc, prod, ...; defaults to `local`)
set -euo pipefail

env_name="${1:-local}"
case_type="DEEDPOLL"
definitions_dir="$(pwd)/build/definitions"

if [ ! -d "$definitions_dir/$case_type" ]; then
  echo "No $definitions_dir/$case_type: run generateCCDConfig with CCD_CASE_TYPE_ID=$case_type first." >&2
  exit 1
fi

# On a CNP Jenkins agent the image registry needs the agent's managed identity. Locally, `az acr login
# --name hmctsprod` beforehand is enough, so this only runs where the agent's Azure config exists.
if [ -d /opt/jenkins/.azure-nonprod ]; then
  export AZURE_CONFIG_DIR=/opt/jenkins/.azure-nonprod
  az login --identity >/dev/null
  az acr login --name hmctsprod --subscription DCD-CNP-PROD
fi

docker run --rm \
  -v "$definitions_dir/$case_type:/tmp/ccd-input" \
  -v "$definitions_dir:/tmp/ccd-output" \
  hmctsprod.azurecr.io/ccd/definition-processor:latest \
  json2xlsx -D /tmp/ccd-input -o "/tmp/ccd-output/CCD_Definition_${case_type}_${env_name}.xlsx"

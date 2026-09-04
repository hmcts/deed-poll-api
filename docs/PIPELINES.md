# CI/CD Pipelines Guide

This document explains the GitHub Actions pipeline architecture, including the design philosophy, pipeline structure, and how to extend the system.

## Pipeline Philosophy

The pipeline system follows a **modular, hierarchical structure**:

```
Workflow (workflow.preview.yml, workflow.main.yml)
    └── Stage (stage.build.yml, stage.deploy.yml, etc.)
        └── Job (job.lint.yml, job.test.yml, etc.)
```

**Design Principles:**
- **Separation of Concerns**: Each job has a single responsibility
- **Reusability**: Jobs and stages can be reused across pipelines
- **Parallel Execution**: Jobs within a stage run in parallel
- **Sequential Stages**: Stages run in order with dependencies
- **Self-Documenting**: Each job has its own README.md

## Directory Structure

GitHub Actions requires all reusable workflows to be at the top level of `.github/workflows/`. We use a dot-prefix naming convention to organize them:

```
.github/workflows/
├── workflow.preview.yml           # PR pipeline
├── workflow.main.yml              # Master branch pipeline
├── workflow.claude.yml            # AI assistant
├── stage.build.yml                # Build stage workflow
├── stage.infrastructure.yml       # Infrastructure stage workflow
├── stage.deploy.yml               # Deploy stage workflow
├── stage.smoke-test.yml           # Smoke test stage workflow
├── stage.e2e.yml                  # E2E stage workflow
├── job.detect-changes.yml         # Change detection job
├── job.lint.yml                   # Lint job
├── job.terraform-fmt.yml          # Terraform format check job
├── job.test.yml                   # Test job
├── job.osv-scanner.yml            # Security scanning job
├── job.build-and-publish-images.yml  # Build images job
├── job.terraform.yml              # Terraform plan/apply job
├── job.helm-deploy.yml            # Helm deploy job
├── job.pr-comment.yml             # PR comment job
├── job.smoke-test.yml             # Smoke test job
├── job.e2e-test.yml               # E2E test job
└── jobs/                          # Documentation and scripts
    ├── detect-changes/
    │   └── README.md
    ├── lint/
    │   └── README.md
    ├── test/
    │   └── README.md
    ├── osv-scanner/
    │   └── README.md
    ├── build-and-publish-images/
    │   ├── README.md
    │   ├── detect-affected-apps.sh
    │   ├── generate-build-metadata.sh
    │   └── set-image-variables.sh
    ├── terraform-fmt/
    │   └── README.md
    ├── terraform/
    │   └── README.md
    ├── helm-deploy/
    │   ├── README.md
    │   └── deploy-preview.sh
    ├── pr-comment/
    │   ├── README.md
    │   └── get-deployment-urls.sh
    ├── smoke-test/
    │   └── README.md
    └── e2e-test/
        └── README.md
```

**Naming Convention:**
- `workflow.*.yml` - Main pipeline workflows
- `stage.*.yml` - Stage workflows that orchestrate jobs
- `job.*.yml` - Individual job workflows
- `jobs/*/` - Documentation and scripts for each job

## Pipelines

### Preview Pipeline (`workflow.preview.yml`)

**Trigger:** Pull requests to `master` branch

**Flow:**
```
┌─────────────────────────────────────────────────────────────────────┐
│                         Preview Pipeline                             │
├─────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  ┌────────────────────────┐    ┌─────────────────────────┐          │
│  │ detect-code-changes    │    │ detect-infra-changes    │          │
│  └───────────┬────────────┘    └────────────┬────────────┘          │
│              │                              │                        │
│              ▼                              ▼                        │
│  ┌────────────────────────┐    ┌─────────────────────────┐          │
│  │ BUILD STAGE            │    │ INFRASTRUCTURE STAGE    │          │
│  │ (if code changes)      │    │ (if infra changes)      │          │
│  │  ┌──────┐ ┌──────┐     │    │  ┌────────┐             │          │
│  │  │ Lint │ │ Test │     │    │  │ TF Fmt │             │          │
│  │  └──────┘ └──────┘     │    │  └───┬────┘             │          │
│  │  ┌──────────┐          │    │      ▼                  │          │
│  │  │ OSV Scan │          │    │  ┌────────────────────┐ │          │
│  │  └──────────┘          │    │  │ Terraform Plan     │ │          │
│  │  ┌───────────────┐     │    │  └────────────────────┘ │          │
│  │  │ Build Images  │     │    └─────────────────────────┘          │
│  │  └───────────────┘     │                                         │
│  └───────────┬────────────┘                                         │
│              │                                                       │
│              ▼                                                       │
│  ┌─────────────────────────────────────────────────────┐            │
│  │ DEPLOY STAGE (if build succeeded)                    │            │
│  │  ┌────────────────────────────────────────────────┐ │            │
│  │  │ Helm Deploy                                     │ │            │
│  │  └────────────────────────────────────────────────┘ │            │
│  └─────────────────────────┬───────────────────────────┘            │
│                            ▼                                         │
│  ┌─────────────────────────────────────────────────────┐            │
│  │ SMOKE TEST STAGE                                     │            │
│  │  ┌──────────────┐ ┌─────────────────────────────┐   │            │
│  │  │ PR Comment   │ │ Smoke Test (health checks)  │   │            │
│  │  └──────────────┘ └─────────────────────────────┘   │            │
│  └─────────────────────────┬───────────────────────────┘            │
│                            ▼                                         │
│  ┌─────────────────────────────────────────────────────┐            │
│  │ E2E STAGE                                            │            │
│  │  ┌────────────────────────────────────────────────┐ │            │
│  │  │ Playwright E2E Tests                            │ │            │
│  │  └────────────────────────────────────────────────┘ │            │
│  └─────────────────────────────────────────────────────┘            │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

**Change Detection:**
- Code changes (`yarn.lock`, `apps/`, `libs/`) → Build Stage
- Infrastructure changes (`infrastructure/`) → Infrastructure Stage
- Both run in parallel; stages are skipped if no relevant changes detected

**Concurrency:** One deployment per PR (new pushes cancel in-progress runs)

### Main Pipeline (`workflow.main.yml`)

**Trigger:** Push to `master` branch

**Flow:**
```
┌─────────────────────────────────────────────────────────────────────┐
│                           Main Pipeline                              │
├─────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  ┌────────────────────────┐    ┌─────────────────────────┐          │
│  │ detect-code-changes    │    │ detect-infra-changes    │          │
│  └───────────┬────────────┘    └────────────┬────────────┘          │
│              │                              │                        │
│              ▼                              ▼                        │
│  ┌────────────────────────┐    ┌─────────────────────────┐          │
│  │ BUILD STAGE            │    │ INFRASTRUCTURE STAGE    │          │
│  │ (if code changes)      │    │ (if infra changes)      │          │
│  │  ┌──────┐ ┌──────┐     │    │  ┌────────┐             │          │
│  │  │ Lint │ │ Test │     │    │  │ TF Fmt │             │          │
│  │  └──────┘ └──────┘     │    │  └───┬────┘             │          │
│  │  ┌──────────┐          │    │      ▼                  │          │
│  │  │ OSV Scan │          │    │  ┌────────────────────┐ │          │
│  │  └──────────┘          │    │  │ Terraform Apply    │ │          │
│  │  ┌───────────────┐     │    │  └────────────────────┘ │          │
│  │  │ Build Images  │     │    └─────────────────────────┘          │
│  │  └───────────────┘     │                                         │
│  └───────────┬────────────┘                                         │
│              │                                                       │
│              ▼                                                       │
│  ┌───────────────────────────────────────────────────────────────┐  │
│  │ DEPLOY STAGE → SMOKE TEST → E2E → CLEANUP → PROMOTE           │  │
│  │ (if build succeeded)                                           │  │
│  └───────────────────────────────────────────────────────────────┘  │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

**Change Detection:**
- Code changes (`yarn.lock`, `apps/`, `libs/`) → Build Stage
- Infrastructure changes (`infrastructure/`) → Infrastructure Stage (with Apply)
- Both run in parallel; stages are skipped if no relevant changes detected

**Purpose:** Validate code on merge, run full test suite for coverage reporting, apply infrastructure changes

## Stages

### Build Stage

**Purpose:** Code quality, testing, security scanning, and image building

**Condition:** Only runs if code changes detected (`yarn.lock`, `apps/`, `libs/`)

**Jobs (parallel):**
| Job | Purpose |
|-----|---------|
| Lint | Biome linting + Prisma schema validation |
| Test | Unit tests with coverage + SonarCloud analysis |
| OSV Scanner | Dependency vulnerability scanning |
| Build Images | Docker image build and push to ACR |

**Outputs:**
- `affected-apps`: Apps that changed
- `has-changes`: Whether deployment is needed
- `timestamp`: Build timestamp
- `short-sha`: Git SHA for tagging

### Infrastructure Stage

**Purpose:** Validate and apply infrastructure changes via Terraform

**Condition:** Only runs if infrastructure changes detected (`infrastructure/`)

**Jobs (sequential):**
| Job | Purpose |
|-----|---------|
| Terraform Format | Terraform code formatting check |
| Terraform | Terraform plan (PRs) or plan+apply (main branch) |

**Inputs:** None (uses hardcoded `aat` environment and `nonprod` subscription)
**Outputs:** `plan-exitcode` (0=no changes, 2=changes detected)

**Behavior:**
- Runs in parallel with Build Stage (independent change detection)
- PRs: Plan only (no infrastructure changes applied)
- Main branch: Plan + Apply

### Deploy Stage

**Purpose:** Deploy to Kubernetes using Helm

**Jobs:**
| Job | Purpose |
|-----|---------|
| Helm Deploy | Deploy umbrella chart to AKS |

**Inputs:** `timestamp`, `short-sha`, `affected-apps`, `helm-apps`
**Outputs:** `preview-urls` (base64-encoded JSON)

### Smoke Test Stage

**Purpose:** Verify deployment succeeded

**Jobs (parallel):**
| Job | Purpose |
|-----|---------|
| PR Comment | Post deployment URLs to PR |
| Smoke Test | Health check deployed services |

**Inputs:** `preview-urls`

### E2E Stage

**Purpose:** Full end-to-end testing

**Jobs:**
| Job | Purpose |
|-----|---------|
| E2E Test | Playwright tests against preview environment |

**Inputs:** `preview-urls`

## Jobs

Each job has:
- `job.*.yml`: The reusable workflow (at top level)
- `jobs/*/README.md`: Documentation with inputs, outputs, secrets, artifacts
- `jobs/*/*.sh`: Any required scripts

### Job Documentation Format

Each job's README.md follows this structure:

```markdown
# {Job Name}

## Purpose
Brief description of what the job does.

## Inputs
| Type | Name | Required | Description |

## Artifacts
| Direction | Name | Description |

## Environment Variables
| Name | Required | Description |

## Secrets
| Name | Required | Description |

## Outputs
| Name | Description |
```

## Data Flow

### Output Passing Chain

```
build-and-publish-images-job.yml
  → outputs: affected-apps, has-changes, timestamp, short-sha
    → build-stage.yml exposes these outputs
      → preview.yml passes to deploy-stage.yml
        → helm-deploy-job.yml outputs: preview-urls
          → deploy-stage.yml exposes preview-urls
            → preview.yml passes to smoke-test-stage and e2e-stage
```

### Secrets Inheritance

Secrets are passed using `secrets: inherit`:

```yaml
jobs:
  build-stage:
    uses: ./.github/workflows/stage.build.yml
    secrets: inherit  # Passes all repository secrets
```

## Image Tagging Strategy

### Dual Tag Approach

**For affected apps (rebuilt):**
- Tag: `pr-{PR#}-{SHA}` (e.g., `pr-123-abc1234`)
- Forces Kubernetes to pull new image

**For unaffected apps:**
- Tag: `pr-{PR#}` (e.g., `pr-123`)
- Uses existing image, no pod restart

### Why This Matters

Kubernetes only pulls images if the tag changes. Using dual tagging:
1. Ensures changed apps always deploy fresh images
2. Avoids unnecessary restarts for unchanged apps
3. Optimizes deployment time

## Adding New Jobs

### 1. Create Workflow File

```yaml
# .github/workflows/job.my-new-job.yml
name: My New Job

on:
  workflow_call:
    inputs:
      some-input:
        required: true
        type: string
    outputs:
      some-output:
        description: "Description"
        value: ${{ jobs.my-job.outputs.result }}

jobs:
  my-job:
    name: My New Job
    runs-on: ubuntu-latest
    outputs:
      result: ${{ steps.action.outputs.result }}

    steps:
      - name: Checkout
        uses: actions/checkout@v6

      - name: Do something
        id: action
        run: echo "result=value" >> "$GITHUB_OUTPUT"
```

### 2. Create Documentation Folder

```bash
mkdir -p .github/workflows/jobs/my-new-job
```

### 3. Create README.md

Document the job following the standard format in `jobs/my-new-job/README.md`.

### 4. Add to Stage

```yaml
# In the appropriate stage.*.yml file
jobs:
  my-new-job:
    name: My New Job
    uses: ./.github/workflows/job.my-new-job.yml
    with:
      some-input: ${{ inputs.some-value }}
```

## Adding New Stages

### 1. Create Stage File

```yaml
# .github/workflows/stage.my-stage.yml
name: My Stage

on:
  workflow_call:
    inputs:
      required-input:
        required: true
        type: string
    outputs:
      stage-output:
        value: ${{ jobs.job-name.outputs.result }}

jobs:
  job-one:
    uses: ./.github/workflows/job.job-one.yml
    with:
      input: ${{ inputs.required-input }}

  job-two:
    uses: ./.github/workflows/job.job-two.yml
```

### 2. Add to Pipeline

```yaml
# In preview.yml or main.yml
jobs:
  my-stage:
    needs: [previous-stage]
    uses: ./.github/workflows/stage.my-stage.yml
    with:
      required-input: ${{ needs.previous-stage.outputs.value }}
    secrets: inherit
```

## Debugging Pipelines

### View Workflow Logs

1. Go to Actions tab in GitHub
2. Select the workflow run
3. Click on the failed job
4. Expand step logs

### Local Testing

Test scripts locally before committing:

```bash
# Set up mock GitHub environment
export GITHUB_OUTPUT=/tmp/github_output.txt
export GITHUB_ENV=/tmp/github_env.txt

# Run script
.github/workflows/jobs/build-and-publish-images/detect-affected-apps.sh

# Check outputs
cat /tmp/github_output.txt
```

### Common Issues

**"Workflow not found"**
- Check file path is correct
- Ensure `on: workflow_call` is present

**"Input required but not provided"**
- Verify all required inputs are passed from parent workflow

**"Secret not found"**
- Ensure `secrets: inherit` is set
- Or explicitly pass secrets

**"Output not available"**
- Check job has `outputs:` section
- Verify `GITHUB_OUTPUT` is used correctly

## GitHub Actions Constraints

- **Nesting depth**: Maximum 4 levels of reusable workflows
- **Matrix limit**: 256 jobs in a matrix
- **Timeout**: Default 360 minutes (configurable per job)
- **Concurrency**: Managed by `concurrency` block

## References

- **Deployment Guide**: `docs/DEPLOYMENT.md`
- **Architecture**: `docs/ARCHITECTURE.md`
- **Job Documentation**: `.github/workflows/jobs/*/README.md`
- **Scripts**: `.github/scripts/README.md`
- [GitHub Actions Documentation](https://docs.github.com/en/actions)
- [Reusable Workflows](https://docs.github.com/en/actions/using-workflows/reusing-workflows)

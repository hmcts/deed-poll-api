# Deployment Guide

> **⚠️ This file describes the sibling repo `hmcts/deed-poll-admin`, not this one.** It was copied in
> before any code existed here. The umbrella chart at `helm/deed-poll-admin/` and the per-app
> `apps/*/helm/` directories it describes do not exist in this repo.
>
> **`deed-poll-api` is not onboarded for deployment at all yet.** It has one chart,
> `charts/rpe-spring-boot-template`, which is still the template's — wrong name, wrong image, wrong
> ingress host, and no probe path configured. Deployment is Jenkins (`Jenkinsfile_CNP` — there is
> deliberately no `Jenkinsfile_nightly`), and onboarding it is outstanding work outside the build
> plans.
>
> Read [`RESUME.md`](RESUME.md) for the current state of that. This file needs rewriting or deleting.

This guide covers deployment procedures for the application, including both preview and production environments.

## Overview

The application is deployed to Azure Kubernetes Service (AKS) using Helm charts. The deployment strategy differs between environments:

| Environment | Trigger | Cluster | Purpose |
|-------------|---------|---------|---------|
| Preview | Pull Request | `cft-preview-01-aks` | Isolated testing per PR |
| Production | Manual/Merge to master | TBD | Live application |

## Prerequisites

### Access Requirements

- **Azure Portal**: Access to HMCTS Azure subscription
- **AKS Cluster**: kubectl credentials for target cluster
- **Container Registry**: Push access to `hmctsprod.azurecr.io`
- **GitHub**: Repository write access for workflow triggers

### Tools Required

```bash
# Install required tools
brew install azure-cli kubernetes-cli helm

# Azure login
az login

# Get AKS credentials (preview example)
az aks get-credentials \
  --resource-group cft-preview-01-rg \
  --name cft-preview-01-aks \
  --admin
```

## Helm Chart Architecture

### Umbrella Chart Structure

```
helm/deed-poll-admin/
├── Chart.yaml                    # Dependencies on sub-charts
├── values.yaml                   # Default values
├── values.preview.template.yaml  # Preview environment template
└── charts/                       # Cached dependencies
```

### Sub-Charts

Each application has its own Helm chart:

```
apps/{app}/helm/
├── Chart.yaml        # Depends on HMCTS nodejs/job base charts
├── values.yaml       # Default application values
└── charts/           # Base chart dependencies
```

### Dependencies

| Dependency | Source | Purpose |
|------------|--------|---------|
| `deed-poll-admin-web` | Local (`apps/web/helm`) | Web frontend |
| `deed-poll-admin-api` | Local (`apps/api/helm`) | REST API |
| `deed-poll-admin-crons` | Local (`apps/crons/helm`) | Scheduled jobs |
| `deed-poll-admin-postgres` | Local (`apps/postgres/helm`) | Database migrations |
| `postgresql` | `oci://hmctsprod.azurecr.io/helm` | PostgreSQL database |

## Environment Configuration

### Preview Environment

Preview environments are automatically created for each pull request:

**Naming Convention:**
- Release: `{app-name}-pr-{PR#}` (e.g., `deed-poll-admin-pr-123`)
- Namespace: `{team}` (e.g., `deed-poll`)
- URLs: `https://{team}-{release}-{service}.preview.platform.hmcts.net`

**Automatic Cleanup:**
- Environments are deleted when PRs are closed
- Labels `ns:`, `prd:`, `rel:` track cleanup targets

### Production Environment

Production configuration should include:

**Key Differences from Preview:**
- Persistent database (not ephemeral)
- Proper resource limits and requests
- Horizontal pod autoscaling
- Production-grade secrets management

**Example Production Values:**
```yaml
# values.production.yaml
global:
  environment: prod
  enableKeyVaults: true
  devMode: false

web:
  nodejs:
    replicas: 3
    resources:
      requests:
        cpu: 500m
        memory: 512Mi
      limits:
        cpu: 1000m
        memory: 1Gi
```

## Database Migrations

Database migrations are handled by the `postgres` application:

### Migration Process

1. **Build**: Creates Docker image with Prisma migrations
2. **Deploy**: Kubernetes Job runs `yarn db:migrate`
3. **Wait**: Helm waits for job completion before proceeding
4. **Verify**: Check migration status in Prisma Studio

### Running Migrations Manually

```bash
# From local machine with database access
yarn db:migrate

# Or apply pending migrations only
yarn db:migrate:dev

# View database
yarn db:studio
```

### Rollback Procedure

Prisma doesn't support automatic rollback. For production issues:

1. **Assess Impact**: Determine if rollback is necessary
2. **Create Fix Migration**: Generate migration to reverse changes
3. **Deploy Fix**: Run through normal deployment pipeline
4. **Verify**: Confirm data integrity

```bash
# Generate new migration to fix issues
yarn db:migrate:dev --name fix_previous_migration
```

## Secrets Management

### Azure Key Vault Integration

Secrets are injected from Azure Key Vault:

```yaml
# In Helm values
keyVaults:
  deed-poll:
    secrets:
      - name: AppInsightsConnectionString
        alias: APPLICATION_INSIGHTS_CONNECTION_STRING
      - name: postgres-url
        alias: DATABASE_URL
```

### Required Secrets

| Secret | Description |
|--------|-------------|
| `AppInsightsConnectionString` | Application Insights telemetry |
| `postres-url` | PostgreSQL connection string of Terraform DB |

### Adding New Secrets

1. Add secret to Azure Key Vault
2. Update Helm values to reference it
3. Update application to use the environment variable

## Health Checks

### Liveness Probe

Checks if the application is running:

```yaml
livenessProbe:
  httpGet:
    path: /health
    port: 3000
  initialDelaySeconds: 30
  periodSeconds: 10
```

### Readiness Probe

Checks if the application can accept traffic:

```yaml
readinessProbe:
  httpGet:
    path: /health
    port: 3000
  initialDelaySeconds: 5
  periodSeconds: 5
```

## Troubleshooting

### Common Issues

#### Pods Not Starting

```bash
# Check pod status
kubectl get pods -n {namespace}

# View pod details
kubectl describe pod {pod-name} -n {namespace}

# Check logs
kubectl logs {pod-name} -n {namespace}
```

**Common Causes:**
- Image pull errors (check registry credentials)
- Resource limits exceeded (OOMKilled)
- Health check failures
- Missing secrets or configmaps

#### Helm Release Stuck

The deployment scripts handle this automatically, but for manual recovery:

```bash
# Check release status
helm status {release-name} -n {namespace}

# View history
helm history {release-name} -n {namespace}

# Rollback to previous version
helm rollback {release-name} {revision} -n {namespace}

# Force uninstall (last resort)
helm uninstall {release-name} -n {namespace} --no-hooks
```

#### Database Connection Issues

```bash
# Check postgres pod
kubectl get pods -n {namespace} | grep postgres

# View connection logs
kubectl logs {postgres-pod} -n {namespace}

# Test connection from app pod
kubectl exec -it {app-pod} -n {namespace} -- \
  /bin/sh -c 'echo "SELECT 1;" | psql $DATABASE_URL'
```

### Manual Deployment

If automated deployment fails:

```bash
# 1. Build image locally
docker build -t hmctsprod.azurecr.io/deed-poll/app:manual \
  -f apps/web/Dockerfile .

# 2. Push to registry
docker push hmctsprod.azurecr.io/deed-poll/app:manual

# 3. Deploy with Helm
cd helm/deed-poll-admin
helm dependency update
helm upgrade --install {release-name} . \
  --namespace {namespace} \
  --values values.yaml \
  --set web.nodejs.image=hmctsprod.azurecr.io/deed-poll/app:manual \
  --wait \
  --timeout 10m
```

## Monitoring

### Application Insights

Azure Application Insights provides:
- Request/response metrics
- Error tracking and alerting
- Performance monitoring
- Distributed tracing

### Kubernetes Monitoring

```bash
# Real-time pod metrics
kubectl top pods -n {namespace}

# Event log
kubectl get events -n {namespace} --sort-by='.lastTimestamp'

# Resource usage
kubectl describe resourcequota -n {namespace}
```

### Prisma Studio

Access database through Prisma Studio:
- Preview: `https://{team}-{release}-postgres.preview.platform.hmcts.net`
- Production: Internal access only

## Rollback Procedures

### Helm Rollback

```bash
# List revisions
helm history {release-name} -n {namespace}

# Rollback to specific revision
helm rollback {release-name} {revision} -n {namespace}

# Verify rollback
kubectl get pods -n {namespace}
```

### Image Rollback

If a specific image version needs to be reverted:

```bash
# Update image tag
helm upgrade {release-name} . \
  --namespace {namespace} \
  --set web.nodejs.image=hmctsprod.azurecr.io/deed-poll/app:previous-tag \
  --reuse-values
```

## References

- **Pipelines**: `docs/PIPELINES.md` - CI/CD pipeline documentation
- **Architecture**: `docs/ARCHITECTURE.md` - System architecture
- **Scripts**: `.github/scripts/README.md` - Helper scripts
- **Job Documentation**: `.github/workflows/jobs/*/README.md` - Individual job details

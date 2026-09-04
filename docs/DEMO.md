## HMCTS Monorepo Agentic Capabilities

### Code base
- Monorepo structure
- Key directories and files

### CLAUDE\.md
- Agent guidance
- Naming conventions enforcement
- HMCTS engineering standards

### Hooks
- **post-write.sh** - formatting/linting
- **pre-commit.sh** - unit test validation
- **pre-pr.sh** - e2e tests
- Logging

### Claude Commands
- **/prime** - Load project context
- **/optimize** - Run performance optimization
- **/commit** - Demonstrate git workflow automation
- **/pr** - Create PR with auto-generated description

## MCP Servers Integration
- **JIRA** - Pull ticket details and search issues
- **Playwright** - Automated browser testing

## Sub Agents
- **infrastructure-engineer** - Create Helm charts
- **test-engineer** - Generate Playwright tests
- **code-reviewer** - Proactive code review
- **full-stack-engineer** - Build GOV.UK components
- **ui-ux-engineer** - Accessibility audit

## Workflow
- **/plan** - Start with a JIRA ticket, generate specification.md and tasks.md
- **/implement** - Show full-stack agent orchestration

## Part 7: GitHub Actions Integration
- Automated security analysis
- PR comment feedback

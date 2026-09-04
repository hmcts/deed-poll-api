# Claude Code CLI Cheat Sheet

## Keyboard Shortcuts

### General Controls
```
Ctrl+C                    # Cancel current input or generation
Ctrl+D                    # Exit Claude Code session
Ctrl+L                    # Clear terminal screen (keeps conversation history)
Ctrl+R                    # Reverse search command history
Ctrl+O                    # Show thinking
Esc + Esc                 # Rewind the code/conversation to previous state
Tab                       # Toggle extended thinking mode
Shift+Tab                 # Toggle permission modes (Auto-Accept, Plan Mode, normal)
```

### Quick Commands
```
# at start                # Memory shortcut - add to CLAUDE.md
/ at start                # Slash command
! at start                # Bash mode - run commands directly
```

### Background Commands
```
Ctrl+B                    # Move current bash command to background
                          # (Tmux users: press Ctrl+B twice)
```

For vim mode keybindings, run `/vim` and see the [interactive mode docs](https://docs.claude.com/en/docs/claude-code/interactive-mode).

## Built-in Commands

### Context Management
```bash
/context                    # Add files/folders to context for current conversation
/compact                    # Compress conversation history to reduce token usage
/rewind                     # Roll back to previous state in conversation
```

## Project Custom Commands

### Quick Actions
```bash
/commit                     # Check branch, commit changes with clear message
/pr                         # Create/update PR with summary and test plan
/prime                      # Load project context (README, ARCHITECTURE, current branch spec)
/optimize                   # Performance optimization guide and analysis
```

### Workflows

#### Orchestrated (Step-by-step with review)
```bash
/wf-plan <ticket-id>       # Planning only (same as /plan)
/wf-implement <ticket-id>  # Parallel implementation without code review:
                           # - Read specification
                           # - Parallel agents: full-stack-engineer, test-engineer, infrastructure-engineer
                           # - Run quality checks (lint, test, e2e)

/wf-review <ticket-id>     # Code review and validation:
                           # - Comprehensive code review
                           # - Run all tests
                           # - Verify coverage >80%
```

**Recommended workflow:**
```bash
/wf-plan <ticket-id>       # Review and approve spec
/wf-implement <ticket-id>  # Let agents implement in parallel
/wf-review <ticket-id>     # Final review and testing
/pr                        # Create pull request
```

#### One-Shot (Autonomous)
```bash
/os-small <ticket-id>      # Quick implementation for simple tickets:
                           # - Fetch ticket, create branch
                           # - Parallel implementation (2 agents)
                           # - Run quality checks
                           # No separate planning phase

/os-large <ticket-id>      # Full autonomous implementation:
                           # - Fetch ticket, create branch
                           # - Generate specification and tasks
                           # - Parallel implementation (3 agents)
                           # - Run all tests
                           # - Code review and fixes
```


#!/usr/bin/env node

/**
 * GitHub Actions Orchestrator - State Transition Script
 * 
 * Manages state transitions for the orchestrator state machine.
 * Designed to be idempotent and safe to re-run.
 * 
 * Usage:
 *   node transition.mjs --issue <number>
 *   node transition.mjs --pr <number>
 *   node transition.mjs --repo <owner/name>
 * 
 * Environment:
 *   GITHUB_TOKEN - Required for API access
 *   GITHUB_REPOSITORY - Default repo (owner/name)
 */

import { readFile, writeFile } from 'fs/promises';
import { existsSync } from 'fs';

// Parse command line arguments
const args = process.argv.slice(2);
const options = {};
for (let i = 0; i < args.length; i += 2) {
  const key = args[i].replace('--', '');
  options[key] = args[i + 1];
}

const GITHUB_TOKEN = process.env.GITHUB_TOKEN;
const GITHUB_REPOSITORY = options.repo || process.env.GITHUB_REPOSITORY;

if (!GITHUB_TOKEN) {
  console.error('❌ Error: GITHUB_TOKEN environment variable is required');
  process.exit(1);
}

if (!GITHUB_REPOSITORY) {
  console.error('❌ Error: Repository must be specified via --repo or GITHUB_REPOSITORY env var');
  process.exit(1);
}

const [owner, repo] = GITHUB_REPOSITORY.split('/');

// State labels
const STATE_LABELS = {
  QUEUED: 'state:queued',
  RUNNING: 'state:running',
  NEEDS_REVIEW: 'state:needs-review',
  CHANGES_REQUESTED: 'state:changes-requested',
  FIXING: 'state:fixing',
  PASSED: 'state:passed',
  MERGED: 'state:merged',
  BLOCKED: 'state:blocked'
};

const ORCHESTRATOR_ENABLED = 'orchestrator:enabled';
const ORCHESTRATOR_RUN = 'orchestrator:run';

// GitHub API helper
async function githubAPI(endpoint, method = 'GET', body = null) {
  const url = `https://api.github.com${endpoint}`;
  const options = {
    method,
    headers: {
      'Authorization': `token ${GITHUB_TOKEN}`,
      'Accept': 'application/vnd.github.v3+json',
      'User-Agent': 'FishIT-Mapper-Orchestrator'
    }
  };

  if (body) {
    options.headers['Content-Type'] = 'application/json';
    options.body = JSON.stringify(body);
  }

  const response = await fetch(url, options);
  
  if (!response.ok && response.status !== 404) {
    const error = await response.text();
    throw new Error(`GitHub API error: ${response.status} ${error}`);
  }

  if (response.status === 404) {
    return null;
  }

  return await response.json();
}

// Get current state from labels
function getCurrentState(labels) {
  const stateLabel = labels.find(l => l.name.startsWith('state:'));
  if (!stateLabel) return null;
  
  for (const [state, label] of Object.entries(STATE_LABELS)) {
    if (stateLabel.name === label) {
      return state;
    }
  }
  
  return null;
}

// Update labels (remove old state, add new state)
async function updateLabels(issueNumber, oldState, newState) {
  const oldLabel = STATE_LABELS[oldState];
  const newLabel = STATE_LABELS[newState];
  
  console.log(`📝 Updating labels: ${oldLabel || 'none'} → ${newLabel}`);
  
  // Remove old state label if exists
  if (oldLabel && oldState !== null) {
    try {
      await githubAPI(`/repos/${owner}/${repo}/issues/${issueNumber}/labels/${encodeURIComponent(oldLabel)}`, 'DELETE');
    } catch (err) {
      console.log(`   Note: Could not remove label ${oldLabel} (may not exist)`);
    }
  }
  
  // Add new state label
  if (newLabel) {
    await githubAPI(`/repos/${owner}/${repo}/issues/${issueNumber}/labels`, 'POST', {
      labels: [newLabel]
    });
  }
}

// Post a comment
async function postComment(issueNumber, body) {
  console.log(`💬 Posting comment to #${issueNumber}`);
  await githubAPI(`/repos/${owner}/${repo}/issues/${issueNumber}/comments`, 'POST', { body });
}

// Read checkpoint
async function readCheckpoint() {
  const path = 'codex/CHECKPOINT.md';
  if (!existsSync(path)) {
    return {
      status: 'Idle',
      issue: null,
      pr: null,
      branch: null,
      currentTask: null,
      iteration: 0,
      lastCheck: null,
      lastCheckStatus: null,
      failures: 0,
      history: []
    };
  }
  
  const content = await readFile(path, 'utf-8');
  
  // Parse checkpoint (simple line-based parsing)
  const lines = content.split('\n');
  const checkpoint = {
    status: 'Idle',
    issue: null,
    pr: null,
    branch: null,
    currentTask: null,
    iteration: 0,
    lastCheck: null,
    lastCheckStatus: null,
    failures: 0,
    history: []
  };
  
  for (const line of lines) {
    if (line.startsWith('**Status:**')) checkpoint.status = line.split('**Status:**')[1].trim();
    if (line.startsWith('**Issue:**')) checkpoint.issue = line.split('**Issue:**')[1].trim().replace('#', '');
    if (line.startsWith('**PR:**')) checkpoint.pr = line.split('**PR:**')[1].trim().replace('#', '');
    if (line.startsWith('**Branch:**')) checkpoint.branch = line.split('**Branch:**')[1].trim();
    if (line.startsWith('**Current Task:**')) checkpoint.currentTask = line.split('**Current Task:**')[1].trim();
    if (line.startsWith('**Iteration:**')) {
      const iter = line.split('**Iteration:**')[1].trim().split('/')[0];
      checkpoint.iteration = parseInt(iter) || 0;
    }
    if (line.startsWith('**Previous Check Failures:**')) {
      checkpoint.failures = parseInt(line.split('**Previous Check Failures:**')[1].trim()) || 0;
    }
  }
  
  return checkpoint;
}

// Write checkpoint
async function writeCheckpoint(checkpoint) {
  const content = `# Orchestrator Checkpoint

**Status:** ${checkpoint.status}
**Issue:** ${checkpoint.issue ? '#' + checkpoint.issue : 'N/A'}
**PR:** ${checkpoint.pr ? '#' + checkpoint.pr : 'N/A'}
**Branch:** ${checkpoint.branch || 'N/A'}
**Current Task:** ${checkpoint.currentTask || 'N/A'}
**Iteration:** ${checkpoint.iteration}/5
**Last Check:** ${checkpoint.lastCheck || 'Never'}
**Last Check Status:** ${checkpoint.lastCheckStatus || 'N/A'}
**Previous Check Failures:** ${checkpoint.failures}

## History
${checkpoint.history.join('\n') || '_No history yet._'}

---

## Anleitung

Diese Datei wird vom GitHub Actions Orchestrator automatisch verwaltet.

**Nicht manuell bearbeiten**, außer zum Zurücksetzen des Zustands.
`;
  
  await writeFile('codex/CHECKPOINT.md', content, 'utf-8');
  console.log('💾 Checkpoint updated');
}

// Read TODO queue
async function readTodoQueue() {
  const path = 'codex/TODO_QUEUE.md';
  if (!existsSync(path)) {
    return { tasks: [], currentIssue: null };
  }
  
  const content = await readFile(path, 'utf-8');
  const lines = content.split('\n');
  
  let currentIssue = null;
  const tasks = [];
  
  for (const line of lines) {
    if (line.startsWith('## Issue #')) {
      currentIssue = line.match(/#(\d+)/)?.[1];
    }
    if (line.match(/^- \[ \]/)) {
      tasks.push(line.replace(/^- \[ \] /, '').trim());
    }
  }
  
  return { tasks, currentIssue };
}

// Get first uncompleted task
async function getNextTask() {
  const queue = await readTodoQueue();
  return queue.tasks.length > 0 ? queue.tasks[0] : null;
}

// Create branch name from issue
function createBranchName(issueNumber, title) {
  const slug = title
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-|-$/g, '')
    .substring(0, 50);
  return `orchestrator/issue-${issueNumber}-${slug}`;
}

// Check if PR exists for branch
async function findPRForBranch(branchName) {
  const prs = await githubAPI(`/repos/${owner}/${repo}/pulls?state=open&head=${owner}:${branchName}`);
  return prs && prs.length > 0 ? prs[0] : null;
}

// Check if checks are passing
async function areChecksPassing(sha) {
  const checks = await githubAPI(`/repos/${owner}/${repo}/commits/${sha}/check-runs`);
  
  if (!checks || !checks.check_runs || checks.check_runs.length === 0) {
    // No checks configured = assume passing
    return true;
  }
  
  // All checks must be success or neutral
  return checks.check_runs.every(check => 
    check.status === 'completed' && 
    (check.conclusion === 'success' || check.conclusion === 'neutral')
  );
}

// Get latest review
async function getLatestReview(prNumber) {
  const reviews = await githubAPI(`/repos/${owner}/${repo}/pulls/${prNumber}/reviews`);
  
  if (!reviews || reviews.length === 0) {
    return null;
  }
  
  // Get most recent review
  return reviews[reviews.length - 1];
}

// Collect review comments
async function collectReviewComments(prNumber) {
  const reviews = await githubAPI(`/repos/${owner}/${repo}/pulls/${prNumber}/reviews`);
  const comments = await githubAPI(`/repos/${owner}/${repo}/pulls/${prNumber}/comments`);
  
  const findings = [];
  
  // From reviews with changes requested
  if (reviews) {
    reviews.forEach(review => {
      if (review.state === 'CHANGES_REQUESTED' && review.body) {
        findings.push(`- **Review by @${review.user.login}:** ${review.body}`);
      }
    });
  }
  
  // From review comments
  if (comments) {
    comments.forEach(comment => {
      findings.push(`- **\`${comment.path}:${comment.line}\`** - @${comment.user.login}: ${comment.body}`);
    });
  }
  
  return findings;
}

// Transition: QUEUED → RUNNING
async function transitionQueuedToRunning(issue) {
  console.log('\n🔄 Transition: QUEUED → RUNNING');
  
  const checkpoint = await readCheckpoint();
  const nextTask = await getNextTask();
  
  if (!nextTask) {
    console.log('⚠️  No tasks in queue. Cannot start.');
    return false;
  }
  
  const branchName = createBranchName(issue.number, issue.title);
  
  // Update checkpoint
  checkpoint.status = 'Running';
  checkpoint.issue = issue.number;
  checkpoint.branch = branchName;
  checkpoint.currentTask = nextTask;
  checkpoint.iteration = 1;
  checkpoint.lastCheck = new Date().toISOString();
  checkpoint.history.push(`- ${new Date().toISOString()} - Transition: QUEUED → RUNNING`);
  
  await writeCheckpoint(checkpoint);
  
  // Post instruction comment
  const comment = `🤖 **Orchestrator: Task gestartet**

Bitte führe NUR den folgenden Task aus:

**Task:** ${nextTask}

**Branch:** \`${branchName}\`

### Anweisungen:
1. Erstelle Branch \`${branchName}\` wenn noch nicht vorhanden
2. Implementiere NUR den oben genannten Task
3. Erstelle einen PR mit den Änderungen
4. Aktualisiere \`codex/CHECKPOINT.md\` nicht (wird automatisch gemacht)

### Wichtig:
- Halte die Änderungen minimal und fokussiert auf diesen einen Task
- Führe Build und Tests aus: \`./gradlew :shared:contract:generateFishitContract && ./gradlew :androidApp:compileDebugKotlin\`
- Erstelle den PR sobald der Task fertig ist

Weitere Details in \`codex/TODO_QUEUE.md\`.

---
*Orchestrator Iteration 1/5*`;
  
  await postComment(issue.number, comment);
  
  // Update labels
  await updateLabels(issue.number, 'QUEUED', 'RUNNING');
  
  console.log('✅ Transition complete: QUEUED → RUNNING');
  return true;
}

// Transition: RUNNING → NEEDS_REVIEW
async function transitionRunningToNeedsReview(issue, pr) {
  console.log('\n🔄 Transition: RUNNING → NEEDS_REVIEW');
  
  const checkpoint = await readCheckpoint();
  
  // Check if checks are passing
  const checksPassing = await areChecksPassing(pr.head.sha);
  
  if (!checksPassing) {
    console.log('⚠️  Checks not passing yet. Staying in RUNNING state.');
    return false;
  }
  
  // Update checkpoint
  checkpoint.pr = pr.number;
  checkpoint.lastCheck = new Date().toISOString();
  checkpoint.lastCheckStatus = '✅ passed';
  checkpoint.history.push(`- ${new Date().toISOString()} - Transition: RUNNING → NEEDS_REVIEW`);
  
  await writeCheckpoint(checkpoint);
  
  // Request review
  await postComment(pr.number, `🤖 **Orchestrator: Review benötigt**

Der Task ist abgeschlossen und alle Checks sind erfolgreich.

**Task:** ${checkpoint.currentTask}

Bitte reviewe die Änderungen:
- Prüfe ob der Task korrekt implementiert wurde
- Validiere Code-Qualität
- Gib Approve oder Request Changes

---
*Orchestrator Iteration ${checkpoint.iteration}/5*`);
  
  // Update labels
  await updateLabels(pr.number, 'RUNNING', 'NEEDS_REVIEW');
  
  console.log('✅ Transition complete: RUNNING → NEEDS_REVIEW');
  return true;
}

// Transition: NEEDS_REVIEW → FIXING
async function transitionNeedsReviewToFixing(pr) {
  console.log('\n🔄 Transition: NEEDS_REVIEW → FIXING');
  
  const checkpoint = await readCheckpoint();
  
  // Collect review comments
  const findings = await collectReviewComments(pr.number);
  
  if (findings.length === 0) {
    console.log('⚠️  No review comments found. Cannot transition to FIXING.');
    return false;
  }
  
  // Update checkpoint
  checkpoint.history.push(`- ${new Date().toISOString()} - Transition: NEEDS_REVIEW → FIXING (changes requested)`);
  
  await writeCheckpoint(checkpoint);
  
  // Post fix instruction
  const comment = `🤖 **Orchestrator: Änderungen erforderlich**

Das Review hat folgende Findings ergeben:

${findings.join('\n')}

### Anweisungen:
Bitte behebe NUR die oben genannten Review-Findings.

**Wichtig:**
- Fokussiere auf die Review-Kommentare
- Halte Änderungen minimal
- Teste nach den Fixes: \`./gradlew :shared:contract:generateFishitContract && ./gradlew :androidApp:compileDebugKotlin\`
- Pushe die Änderungen zum bestehenden Branch

---
*Orchestrator Iteration ${checkpoint.iteration}/5*`;
  
  await postComment(pr.number, comment);
  
  // Update labels
  await updateLabels(pr.number, 'NEEDS_REVIEW', 'FIXING');
  
  console.log('✅ Transition complete: NEEDS_REVIEW → FIXING');
  return true;
}

// Transition: FIXING → NEEDS_REVIEW
async function transitionFixingToNeedsReview(pr) {
  console.log('\n🔄 Transition: FIXING → NEEDS_REVIEW');
  
  const checkpoint = await readCheckpoint();
  
  // Increment iteration
  checkpoint.iteration += 1;
  
  // Check iteration limit
  if (checkpoint.iteration > 5) {
    console.log('⚠️  Iteration limit exceeded (5). Moving to BLOCKED.');
    return await transitionToBlocked(pr, 'Iteration limit exceeded (5)');
  }
  
  // Check if checks are passing
  const checksPassing = await areChecksPassing(pr.head.sha);
  
  if (!checksPassing) {
    console.log('⚠️  Checks not passing. Staying in FIXING state.');
    
    // Track failures
    checkpoint.failures += 1;
    if (checkpoint.failures >= 2) {
      console.log('⚠️  Too many check failures (2). Moving to BLOCKED.');
      return await transitionToBlocked(pr, 'Checks failed 2 times');
    }
    
    await writeCheckpoint(checkpoint);
    return false;
  }
  
  // Reset failures on success
  checkpoint.failures = 0;
  checkpoint.lastCheck = new Date().toISOString();
  checkpoint.lastCheckStatus = '✅ passed';
  checkpoint.history.push(`- ${new Date().toISOString()} - Transition: FIXING → NEEDS_REVIEW (iteration ${checkpoint.iteration})`);
  
  await writeCheckpoint(checkpoint);
  
  // Request re-review
  await postComment(pr.number, `🤖 **Orchestrator: Fixes angewendet**

Änderungen wurden implementiert und alle Checks sind erfolgreich.

Bitte führe ein erneutes Review durch.

---
*Orchestrator Iteration ${checkpoint.iteration}/5*`);
  
  // Update labels
  await updateLabels(pr.number, 'FIXING', 'NEEDS_REVIEW');
  
  console.log('✅ Transition complete: FIXING → NEEDS_REVIEW');
  return true;
}

// Transition: NEEDS_REVIEW → PASSED
async function transitionNeedsReviewToPassed(pr, review) {
  console.log('\n🔄 Transition: NEEDS_REVIEW → PASSED');
  
  const checkpoint = await readCheckpoint();
  
  // Verify checks are still passing
  const checksPassing = await areChecksPassing(pr.head.sha);
  
  if (!checksPassing) {
    console.log('⚠️  Checks not passing. Cannot move to PASSED.');
    return false;
  }
  
  // Update checkpoint
  checkpoint.history.push(`- ${new Date().toISOString()} - Transition: NEEDS_REVIEW → PASSED (approved)`);
  
  await writeCheckpoint(checkpoint);
  
  // Post confirmation
  await postComment(pr.number, `🤖 **Orchestrator: Bereit zum Merge**

✅ Review approved
✅ Checks erfolgreich

Der PR wird automatisch gemerged.

---
*Orchestrator Iteration ${checkpoint.iteration}/5*`);
  
  // Update labels
  await updateLabels(pr.number, 'NEEDS_REVIEW', 'PASSED');
  
  console.log('✅ Transition complete: NEEDS_REVIEW → PASSED');
  return true;
}

// Transition: PASSED → MERGED
async function transitionPassedToMerged(pr) {
  console.log('\n🔄 Transition: PASSED → MERGED');
  
  const checkpoint = await readCheckpoint();
  
  // Merge PR (squash)
  try {
    await githubAPI(`/repos/${owner}/${repo}/pulls/${pr.number}/merge`, 'PUT', {
      merge_method: 'squash'
    });
    
    console.log('✅ PR merged successfully');
  } catch (err) {
    console.error('❌ Failed to merge PR:', err.message);
    return false;
  }
  
  // Close issue
  const issueNumber = checkpoint.issue;
  if (issueNumber && issueNumber !== 'N/A') {
    await githubAPI(`/repos/${owner}/${repo}/issues/${issueNumber}`, 'PATCH', {
      state: 'closed'
    });
    console.log(`✅ Issue #${issueNumber} closed`);
  }
  
  // Check for remaining tasks and create follow-up issue
  const queue = await readTodoQueue();
  if (queue.tasks.length > 0) {
    const issue = await githubAPI(`/repos/${owner}/${repo}/issues/${issueNumber}`);
    
    const followupBody = `# Follow-up: ${issue.title}

Fortsetzung von #${issueNumber}

## Verbleibende Tasks:
${queue.tasks.map(t => `- [ ] ${t}`).join('\n')}

## Context:
Original Issue: #${issueNumber}

---
*Automatisch erstellt durch Orchestrator nach erfolgreichem Merge von #${pr.number}*`;
    
    const newIssue = await githubAPI(`/repos/${owner}/${repo}/issues`, 'POST', {
      title: `Follow-up: ${issue.title}`,
      body: followupBody,
      labels: [ORCHESTRATOR_ENABLED, ORCHESTRATOR_RUN, STATE_LABELS.QUEUED]
    });
    
    console.log(`✅ Follow-up issue created: #${newIssue.number}`);
  }
  
  // Update checkpoint to idle
  checkpoint.status = 'Idle';
  checkpoint.issue = 'N/A';
  checkpoint.pr = 'N/A';
  checkpoint.branch = 'N/A';
  checkpoint.currentTask = 'N/A';
  checkpoint.iteration = 0;
  checkpoint.history.push(`- ${new Date().toISOString()} - Transition: PASSED → MERGED`);
  
  await writeCheckpoint(checkpoint);
  
  // Update labels
  await updateLabels(pr.number, 'PASSED', 'MERGED');
  
  console.log('✅ Transition complete: PASSED → MERGED');
  return true;
}

// Transition to BLOCKED
async function transitionToBlocked(pr, reason) {
  console.log(`\n🔄 Transition: → BLOCKED (${reason})`);
  
  const checkpoint = await readCheckpoint();
  
  checkpoint.status = 'Blocked';
  checkpoint.history.push(`- ${new Date().toISOString()} - Transition: → BLOCKED (${reason})`);
  
  await writeCheckpoint(checkpoint);
  
  await postComment(pr.number, `⚠️ **Orchestrator: BLOCKIERT**

**Grund:** ${reason}

Manuelle Intervention erforderlich.

### Nächste Schritte:
1. Prüfe und behebe das Problem manuell
2. Entferne Label \`state:blocked\`
3. Füge Label \`state:fixing\` hinzu
4. Der Orchestrator setzt beim nächsten Run fort

---
*Orchestrator Iteration ${checkpoint.iteration}/5*`);
  
  // Update labels (remove current state, add blocked)
  const currentState = getCurrentState(pr.labels);
  await updateLabels(pr.number, currentState, 'BLOCKED');
  
  console.log('✅ Transition complete: → BLOCKED');
  return true;
}

// Main transition logic
async function performTransition() {
  console.log('🚀 GitHub Actions Orchestrator - Transition Script\n');
  console.log(`📦 Repository: ${owner}/${repo}`);
  
  // Determine work item (issue or PR)
  let workItem = null;
  let itemType = null;
  
  if (options.issue) {
    console.log(`🎯 Target: Issue #${options.issue}`);
    workItem = await githubAPI(`/repos/${owner}/${repo}/issues/${options.issue}`);
    itemType = 'issue';
  } else if (options.pr) {
    console.log(`🎯 Target: PR #${options.pr}`);
    workItem = await githubAPI(`/repos/${owner}/${repo}/pulls/${options.pr}`);
    itemType = 'pr';
  } else {
    // Auto-discover: find issue with orchestrator:run + state:queued
    console.log('🔍 Auto-discovering work item...');
    const issues = await githubAPI(`/repos/${owner}/${repo}/issues?labels=${ORCHESTRATOR_RUN},${STATE_LABELS.QUEUED}&state=open`);
    
    if (issues && issues.length > 0) {
      workItem = issues[0];
      itemType = 'issue';
      console.log(`✅ Found queued issue: #${workItem.number}`);
    } else {
      // Look for running/fixing issues
      const runningIssues = await githubAPI(`/repos/${owner}/${repo}/issues?labels=${STATE_LABELS.RUNNING}&state=open`);
      if (runningIssues && runningIssues.length > 0) {
        workItem = runningIssues[0];
        itemType = 'issue';
        console.log(`✅ Found running issue: #${workItem.number}`);
      } else {
        const fixingPRs = await githubAPI(`/repos/${owner}/${repo}/pulls?state=open`);
        if (fixingPRs && fixingPRs.length > 0) {
          const fixing = fixingPRs.find(pr => pr.labels.some(l => l.name === STATE_LABELS.FIXING));
          if (fixing) {
            workItem = fixing;
            itemType = 'pr';
            console.log(`✅ Found fixing PR: #${workItem.number}`);
          }
        }
      }
    }
  }
  
  if (!workItem) {
    console.log('ℹ️  No active work items found. Exiting successfully.');
    return;
  }
  
  // Check for orchestrator:enabled label
  const hasOrchestratorEnabled = workItem.labels.some(l => l.name === ORCHESTRATOR_ENABLED);
  if (!hasOrchestratorEnabled) {
    console.log('⚠️  orchestrator:enabled label not found. Skipping.');
    return;
  }
  
  // Determine current state
  const currentState = getCurrentState(workItem.labels);
  console.log(`📊 Current state: ${currentState || 'UNKNOWN'}`);
  
  // Perform appropriate transition
  let transitioned = false;
  
  switch (currentState) {
    case 'QUEUED':
      transitioned = await transitionQueuedToRunning(workItem);
      break;
      
    case 'RUNNING':
      // Check if PR exists
      const checkpoint = await readCheckpoint();
      const pr = await findPRForBranch(checkpoint.branch);
      if (pr) {
        transitioned = await transitionRunningToNeedsReview(workItem, pr);
      } else {
        console.log('ℹ️  Waiting for PR to be created. Staying in RUNNING state.');
      }
      break;
      
    case 'NEEDS_REVIEW':
      // Check latest review
      const prForReview = itemType === 'pr' ? workItem : await githubAPI(`/repos/${owner}/${repo}/pulls/${workItem.number}`);
      if (!prForReview) {
        console.log('⚠️  PR not found. Cannot transition.');
        break;
      }
      
      const latestReview = await getLatestReview(prForReview.number);
      
      if (latestReview && latestReview.state === 'APPROVED') {
        transitioned = await transitionNeedsReviewToPassed(prForReview, latestReview);
      } else if (latestReview && latestReview.state === 'CHANGES_REQUESTED') {
        transitioned = await transitionNeedsReviewToFixing(prForReview);
      } else {
        console.log('ℹ️  Waiting for review. Staying in NEEDS_REVIEW state.');
      }
      break;
      
    case 'FIXING':
      const prForFixing = itemType === 'pr' ? workItem : await githubAPI(`/repos/${owner}/${repo}/pulls/${workItem.number}`);
      if (prForFixing) {
        transitioned = await transitionFixingToNeedsReview(prForFixing);
      }
      break;
      
    case 'PASSED':
      const prForMerge = itemType === 'pr' ? workItem : await githubAPI(`/repos/${owner}/${repo}/pulls/${workItem.number}`);
      if (prForMerge) {
        transitioned = await transitionPassedToMerged(prForMerge);
      }
      break;
      
    case 'BLOCKED':
      console.log('⚠️  Work item is BLOCKED. Manual intervention required.');
      break;
      
    case 'MERGED':
      console.log('✅ Work item already MERGED. Nothing to do.');
      break;
      
    default:
      console.log(`⚠️  Unknown state: ${currentState}. No transition performed.`);
  }
  
  if (transitioned) {
    console.log('\n✅ Transition completed successfully');
  } else {
    console.log('\nℹ️  No transition performed (conditions not met or waiting)');
  }
}

// Run
performTransition().catch(err => {
  console.error('\n❌ Error:', err.message);
  process.exit(1);
});

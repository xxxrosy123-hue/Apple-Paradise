---
name: apple-park-dev
description: Project-specific development rules for Apple Paradise, a Java 11 native Android + server + MCP personal life-state system forked from linjian-peek-public. Use this skill for Android, MCP, server, AppGate, time-axis, reminders, health, wallet, accessibility, testing, and product-logic changes.
---

# Apple Paradise Development Skill

Use this skill whenever modifying Apple Paradise.

## 1. Start from the existing project, not a template

Before proposing code:

- inspect the relevant current files;
- inspect the current data format and MCP action names;
- inspect the Android call path;
- preserve the existing Java/XML build baseline;
- use the Notion product archive for settled requirements when available.

Do not assume a Kotlin/Compose/Hilt/Room stack.

## 2. Compatibility gate

Reject or rewrite external Android guidance that requires:

- Kotlin-only syntax;
- Jetpack Compose;
- Hilt;
- Room;
- Navigation3;
- Gradle-only dependency management;
- Coroutines/Flow as a required primitive.

Translate useful principles into Java/native-Android equivalents instead of forcing a migration.

## 3. Feature-routing checklist

When the task touches **AppGate**:
- inspect current lock/unlock/temp-unlock state handling;
- preserve valid temporary permissions across windows;
- record reason/expiry/source when possible;
- distinguish temporary decisions from long-term rules;
- ensure AI-facing temporary access tools tell the AI not to approve blindly;
- do not make the server pretend to judge subjective user need.

When the task touches **MCP**:
- make tool names and descriptions unambiguous;
- return effective state, reason, validity, and freshness where useful;
- avoid duplicate/conflicting sources of truth;
- prefer `get_current_context` for current decision-relevant facts;
- keep history in dedicated list/query tools.

When the task touches **schedule/time axis**:
- keep planned and actual blocks distinct;
- preserve provenance;
- allow manual editing of actual blocks;
- manual correction wins over routine auto-sync;
- connect Todo -> Schedule -> Focus without requiring duplicate data entry.

When the task touches **life maintenance/reminders**:
- prefer local Android scheduling/notifications;
- support complete/snooze/reschedule/skip;
- do not depend on ChatGPT being open;
- start simple before adding AI slot optimization.

When the task touches **health**:
- verify actual Health Connect/provider availability;
- keep field-level source/update time/staleness;
- avoid claiming unsupported Huawei data.

When the task touches **wallet**:
- preserve `created_at` separately from actual transaction time;
- support day-level grouping;
- do not backfill invented transaction times.

When the task touches **screen observation**:
- prefer state/accessibility/nodes before screenshots;
- screenshots are fresh/on-demand, not a permanent surveillance archive.

## 4. UI/product behavior rules

Do not fake companion presence.

“哥哥来过” or similar presence indicators may only update after real AI/MCP interaction that the product can substantiate.

Historical companion notes may be stored only when an AI actually writes them.

Homepage design should emphasize current life state rather than becoming a grid of unrelated feature buttons.

## 5. Accessibility baseline

For every interactive screen:

- actionable controls need understandable labels;
- decorative images should not create noisy accessibility output;
- touch targets should generally be at least 48dp;
- focus order must match visual/interaction order;
- toggle/selected/locked states should be exposed to accessibility services;
- do not rely only on color to communicate state.

## 6. Background and notification reliability

For alarms/reminders:

- verify Android version restrictions;
- handle app restart/process death where relevant;
- persist enough state to rebuild future reminders;
- avoid notification spam;
- keep reminder actions idempotent;
- when exact timing is not guaranteed, represent that limitation clearly.

## 7. Privacy/security review

Before shipping changes involving accessibility, screenshot, health, location, network, auth, or MCP:

- enumerate data collected;
- enumerate permissions used;
- minimize retention;
- prevent secrets in logs/repository;
- validate MCP/server inputs;
- avoid exposing powerful low-level actions without a clear product need;
- keep sensitive histories bounded when the product only needs current state.

## 8. Testing strategy for this repository

Do not paste Gradle/Compose test setup into this project unless a migration is explicitly in scope.

Prefer:

- deterministic Java logic tests where existing tooling permits;
- small test fixtures for state transitions;
- manual/ADB smoke checks for AccessibilityService/AppGate/notifications;
- build-script compilation as a required regression check after Android changes;
- MCP request/response fixtures for server/tool changes;
- conflict tests for cross-window temporary permissions;
- restart/expiry tests for TTL and reminder state.

High-value regression cases:

1. valid temporary ALLOW remains effective until expiry;
2. a new window does not silently erase that permission;
3. expired decisions disappear from current context;
4. temporary unlock tool description contains the non-blind-approval instruction;
5. stale data is not presented as fresh current state;
6. manual actual-time edits are not overwritten by sync;
7. reminder completion/snooze is idempotent;
8. wallet actual date is not replaced by record-creation date.

## 9. Performance and implementation restraint

Prefer:

- cheap local reads;
- incremental sync;
- bounded logs;
- lazy screenshot capture;
- avoiding unnecessary background polling;
- avoiding database/history dumps through MCP;
- small, reversible code changes.

Do not over-engineer speculative infrastructure before a real requirement needs it.

## 10. Definition of done

A change is not done only because code compiles.

For a behavior change, verify:

- source-of-truth semantics;
- restart behavior if state is persisted;
- expiry/freshness behavior;
- cross-window conflict behavior where relevant;
- Android permission/background constraints where relevant;
- MCP output matches product meaning;
- user-visible state can be corrected manually when product requirements require correction.

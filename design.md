

## Overview

A lightweight, file-system-based command relay system that allows a central operator
to issue shell commands to multiple Linux machines simultaneously, using a shared
network mount as the communication channel. No direct SSH access from the operator's
machine is required at runtime.

---

## Problem Statement

In environments with many Linux machines daily operational checks
(process status, log inspection) require manual login to each machine individually.
This design eliminates that by allowing a single command to fan out across all
machines automatically, with results collected back to a central location.

---

## High-Level Architecture

```
Operator (Windows PC)
  │
  │  writes command file
  ▼
Network Share (NAS)
  ├── commands/        ← operator writes here
  ├── output/          ← agents write results here
  ├── state/           ← agents track last executed command here
  └── config/          ← shared configuration and blacklist
  │
  │  polled every 5 seconds
  ▼
Linux Machines (30–40 agents running as service account)
  ├── machine-01 agent
  ├── machine-02 agent
  ├── ...
  └── machine-N agent
```

---

## Component Design

### 1. Command File

**Location:** `<NAS_COMMANDS_PATH>/cmd_<TIMESTAMP>.txt`

**Example filename:** `cmd_20250702143022847.txt`

The timestamp (`YYYYMMDDHHMMSSmmm`) is embedded in the filename itself.
Each new command creates a new file — no overwrites, no race conditions.

**Atomic write protocol:**
1. Write to `cmd_20250702143022847.tmp`
2. Rename to `cmd_20250702143022847.txt`

Rename is atomic on most filesystems — agents never read a partial file.

**File content format:**
```
<TARGET> <COMMAND>
```

**Target formats:**

| Target | Meaning |
|---|---|
| `ALL` | All machines execute |
| `machine-03` | Only machine-03 executes |
| `machine-01,machine-05,machine-12` | Named machines only |

**Examples:**
```
ALL ps -ef
machine-03 tail -100 /app/logs/app.log
machine-01,machine-07 grep -i ERROR /app/logs/app.log
```

---

### 2. Agent (Linux Side)

One agent process runs on each Linux machine, under the service account.

**Poll loop (every 5 seconds):**

```
1. Verify NAS is mounted — if not, skip and log locally
2. List all cmd_*.txt files in <NAS_COMMANDS_PATH>
3. Sort by filename (timestamp ascending) — pick the latest
4. Read last executed timestamp from <NAS_STATE_PATH>/<hostname>_last_ts.txt
5. If latest timestamp == last executed → nothing new, sleep 5s
6. If latest timestamp > last executed → new command detected:
     a. Read command file
     b. Parse target — am I included?
     c. If not included → update state file, sleep 5s
     d. If included → execute command (with blacklist check)
     e. Capture output
     f. If output non-empty → write to <NAS_OUTPUT_PATH>/<date>/<hostname>_<timestamp>.txt
     g. Update state file with this timestamp
     h. Sleep 5s
```

**Hostname matching:**
- Agent uses `hostname` command to determine its own identity
- Must match exactly (case-sensitive) against machine list in `process.conf`
- Verify hostnames match `process.conf` entries during initial setup

---

### 3. Output File

**Location:** `<NAS_OUTPUT_PATH>/<YYYYMMDD>/<hostname>_<timestamp>.txt`

**Example:** `/nas/output/20250702/machine-03_143022847.txt`

- Dated subfolder — easy periodic cleanup
- One file per machine per command execution
- If command produces no output — no file written (explicit empty = no noise)
- Output capped at 10,000 lines
- If truncated, last line is: `[OUTPUT TRUNCATED AT 10000 LINES]`

---

### 4. State File

**Location:** `<NAS_STATE_PATH>/<hostname>_last_ts.txt`

**Content:** Single line — last executed timestamp string

**Example:** `20250702143022847`

- Stored on NAS — survives Linux box reboots, `/tmp` clears, home dir issues
- One file per machine
- Read at startup and after every command execution
- If state file missing (first run) — agent treats everything as new and picks up latest command only (does not replay history)

---

### 5. Blacklist

**Location:** `<NAS_CONFIG_PATH>/blacklist.conf`

**Format:** One command per line. Comment out with `#` to temporarily allow.

**Default blacklist:**
```
rm
kill
shutdown
reboot
mkfs
dd
chmod
chown
```

**To temporarily allow a blacklisted command:**
```
# kill
rm
shutdown
```
`kill` is now allowed. No agent restart needed — blacklist is read on every command execution.

**Matching rule:** Blacklist checks the first token of the command only.
`kill -9 1234` → first token is `kill` → blocked.

---

### 6. Configuration File

**Location:** `<NAS_CONFIG_PATH>/process.conf`

```ini
# NAS paths — configure for your environment

# Linux side mount path
NAS_MOUNT_PATH=/mnt/nas

# Windows side mount path (UNC)
NAS_MOUNT_PATH_WINDOWS=\\\\server\\share

# Subdirectory paths (relative to NAS_MOUNT_PATH)
COMMANDS_DIR=commands
OUTPUT_DIR=output
STATE_DIR=state
CONFIG_DIR=config

# Agent behaviour
POLL_INTERVAL_SECONDS=5
OUTPUT_MAX_LINES=10000

# Machine list — hostnames must match `hostname` output exactly
MACHINES=machine-01,machine-02,machine-03,machine-04,machine-05
```

---

## Folder Structure on NAS

```
/nas/
├── commands/
│   ├── cmd_20250702143022847.txt       ← active commands
│   └── cmd_20250702150011123.txt
├── output/
│   ├── 20250702/
│   │   ├── machine-01_143022847.txt
│   │   ├── machine-03_143022847.txt
│   │   └── machine-07_143023001.txt
│   └── 20250703/
│       └── ...
├── state/
│   ├── machine-01_last_ts.txt
│   ├── machine-02_last_ts.txt
│   └── ...
└── config/
    ├── process.conf
    └── blacklist.conf
```

---

## Edge Cases and Handling

| Scenario | Handling |
|---|---|
| NAS not mounted on a Linux box | Agent detects mount missing, skips poll cycle, logs locally |
| Agent reads partial command file | Atomic rename on write prevents this |
| Two commands issued within same millisecond | Use millisecond-precision timestamp — collision extremely unlikely |
| Command produces no output | No output file written — silence means nothing found |
| Output exceeds 10,000 lines | Truncated with `[OUTPUT TRUNCATED AT 10000 LINES]` marker |
| State file missing on first run | Agent picks up latest command only — does not replay history |
| Machine not in target list | Agent updates state file and skips execution |
| Blacklisted command issued | Agent writes refusal message to output file — does not execute |

---

## Security Considerations

- NAS share access controls are the primary security boundary — restrict read/write to authorised users and service accounts only
- Blacklist prevents destructive commands by default
- Blacklist is editable on NAS — control NAS write access carefully
- Command files are plaintext — do not include secrets, passwords, or tokens in commands
- All output is written to NAS — treat output folder as potentially sensitive

---

## Phase 2 Considerations (Out of Scope for Now)

- Agent self-healing via cron watchdog
- NAS mount auto-recovery
- Command acknowledgement / delivery confirmation
- LLM integration on Windows side for natural language command generation and output interpretation
- Retention policy / auto-cleanup of old command and output files
- Encrypted command channel

---

## Glossary

| Term | Meaning |
|---|---|
| Agent | Polling script running on each Linux machine |
| NAS | Network Attached Storage — shared filesystem accessible from both Windows and Linux |
| Command file | Text file written by operator containing target and command |
| State file | Per-machine file on NAS tracking last executed command timestamp |
| Blacklist | Config file listing commands the agent will refuse to execute |
| Target | Machine(s) a command is addressed to — `ALL`, single hostname, or comma-separated list |

---

*Document version: 1.0 — Initial design*

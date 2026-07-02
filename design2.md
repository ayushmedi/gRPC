
## Overview

A lightweight, file-system-based command relay system that allows a central operator
to issue shell commands to multiple Linux machines from a single command file, using a shared
network mount (NAS) as the communication channel. The agent runs on a single Linux
machine and fans out commands to other machines via SSH. No direct SSH access from
the operator's Windows PC to the Linux machines is required at runtime.

---

## Problem Statement

In environments with many Linux machines (30–40+), daily operational checks
(process status, log inspection) require manual login to each machine individually.
This design eliminates that by allowing a single command file to fan out across all
machines automatically, with results collected back to a central NAS location readable
from Windows.

---

## High-Level Architecture

```
Operator / LLM (Windows PC)
  │
  │  writes cmd_<timestamp>.txt to NAS (Windows mount path)
  │  polls for DONE_<timestamp>.txt to know execution is complete
  │  reads output files from NAS (Windows mount path)
  ▼
NAS (Network Attached Storage)
  ├── commands/     ← operator writes command files here
  ├── output/       ← agent writes results here (date subfolders)
  ├── state/        ← agent tracks last executed command here
  └── config/       ← process.conf and blacklist.conf
  ▲
  │  mounted at Linux path
  │  polled every 5 seconds
  ▼
Agent (single Linux machine — base host)
  │
  │  runs commands locally if target == self
  │  SSHes into other machines for remote targets
  │  captures stdout/stderr → writes to NAS output folder
  │  writes DONE marker when all commands complete
  ▼
Other Linux Machines (30–40)
  └── accessed via SSH from agent (machine-to-machine)
```

---

## Component Design

### 1. Command File

**Location:** `<NAS_COMMANDS_DIR>/cmd_<TIMESTAMP>.txt`

**Example filename:** `cmd_20250702143022847.txt`

Timestamp format: `YYYYMMDDHHMMSSmmm` (millisecond precision to avoid collisions).
Each new command batch creates a new file — no overwrites, no race conditions.

**Atomic write protocol (operator/LLM side):**
1. Write content to `cmd_20250702143022847.tmp`
2. Rename to `cmd_20250702143022847.txt`

Rename is atomic on most **local** filesystems, but this guarantee is weaker on SMB/CIFS
and some NFS setups, where a directory listing can even show a `.txt` whose contents are
still being flushed. Rename alone is therefore **not** sufficient on a NAS.

**`#EOF` completeness sentinel (required):**
- Every command file MUST end with a final line containing exactly `#EOF`.
- The agent only treats a file as complete if its last non-empty line is `#EOF`.
- A `cmd_*.txt` without a trailing `#EOF` is assumed *still being written* → the agent
  skips it this cycle and retries next poll (it does not error, and does not advance state).
- This is the real guard against partial reads; the `.tmp`→rename step is kept as a first
  line of defence.

**Encoding / line-ending normalisation (Windows → Linux):**
- Files are authored on Windows and WILL use `CRLF` (`\r\n`) and MAY carry a UTF-8 BOM.
- The agent MUST decode as UTF-8 with `errors="replace"`, strip a leading BOM, and strip a
  trailing `\r` from each line before parsing.

---

**File content format:**

Each line is one command:
```
<TARGET> CMD<N>: <COMMAND>
```

- `TARGET` — which machines to run on
- `CMD<N>` — sequential command number (CMD1, CMD2, CMD3 ...) — used to correlate output files
- `COMMAND` — the shell command to execute

**Target formats:**

| Target | Meaning |
|---|---|
| `ALL` | All machines listed in `process.conf` |
| `machine-03` | Single named machine only |
| `machine-01,machine-03,machine-07` | Comma-separated list of machines |

**Example command file:**
```
ALL CMD1: ps -ef
ALL CMD2: df -h
machine-03 CMD3: tail -100 /app/logs/app.log
machine-03 CMD4: grep -i ERROR /app/logs/app.log
machine-01,machine-07 CMD5: uptime
#EOF
```

**Line parsing rules (strict — reject the whole batch rather than guess):**
- Expected shape per line: `^\s*(?P<target>\S+)\s+CMD(?P<n>\d+):\s?(?P<cmd>.*)$`
- Blank lines and lines beginning with `#` are ignored (except the `#EOF` sentinel).
- Everything after the first `:` is the literal command — internal whitespace, pipes and
  further colons are preserved verbatim; do not split on additional colons.
- **If ANY non-ignored line is malformed, targets an unknown machine, or reuses a `CMD<N>`
  number, the entire batch is rejected** — nothing executes, and a DONE marker is written
  with `status: ERROR` and a `reason:` naming the offending line. This all-or-nothing rule
  prevents a typo from silently running a partial batch and prevents output-filename
  collisions from duplicate CMD numbers.

---

### 2. Agent (Linux Side)

One agent process runs on the **base host machine** under the service account.
It is the only machine that needs the script deployed.

The agent is a **long-running daemon** (started manually under the service account, e.g.
`su - <svc>` then launched). It loops forever, polling every `POLL_INTERVAL_SECONDS`.

**Startup (once):**
```
0a. Acquire an exclusive non-blocking flock on a LOCAL lockfile (fcntl.flock); write this
    process's PID into the file. If already held → another agent is running → log and exit.
    (Prevents double execution. Lock is local because flock is unreliable over a NAS.)
0b. Load & validate process.conf (fail fast on any error — see §7).
0c. Warn if BASE_HOSTNAME != socket.gethostname() (mismatch breaks local-vs-SSH routing).
0d. Install SIGTERM/SIGINT handlers for clean shutdown between batches.
```

**Poll loop (every `POLL_INTERVAL_SECONDS`):**
```
1. Verify NAS is LIVE: os.path.ismount(NAS_MOUNT_PATH_LINUX) AND a sentinel read
   (e.g. open config/process.conf) both succeed. If not → skip cycle, log to stderr, retry.
   (NAS is assumed always up, but this cheap check is kept as a safety net: a stale
    mountpoint dir can exist while the NAS is gone, and it avoids writing output to the
    local disk by mistake. When the NAS is unavailable the log also can't be written, so
    this branch logs to stderr.)
2. List all cmd_*.txt in COMMANDS_DIR; parse the timestamp from each filename.
3. Read last executed timestamp from STATE_DIR/<base_hostname>_last_ts.txt.
4. Build the QUEUE = every cmd file whose timestamp > last executed, sorted ASCENDING.
   (Process ALL pending files oldest→newest, so nothing issued during a long batch is lost.)
5. If queue is empty → sleep and repeat.
6. For EACH command file in the queue, oldest first:
     a. Read the file; if its last non-empty line is not `#EOF` → treat as still-being-
        written: skip THIS file (and everything after it in the queue) this cycle.
     b. Normalise encoding/line-endings; parse & validate ALL lines up front.
          - On any parse error / unknown machine / duplicate CMD<N> → write a DONE marker
            with status: ERROR + reason, advance state past this file, move on. Nothing runs.
     c. Execute lines in order; within a line, iterate machines SEQUENTIALLY:
          i.   Resolve machine list from TARGET (ALL → MACHINES).
          ii.  Check the command's first token against the (freshly re-read) blacklist.
               - If blocked → write `[BLOCKED] ...` to that machine's output file, skip exec.
          iii. If machine == BASE_HOSTNAME → run locally; else → `ssh <host> "<cmd>"`.
               - Enforce COMMAND_TIMEOUT_SECONDS; stdin from /dev/null; capture stdout+stderr.
               - Truncate at OUTPUT_MAX_LINES if needed.
               - Success + empty output → NO file (silence = nothing found).
               - Success + output, OR any failure (non-zero, timeout, ssh error) → write an
                 output file ATOMICALLY (.tmp → fsync → rename). Failures always produce a file.
     d. After ALL lines/machines for this file are written → write the DONE marker LAST,
        atomically (this is the LLM's "ready" signal, so it must come after every output).
     e. Only AFTER the DONE marker is durably written → update the state file to this ts.
7. Sleep POLL_INTERVAL_SECONDS, repeat.
```

**Strict write ordering (per file): output files → DONE marker → state file.**
If the agent dies at any point, no DONE marker exists for an incomplete batch and state is
not advanced, so the whole file simply re-runs on restart (see idempotency note in §5).

**Always emit a DONE marker.** Each file is wrapped in `try/except/finally`; on any
unhandled error the agent still attempts a DONE marker with `status: ERROR` so the LLM's
poll terminates instead of hanging forever. The only case with no DONE is total NAS loss —
so the LLM side also applies its own overall timeout (see LLM flow).

**Date-folder derivation.** The `<YYYYMMDD>` output subfolder is derived from the COMMAND
FILE's timestamp, never from "now", so a batch that crosses midnight keeps all its output
and DONE marker together, and the LLM computes the exact same path.

**SSH execution model:**
- Remote execution is a per-command, stateless `ssh <host> "<command>"` — connect, run, exit.
  No persistent sessions.
- Fan-out is **sequential** (one machine at a time). A down host costs at most one
  `ConnectTimeout`; the loop is single-threaded and blocks while a batch runs (accepted).
- If the target equals `BASE_HOSTNAME`, the command runs locally with no SSH.

---

### 3. Output Files

**Location:** `<NAS_OUTPUT_DIR>/<YYYYMMDD>/<machine>_CMD<N>_<timestamp>.txt`

**Examples:**
```
/nas/output/20250702/machine-01_CMD1_20250702143022847.txt
/nas/output/20250702/machine-03_CMD1_20250702143022847.txt
/nas/output/20250702/machine-03_CMD3_20250702143022847.txt
/nas/output/20250702/machine-03_CMD4_20250702143022847.txt
/nas/output/20250702/machine-07_CMD5_20250702143022847.txt
```

- **Timestamp in output filename matches command file timestamp** — single correlation key
- **CMD number in filename** — LLM knows exactly which output belongs to which command
- Dated subfolder — easy periodic manual cleanup
- If command produces no output — no file written
- Output capped at `OUTPUT_MAX_LINES` lines
- If truncated, last line appended: `[OUTPUT TRUNCATED AT <OUTPUT_MAX_LINES> LINES]` (e.g. 10000)

**Write robustness requirements:**
- **Atomic:** write `<name>.tmp`, `flush` + `os.fsync`, then `os.replace` to `.txt`, so
  Windows never sees a half-written output file.
- **Directory creation:** `os.makedirs(<OUTPUT_DIR>/<date>/, exist_ok=True)` inside
  try/except (tolerate the concurrent-exists race and surface NAS errors).
- **Encoding:** decode captured bytes with `errors="replace"` and strip NUL bytes so binary
  output never crashes the agent and the file opens cleanly on Windows.
- **Filename sanitisation (path-traversal defence):** only accept `machine` values that
  exactly match an entry in `MACHINES`, and `CMD\d+`; reject any `/`, `\`, `..` or control
  chars so a crafted command file can't write outside `OUTPUT_DIR`.
- **A failure is still output:** an SSH failure, timeout, or non-zero exit is a result, not
  silence — always write a file describing it (e.g. `[SSH ERROR] ...`, `[TIMEOUT after 60s]`,
  `[EXIT 2] ...`). "Silence = nothing found" applies only to a *successful* command that
  genuinely produced zero bytes.
- **Memory note (accepted trade-off):** output is buffered in memory before truncation, so a
  single command emitting enormous output could pressure memory. We cap by `OUTPUT_MAX_LINES`
  only (no byte cap) per current decision — keep `OUTPUT_MAX_LINES` conservative.

---

### 4. DONE Marker File

**Location:** `<NAS_OUTPUT_DIR>/<YYYYMMDD>/DONE_<timestamp>.txt`

**Example:** `/nas/output/20250702/DONE_20250702143022847.txt`

**Content:**
```
command_file: cmd_20250702143022847.txt
completed_at: 20250702143055123
status: COMPLETE
machines: machine-01, machine-03, machine-07
commands: CMD1, CMD2, CMD3, CMD4, CMD5
failed: none
failed_count: 0
```

If any command failed:
```
status: PARTIAL
failed: machine-03_CMD4 (non-zero exit code)
failed_count: 1
```

If the batch could not run at all (malformed file, unknown machine, duplicate CMD):
```
status: ERROR
reason: duplicate CMD number CMD3 in command file
```

**Status values (the complete set the LLM must handle):**

| Status | Meaning |
|---|---|
| `COMPLETE` | Every targeted command on every targeted machine succeeded (exit 0). |
| `PARTIAL` | Batch ran; one or more machine/command pairs failed — see `failed:`. |
| `ERROR` | Batch did NOT execute (rejected at validation). See `reason:`. No output files. |

- LLM polls for this file after issuing a command file
- Once present — all commands executed, all output files ready to read
- `PARTIAL` / `ERROR` status tells the LLM something needs attention

**DONE marker robustness:**
- Written **last** and **atomically** (`.tmp` → `fsync` → `rename`), after every output file.
- `failed:` enumerates each failed `machine_CMDn` with a short reason (`ssh-unreachable`,
  `timeout`, `exit=<code>`, `blocked`), and `failed_count:` gives a machine-readable total,
  so the LLM can decide what to re-issue without reading every output file.

---

### 5. State File

**Location:** `<NAS_STATE_DIR>/<base_hostname>_last_ts.txt`

**Content:** Single line — last executed timestamp

**Example:** `20250702143022847`

- Stored on NAS — survives reboots, `/tmp` clears, home directory issues
- Read at every poll cycle
- Advanced to a file's timestamp only **after** that file's DONE marker is durably written
- Because pending files are processed oldest→newest and state advances per file, a crash
  re-runs at most the *current* file, not already-completed earlier ones
- If missing on first run — agent adopts the newest command file's timestamp as the
  baseline and does NOT replay history

**State file robustness:**
- **Corrupt / empty / non-numeric content** → treated exactly like "missing on first run";
  log a warning, adopt the newest file as baseline, never crash.
- **Atomic update** (`.tmp` → `fsync` → `rename`) so a crash mid-write can't leave a
  truncated timestamp.
- **If the state write fails**, the file is NOT considered consumed → it re-runs next cycle.
  Re-execution is the deliberate safe failure mode (avoids silently dropping a batch).
- **Idempotency caveat:** a crash/NAS-loss between execution and state update causes the
  whole file to re-run — commands are **at-least-once**, not exactly-once. This is
  acceptable because the checks are read-only (`ps`, `df`, `tail`, `grep`, `uptime`).
  Operators must not put non-idempotent, mutating commands through this channel.

---

### 6. Blacklist

**Location:** `<NAS_CONFIG_DIR>/blacklist.conf`

**Format:** One command token per line. Comment out with `#` to temporarily allow.

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

**To temporarily allow a blacklisted command — comment it out:**
```
# kill
rm
shutdown
```
`kill` is now allowed. No agent restart needed — blacklist is re-read on every batch.

**Matching rule:** First token of the command checked against blacklist.
`kill -9 1234` → first token `kill` → blocked.

If blocked — agent writes to output file:
```
[BLOCKED] Command 'kill' is blacklisted. Remove from blacklist.conf to allow.
```

> **SECURITY NOTE — the blacklist is a typo-guard, not a security boundary.**
> Commands run through a shell (pipes/redirects are required), so a first-token check is
> easily bypassed: `sudo rm`, `/bin/rm` (token is `/bin/rm`, not `rm`), `ls; rm -rf /`,
> `$(rm ...)`, `` `rm ...` ``, `bash -c 'rm ...'`, `find . -delete`, `> file`. This is an
> accepted, deliberate limitation of the current design. The **real** boundaries are:
> (1) NAS write-ACLs on `commands/` (only trusted operators can issue commands), and
> (2) the OS privileges of the account SSH runs as on each machine. Keep those tight.

**Blacklist file robustness:**
- Re-read on every batch (dynamic; no restart needed to change it).
- If the file is **missing or unreadable**, fail **closed** — apply a built-in default
  blocklist rather than allowing everything.
- Normalise entries: trim whitespace, ignore blank/`#` lines, case-insensitive compare.

---

### 7. Configuration File

**Location:** `<NAS_CONFIG_DIR>/process.conf`

```ini
# -----------------------------------------------
# NAS Mount Paths — configure for your environment
# -----------------------------------------------

# Linux side NAS mount path
NAS_MOUNT_PATH_LINUX=/mnt/nas

# Windows side NAS mount path (UNC format)
NAS_MOUNT_PATH_WINDOWS=\\\\servername\\sharename

# -----------------------------------------------
# NAS Subdirectories (relative to NAS_MOUNT_PATH_LINUX)
# -----------------------------------------------
COMMANDS_DIR=commands
OUTPUT_DIR=output
STATE_DIR=state
CONFIG_DIR=config

# -----------------------------------------------
# Agent Behaviour
# -----------------------------------------------

# Hostname of the machine where agent runs
# Must match `hostname` output exactly
BASE_HOSTNAME=machine-01

# All machines in the fleet
# Hostnames must match `hostname` output on each box exactly (case-sensitive)
# Used when TARGET is ALL
MACHINES=machine-01,machine-02,machine-03,machine-04,machine-05

# Poll interval in seconds
POLL_INTERVAL_SECONDS=5

# Maximum output lines per command per machine before truncation
OUTPUT_MAX_LINES=10000

# -----------------------------------------------
# Execution timeouts
# -----------------------------------------------

# Per-command wall-clock timeout (local OR remote); the process group is killed if exceeded
COMMAND_TIMEOUT_SECONDS=60

# SSH connect timeout (seconds) — how long to wait to establish the connection to a host
SSH_CONNECT_TIMEOUT_SECONDS=10

# -----------------------------------------------
# Logging (written to the NAS output area; NAS is assumed always available)
# -----------------------------------------------
LOG_FILE=/mnt/nas/output/relay-agent.log
LOG_MAX_BYTES=10485760
LOG_BACKUP_COUNT=5

# Local single-instance lockfile. Stays LOCAL on purpose — flock is unreliable over a
# network mount, and the agent only ever runs on the one base host. The agent writes its
# own PID into this file so operators can see which process holds the lock.
LOCK_FILE=/tmp/relay-agent.lock
```

**Logging note:** logs live on the NAS (`LOG_FILE`), rotated via stdlib
`RotatingFileHandler` (`LOG_MAX_BYTES` × `LOG_BACKUP_COUNT`). If a log write ever fails, the
agent falls back to stderr so a logging problem can never stop execution. (Rotation renames
the open file; on some SMB shares that can be finicky — if you hit it, switch to a
date-based log name instead of size rotation.)

**SSH note:** the agent runs as the service account and invokes plain `ssh <host> "<cmd>"`,
relying on that account's existing passphrase-less key and `known_hosts`. It adds only two
safety options so a batch can never hang: `-o BatchMode=yes` (never prompt for a password)
and `-o ConnectTimeout=<SSH_CONNECT_TIMEOUT_SECONDS>`. No user/key/host-key policy is
hard-coded — whatever works interactively for the service account works here. Standard
commands only (no login-shell/`bash -lc` wrapping, no aliases) for now.

**Config parsing & validation contract (fail fast at startup):**
- Missing/unreadable config file → log a clear fatal error and exit non-zero.
- Missing required key, or wrong type (e.g. `POLL_INTERVAL_SECONDS=abc`) → fatal, exit.
- `MACHINES` empty, or `BASE_HOSTNAME` not present in `MACHINES` → fatal, exit.
- Warn loudly if `BASE_HOSTNAME` != `socket.gethostname()` (FQDN vs short-name / case is the
  usual cause of "everything runs remotely / nothing runs locally").
- Ignore `#` comments and blank lines; tolerate CRLF; trim whitespace around keys/values.
- **Reload policy:** `blacklist.conf` is re-read every batch. `process.conf` is read once at
  startup — changing machines/timeouts/paths requires an agent restart.

---

## NAS Folder Structure

```
/nas/                                         (Linux: /mnt/nas | Windows: \\server\share)
├── commands/
│   ├── cmd_20250702143022847.txt             ← command batch issued at 14:30
│   └── cmd_20250702150011123.txt             ← next command batch
├── output/
│   ├── relay-agent.log                        ← agent's own rotating log (LOG_FILE)
│   ├── 20250702/
│   │   ├── machine-01_CMD1_20250702143022847.txt
│   │   ├── machine-01_CMD2_20250702143022847.txt
│   │   ├── machine-03_CMD3_20250702143022847.txt
│   │   ├── machine-03_CMD4_20250702143022847.txt
│   │   ├── machine-07_CMD5_20250702143022847.txt
│   │   └── DONE_20250702143022847.txt        ← completion marker
│   └── 20250703/
│       └── ...
├── state/
│   └── machine-01_last_ts.txt               ← one file for base host
└── config/
    ├── process.conf
    └── blacklist.conf
```

---

## How Output is Captured and Written to NAS

The agent uses Python `subprocess` to run every command — whether local or remote via SSH.
stdout and stderr are both captured in memory. The script then writes that captured text
directly to the NAS output path. The command itself has no awareness of NAS.

```
subprocess.run(command, capture_output=True, timeout=COMMAND_TIMEOUT_SECONDS,
               stdin=DEVNULL, start_new_session=True)
        ↓
stdout + stderr captured as bytes, decoded errors="replace"
        ↓
Truncated at OUTPUT_MAX_LINES if needed
        ↓
Written atomically (.tmp → fsync → rename) as a text file to:
/mnt/nas/output/20250702/machine-03_CMD3_20250702143022847.txt
```

This works identically for `ps -ef`, `tail`, `grep`, `df`, SSH-wrapped remote commands —
any command universally.

### subprocess execution rules (non-negotiable)

- **Always pass `timeout=COMMAND_TIMEOUT_SECONDS`.** Commands like `tail -f`, a hung mount,
  or anything waiting on stdin would otherwise block the whole single-threaded loop forever.
  Start the child in its own session (`start_new_session=True`); on `TimeoutExpired`, kill
  the whole **process group** (`os.killpg`) so children/pipes die too, then write
  `[TIMEOUT after Ns]` and record the failure.
- **stdin from `DEVNULL`** so a command that reads stdin can't hang.
- **Capture bytes, decode `errors="replace"`** — never let binary output raise
  `UnicodeDecodeError` and crash the agent.
- **Two different failure meanings:** a non-zero *exit code* is a normal command result
  (write stderr, mark `exit=<n>`); wrap the call in try/except for *agent-side* failures
  (`FileNotFoundError` if `ssh` is missing, `OSError`, `TimeoutExpired`).
- **Local vs remote invocation:**
  - Local (`machine == BASE_HOSTNAME`): run via the shell so pipes/redirects work.
  - Remote: `subprocess` with an argument **list** — `["ssh", "-o", "BatchMode=yes", "-o",
    "ConnectTimeout=<N>", host, command]` — `shell=False` locally (avoids a local double-shell
    quoting nightmare); the remote sshd hands `command` to the remote shell, so pipes still
    work on the far side.
- **Distinguish SSH-connection failure from remote exit code:** the `ssh` client returns
  `255` for connect/auth failure, which can collide with a genuine remote exit 255 — detect
  connection failures from stderr text (`Connection refused`, `Permission denied`,
  `Connection timed out`, `Could not resolve`) and label them `ssh-unreachable`.

---

## LLM Interaction Flow (Windows Side)

```
1. LLM writes cmd_<timestamp>.txt to \\server\share\commands\ (atomic .tmp → rename)
   and the LAST line MUST be the #EOF sentinel, or the agent will keep waiting.
2. LLM computes the date folder from its OWN <timestamp> (the YYYYMMDD prefix) — not the
   current Windows date — and polls that folder for DONE_<timestamp>.txt.
3. DONE appears → read the `status:` line first:
     - COMPLETE → read output files.
     - PARTIAL  → read output files AND the `failed:` list; decide what to re-issue.
     - ERROR    → no output files exist; read `reason:` and fix the command file.
4. LLM reads relevant output files. A COMPLETE command with NO file means it produced no
   output (silence = nothing found) — that is expected, not an error.
5. LLM reasons over output → answers operator's question.
6. If follow-up needed → LLM writes the next cmd_<timestamp>.txt and repeats.

Overall poll timeout (required on the LLM side):
- If no DONE marker appears within a bounded time (e.g. 5 min), STOP polling and surface
  "agent may be down / NAS unwritable" to the operator. The agent guarantees a DONE marker
  for any batch it STARTS, so a missing DONE means it never ran, is down, or the NAS is
  unwritable — none of which resolve by polling longer.
```

---

## Edge Cases and Handling

| Scenario | Handling |
|---|---|
| NAS not mounted (safety net) | `os.path.ismount` + sentinel-read both checked → skip cycle, log to stderr, retry |
| Stale mountpoint (dir exists, NAS gone) | Sentinel read fails → treated as not-mounted (avoids writing output to local disk) |
| NAS disappears mid-batch | Writes raise → caught, logged to stderr, state NOT advanced → file re-runs when NAS returns |
| Agent reads partial command file | `#EOF` sentinel missing → file deferred to next cycle (rename is only first-line defence) |
| CRLF / BOM from Windows | Decoded UTF-8 `errors="replace"`, BOM stripped, trailing `\r` trimmed |
| Malformed line / unknown machine / duplicate CMD | Whole batch rejected → DONE `status: ERROR` + reason; nothing executes |
| Two command files in same millisecond | Millisecond timestamp; equal names impossible on one dir — collision extremely unlikely |
| Multiple pending files (arrived during a long batch) | All processed oldest→newest; none dropped; state advances per file |
| Command produces no output (success) | No output file written — silence means nothing found |
| Output exceeds OUTPUT_MAX_LINES | Truncated with `[OUTPUT TRUNCATED AT N LINES]` marker |
| Huge single-command output | Buffered in memory (accepted); keep OUTPUT_MAX_LINES conservative (no byte cap by decision) |
| Binary / non-UTF-8 output | Decoded `errors="replace"`, NUL bytes stripped |
| Command hangs / `tail -f` / waits on stdin | `COMMAND_TIMEOUT_SECONDS` kills the process group; `[TIMEOUT]` written, marked failed |
| State file missing / corrupt / non-numeric | Treated as first run — newest file becomes baseline, no crash, no replay |
| State write fails | File NOT marked consumed → re-runs next cycle (safe failure mode) |
| Agent crashes mid-batch | No state advance → current file re-runs (at-least-once; checks are idempotent) |
| Path-traversal in machine/CMD token | Rejected — only exact `MACHINES` names and `CMD\d+` allowed in filenames |
| Blacklisted first token | `[BLOCKED]` written to output; not executed (note: first-token check is weak, see §6) |
| Blacklist file missing/unreadable | Fail closed — built-in default blocklist applied |
| SSH would prompt for password | `BatchMode=yes` fails fast instead of hanging |
| SSH host unreachable / refused | Detected from stderr → `ssh-unreachable`; DONE = PARTIAL |
| SSH exit 255 ambiguity | Connection failure distinguished from real remote exit 255 via stderr text |
| Command exits non-zero | stderr captured & written; recorded in `failed:` → DONE = PARTIAL |
| `ssh` binary missing | `FileNotFoundError` caught → error output written, machine marked failed |
| Two agents started accidentally | `flock` on lockfile → second instance logs and exits |
| `BASE_HOSTNAME` != `hostname` | Startup warning; otherwise local target would wrongly route |
| Batch crosses midnight | Date folder derived from command timestamp, not "now" — output stays together |
| Disk/quota full on NAS | Write raises → logged; DONE attempts `status: ERROR`; state not advanced |
| Config missing/invalid at startup | Fatal error, non-zero exit — never runs half-configured |
| SIGTERM/SIGINT received | Handler finishes/aborts the current write cleanly, releases lock, exits |

---

## Standard-Library-Only Implementation Notes

The agent is implementable on a stock **Python 3.12** install with **zero pip packages**
(hard requirement — no pip on the fleet). Mapping of needs to stdlib modules:

| Need | Stdlib module | Notes |
|---|---|---|
| Run local & remote commands | `subprocess` | `run(..., timeout=, capture_output=True, stdin=DEVNULL, start_new_session=True)` |
| SSH | system `ssh` binary via `subprocess` | **No paramiko** — shell out to the OpenSSH client already on every box |
| Kill hung process trees | `os.killpg`, `signal` | requires `start_new_session=True` to form a process group |
| Single-instance lock | `fcntl.flock` | on a local lockfile |
| Atomic writes | `os` (`os.replace`, `os.fsync`), `tempfile` | temp in same dir → flush+fsync → replace |
| Live mount check | `os.path.ismount` + sentinel read | both required |
| Timestamps / date folders | `datetime`, `time` | folder derived from the command timestamp |
| Config / blacklist parsing | plain file reads | tolerate CRLF/BOM manually |
| Rotating log on NAS | `logging`, `logging.handlers.RotatingFileHandler` | `LOG_FILE` on the NAS output area; stderr fallback if a write fails |
| Lockfile PID | `os.getpid`, `fcntl.flock` | flock enforces the lock; PID is written in for operator visibility |
| Signals | `signal` | SIGTERM/SIGINT graceful shutdown |
| Hostname | `socket.gethostname` | validate against `BASE_HOSTNAME` |

Everything above ships with CPython — no external dependency, no build step.

---

## Locked-In Design Decisions (agreed)

These were explicitly chosen; revisit before changing behaviour:

1. **Command safety = first-token blacklist, kept as-is** — documented as a typo-guard, not
   a security boundary (§6).
2. **Shell features required** — pipes/redirects work; local runs via shell, remote via the
   remote shell over `ssh`.
3. **`#EOF` sentinel required** on every command file to detect completeness.
4. **Process ALL pending files oldest→newest** (queue), never latest-only.
5. **At-least-once execution** — a crash re-runs the current file; acceptable for read-only
   checks.
6. **Sequential fan-out**, one machine at a time.
7. **SSH** = agent runs as the service account, plain `ssh <host> "<cmd>"` with only
   `BatchMode=yes` + `ConnectTimeout` added; passphrase-less key; standard commands only
   (no `bash -lc`, no aliases).
8. **Per-command timeout = 60s** (configurable), process-group kill on timeout.
9. **Output cap = lines only** (`OUTPUT_MAX_LINES`); no byte cap.
10. **Malformed batch → reject entirely** with DONE `status: ERROR`.
11. **Runtime** = Python 3.12, long-running daemon started manually under the service
    account.

---

## Phase 2 Considerations (Out of Scope for Now)

- Agent self-healing via cron watchdog
- NAS mount auto-recovery and alerting
- Retention policy and auto-cleanup of old command and output files
- Encrypted command channel
- LLM integration on Windows side (natural language → command file generation)
- Web UI for rota person to issue commands and view results

---

## Glossary

| Term | Meaning |
|---|---|
| Agent | Single Python script running on the base host Linux machine |
| Base host | The one Linux machine where the agent is deployed |
| NAS | Network Attached Storage — shared filesystem accessible from both Windows and Linux |
| Command file | Text file written by operator/LLM containing one or more targeted commands |
| CMD number | Sequential label (CMD1, CMD2...) within a command file — correlates output to command |
| State file | NAS file tracking last executed command file timestamp |
| DONE marker | NAS file written by agent when all commands in a batch are complete |
| Blacklist | Config file listing command tokens the agent will refuse to execute |
| TARGET | Machines a command line is addressed to — ALL, single hostname, or comma-separated list |
| BASE_HOSTNAME | Hostname of the machine running the agent — used to decide local vs SSH execution |

---


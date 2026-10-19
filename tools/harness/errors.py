"""Stable identifiers for everything that can go wrong, so a failure reports itself instead of stalling silently.

classify(text) gives a test's error an id and a hint (E-SETTLE-TIMEOUT: "...").  feedback(lines) finds the game's
own complaints in a test's log (a setup command that quietly did nothing: "That position is not loaded") and gives
them ids too; the runner turns those into flags, so a scene that was not built as designed never passes as if it
were. Adding a failure kind: one line in CODES or FEEDBACK.
"""
import re

# (id, pattern on the error text, what to do about it)
CODES = [
    ("E-CLIENT-CLOSED", r"closed \(not a crash\)|client was closed", "someone closed the window; the harness relaunched"),
    ("E-DISCONNECT", r"client disconnected|Network Protocol Error|Connection Lost",
     "the client lost its world (see the game log's exception; often a packet / entity bug)"),
    ("E-HEARTBEAT", r"stopped responding|heartbeat .* old", "the client froze or died (log, crash report)"),
    ("E-CRASH", r"crash", "the client crashed (crash report next to the result)"),
    ("E-STUCK-SCREEN", r"stuck on \w+Screen|still loading after", "a screen blocks the world (title, race, loading)"),
    ("E-SETTLE-TIMEOUT", r"did not settle|not settled|settle.*deadline",
     "the area never finished loading / meshing at the camera (unloaded, too far, or a heavy place)"),
    ("E-CAMERA-IN-TERRAIN", r"intersects terrain|inside a block", "the pose is inside blocks: re-plan the camera or raise it"),
    ("E-POS-UNLOADED", r"position is unloaded|not loaded", "the target area was not loaded when a command ran"),
    ("E-NO-SUBJECT", r"no subject within|framed nothing|framed .*, not ", "nothing (or the wrong thing) to frame"),
    ("E-WRONG-POSE", r"camera .* from the requested|looks .* away from", "the camera is not where the scene asked"),
    ("E-SHADER-STATE", r"captured with shaders", "the shader state does not match the variant"),
    ("E-UNKNOWN-ID", r"[Uu]nknown (item|block|entity|role|race|culture|weather)|unknown .* id",
     "a scene names an id the game does not have (validate_scenes / subject index)"),
    ("E-CMD-FAILED", r"command .* failed|command .*returned zero|Scenario failed|Incorrect argument|Unknown or incomplete command",
     "a command did not run (syntax or arguments)"),
    ("E-TIMEOUT", r"did not acknowledge|timed out|timeout", "a step took longer than its limit"),
    ("E-NO-REPORT", r"wrote no ", "a measure command produced no report"),
    ("E-VALIDATE", r"offline .* off|heights off|lab and live differ|online", "the offline tools and the game disagree"),
    ("E-CHECK-FAILED", r"^(ERR|FAIL|problems|PROBLEMS)|ERR |FAILED", "an offline check reported problems"),
    ("E-METRIC", r"(?-i:black box|luma \d|hand-off change|dE|brighter x|darker x|shapes differ|pop ratio)",
     "an image metric is out of range"),
]
# the game's own complaints in a test's log -> id
FEEDBACK = [
    ("E-POS-UNLOADED", r"That position is not loaded"),
    ("E-UNKNOWN-ID", r"Unknown (block|item|entity) type|Unknown item|Unknown block"),
    ("E-CMD-FAILED", r"Incorrect argument for command|Unknown or incomplete command|Could not set the block|Scenario failed"),
    ("E-NO-SUBJECT", r"No entity was found"),
    ("E-DISCONNECT", r"Failed to handle packet"),
]


def classify(text):
    """(id, hint) for an error / failure text; ("E-OTHER", "") when nothing matches."""
    for code, pat, hint in CODES:
        if text and re.search(pat, text, re.I):
            return code, hint
    return "E-OTHER", ""


def feedback(lines):
    """[(id, message, count)] for the game's complaints in a test's log lines."""
    found = {}
    for line in lines or []:
        for code, pat in FEEDBACK:
            m = re.search(pat, line)
            if m:
                msg = line[line.find("]: ") + 3:].strip() if "]: " in line else m.group(0)
                msg = re.sub(r"^(System chat|\[System\] \[CHAT\]):?\s*", "", msg)[:140]
                k = (code, msg)
                found[k] = found.get(k, 0) + 1
                break
    return [(c, msg, n) for (c, msg), n in found.items()]

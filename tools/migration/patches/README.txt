Unapplied native entity rendering proposal

entity-render-state-263.unapplied.patch is a complete eight-file source proposal
for Minecraft 26.3 / NeoForge 26.3.0.90 / Java 25. It is deliberately not applied
to shared Java source while another AI owns UI testing and renderer ownership
is being coordinated. Its JSON sidecar records input/candidate SHA256 hashes.

It repairs custom NPC/humanoid states being routed to the player renderer and
appearance/costume snapshots being erased by NeoForge's post-extraction reset.
It retains public native64x64 meshes, inherited overlays, custom poses, armor,
features and costumes. Genuine player appearance and cape are unchanged.
It also includes the separately reviewed child geometry age-scale correction.

The proposed eight files compiled in an isolated E: source tree against the
actual native jar and dependencies with Java25 javac, exit0, no diagnostics.
The patch passed git apply --check; all eight shared source hashes remained
unchanged. This is not a full build or loaded-world visual acceptance.

Before integrating, coordinate ownership, check the sidecar input hashes and
run git apply --check. Preserve newer edits instead of forcing stale context.
After integration require a full native build, actual dispatcher/snapshot
checks and loaded-world front/back NPC, child/adult, posed humanoid, costume,
armor, glowing-eye and genuine-player cape captures. Do not certify the port
from compilation alone. Do not apply this combined patch and the scratch
component patches together.

Detailed local review, exact native API evidence, compile arguments/signatures
and original diagnostic captures are under:
dist/code-review/neoforge-migration/npc-render-fix
dist/code-review/neoforge-migration/client-38-before-pc-reboot

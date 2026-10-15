# MS1 Regional Story Chain Schema (`skycraft/quests/story/*.json`)

Every region in Tamriel has an authored regional story chain (`MS1` in `docs/world/DESIGN-PRINCIPLES.md` Section 5.1 & 5.4, audited by `tools/qa/story_lint.py` and `tools/qa/flow_audit.py`).

## Purpose

A regional story chain turns a region's World Sheet tensions (`L7`–`L10`) into a playable multi-stage narrative arc that:
1. Hooks the player within **800 blocks of road** after crossing any highway/lane border into the region (`F-HOOK`).
2. Leads the player through at least **3 linked quests** across the region's settlements, landmarks, and marquee/major dungeons (`F-STORY`).
3. Culminates in a `finale: true` quest with a persistent world-state `consequence`.

## Schema

```json
{
  "region": "skyrim/whiterun",
  "why": ["whiterun.L9.neutral_jarl", "whiterun.L9.siege_point"],
  "hook": {
    "place": "riverwood",
    "places": ["riverwood", "rorikstead", "valtheim_towers", "whitewatch_tower", "hamvirs_rest"],
    "npc": "whiterun_hook_npc",
    "max_road_blocks": 800
  },
  "quests": [
    {
      "id": "story_whiterun_1",
      "title": "story.skycraft.whiterun_1.title",
      "requires": [],
      "places": ["riverwood", "bleak_falls_barrow", "whiterun"],
      "stages": [
        {
          "id": 10,
          "place": "riverwood",
          "objective": "Hear the southern watch-warden's report on the barrow tablets and the hold's closed gates."
        },
        {
          "id": 20,
          "place": "bleak_falls_barrow",
          "objective": "Recover the boundary-tablet from the inner sanctum of Bleak Falls Barrow."
        },
        {
          "id": 30,
          "place": "whiterun",
          "objective": "Deliver the boundary-tablet to the steward in Dragonsreach."
        }
      ],
      "consequence": "guard_banner:whiterun_alert",
      "why": ["whiterun.L9.neutral_jarl", "whiterun.L10.dragon_mounds"]
    },
    {
      "id": "story_whiterun_2",
      "title": "story.skycraft.whiterun_2.title",
      "requires": ["story_whiterun_1"],
      "places": ["whiterun", "valtheim_towers", "halted_stream_camp"],
      "stages": [
        {
          "id": 10,
          "place": "whiterun",
          "objective": "Inspect the seized caravan manifests with the hold quartermaster."
        },
        {
          "id": 20,
          "place": "valtheim_towers",
          "objective": "Break the toll blockade at Valtheim Towers and recover the forged seal."
        }
      ],
      "consequence": "patrol_route:whiterun_east_road_open",
      "why": ["whiterun.L10.toll_bandits", "whiterun.L7.clan_feud"]
    },
    {
      "id": "story_whiterun_3",
      "title": "story.skycraft.whiterun_3.title",
      "requires": ["story_whiterun_2"],
      "places": ["whiterun", "dustmans_cairn", "western_watchtower"],
      "finale": true,
      "stages": [
        {
          "id": 10,
          "place": "dustmans_cairn",
          "objective": "Confront the conspirators in the deep crypt of Dustman's Cairn."
        },
        {
          "id": 20,
          "place": "whiterun",
          "objective": "Present the truce charter before the Jarl's council in Whiterun."
        }
      ],
      "consequence": "world_state:whiterun_resolved",
      "why": ["whiterun.L9.siege_point", "whiterun.L7.warrior_hall"]
    }
  ]
}
```

## Field Rules (Enforced by `tools/qa/story_lint.py`)

- `region`: Must be a valid land region ID (`<province>/<region>`) present in `skycraft/tamriel/regions.json`.
- `why`: Non-empty array of World Sheet citations (`<region>.L<7..10>.<id>` or `<province>.L<7..10>.<id>`) that exist in `skycraft/tamriel/world/<province>.json`.
- `hook`:
  - `place`: Primary hook place ID; must exist in `skycraft/tamriel/places/*.json` and lie in or on the border of `region`.
  - `places` (optional): Additional border/outpost hook place IDs where road runners, watch-wardens, or waystone notices initiate the hook.
  - `npc`: Identifier of the hook NPC or notice trigger.
  - `max_road_blocks`: Maximum road distance from a border crossing to a hook place (default `800`).
- `quests`:
  - Array of **at least 3** ordered quests.
  - Each quest requires `id`, `title`, `requires` (array of quest IDs in the same file), `places` (non-empty array of valid place IDs in `places/*.json`), `stages` (non-empty array of stage objects), `consequence` (non-empty string), and `why` (non-empty array citing `L7`–`L10`).
  - `requires` must form an acyclic dependency graph (DAG) with at least one path of length >= 3 ending at a quest with `"finale": true` and a non-empty `"consequence"`.
  - All prose and titles must be 100% original to Skycraft (verified by `tools/qa/verbatim_check.py` and `tools/qa/story_lint.py`).

# Hollowfall Ghosts

A small Fabric mod for **Minecraft 26.3** that lets one person film scenes with several characters.
You record yourself as one character, the mod replays that take as a **ghost** (a mannequin wearing the
character's skin), and you act against the ghost as the next character. Flashback records everything together.

Made for the Hollowfall SMP series.

## Install

1. Download `hollowfall-ghosts-*.jar` from the [latest release](../../releases/tag/latest).
2. Put it in your 26.3 Fabric `mods` folder next to **Fabric API** (and Flashback, The Sift, etc.).

## Filming a fight: the basic loop

Say DamanbigC fights Mossie_.

1. **Record Mossie_ first.** Stand where Mossie_ starts, in Survival or Adventure mode (not Creative), and run
   `/ghost record mossie_fight`. After a 3-second countdown you're recording. Swing at the spot where DamanbigC
   will be, move, dodge. Run `/ghost stop` to save the take.
2. **Give the ghost Mossie_'s look.**
   - A skin from a resource pack: `/ghost skin mossie_fight texture mossie`. This means
     `assets/hollowfall/textures/skin/mossie.png` in a resource pack you have turned on. Use
     `/ghost skin mossie_fight slim true` for a slim-arm skin.
   - Or a real account's skin: `/ghost skin mossie_fight player SomeName`.
   - Name tag: `/ghost name mossie_fight set Mossie_`, or `/ghost name mossie_fight none` to hide it.
3. **Start Flashback, then record DamanbigC against the ghost.** Go back to DamanbigC's start and run
   `/ghost record daman_fight with mossie_fight`. The Mossie_ ghost replays in sync while you fight it.
   - Your hits on the ghost give it the red hurt flash and knockback.
   - When the ghost's replayed swing lines up with you, **you** get the hurt flash and knockback too. Your
     health is put back right away, so a fight can't kill you by accident. Turn this off with `/ghost hits false`.
4. `/ghost stop`, then stop Flashback. That Flashback recording now has both fighters in one shot, ready for
   camera work.

For three or more characters, keep stacking takes: `/ghost record brambly_fight with mossie_fight daman_fight`.
To just watch takes back: `/ghost play mossie_fight daman_fight`.

## All commands

| Command | What it does |
| --- | --- |
| `/ghost record <take>` | Countdown, then record yourself into `<take>` (overwrites it, keeps its skin and name) |
| `/ghost record <take> with <takes...>` | Same, while those takes play back as ghosts |
| `/ghost play <takes...>` | Play takes as ghosts |
| `/ghost stop` | Save the recording and remove the ghosts |
| `/ghost list` | List saved takes with length and skin |
| `/ghost delete <take>` | Delete a take |
| `/ghost skin <take> texture <id>` | Skin from a resource pack (`mossie` = `hollowfall:skin/mossie`) |
| `/ghost skin <take> player <name>` | Skin of a real account |
| `/ghost skin <take> slim <true/false>` | Arm model for texture skins |
| `/ghost name <take> set <text>` / `none` | Name tag above the ghost |
| `/ghost mortal <take> <true/false>` | Mortal ghosts have normal health: your hits hurt them and they can die |
| `/ghost kill <take>` | Kill a playing ghost on cue (death animation) |
| `/ghost nodeath <take>` | Remove the recorded death from a take |
| `/ghost hits <true/false>` | Whether replayed swings hit live players |
| `/ghost countdown <0-10>` | Countdown length in seconds (default 3) |
| `/ghost clear` | Emergency cleanup: stop everything and remove every ghost |

Takes are saved in the world folder under `hollowfall_ghosts/`, so they travel with the world.

## Hitting and killing ghosts

- Hitting a ghost always makes it flinch and knocks it back off its path for a moment. By default it can't be
  hurt, so a fight can't end early by accident.
- `/ghost mortal <take> true` gives that ghost normal health, so enough hits kill it.
- **Scripted deaths:** if you die while recording a take, the take ends there. Every time it plays back, the
  ghost dies at that exact moment. Use `/ghost nodeath <take>` to undo that.
- `/ghost kill <take>` kills a playing ghost whenever you want.

## What gets recorded

Recorded:
- Position and walking
- Head and body turning
- Crouching, swimming, elytra flying and sleeping poses
- Arm swings (main and off hand)
- Held items and armor

Not recorded (yet):
- Using items: bow draws, eating, shield blocking
- Placing or breaking blocks, which the ghost won't do
- Sounds and chat

Fake those in editing, or do them live as the last character you record.

## Credits

The idea of recording a player and replaying it through an "actor" entity comes from **McHorse's BBS mod**
(MIT license, https://github.com/mchorse/bbs-mod). This mod is a small separate implementation for 26.3 and
doesn't include BBS code.

MIT licensed. See `LICENSE`.

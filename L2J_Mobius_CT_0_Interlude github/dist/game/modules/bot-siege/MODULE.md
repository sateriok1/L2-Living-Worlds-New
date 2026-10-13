# Bot Siege module: testing sieges with phantom parties

A module (`dist/game/modules/bot-siege`) that registers two GM voiced commands. It edits no stock script: nothing in `MasterHandler` changes, so the other commands are untouched. Set `Enabled = False` in `config/module.ini` (and restart) to turn it off; delete the folder to remove it. The siege logic itself is in the core class `PhantomSiege`, so the server jar must be built from this branch.

For an NPC-owned castle, attackers only. Everything is off until you run the command, and all commands are GM only.

## Setup (two clients, each leading a party of phantoms)
1. Each real player has a clan (clan level 4 or higher is the safe choice; the setup command registers with the admin override, so the level is not checked) and leads a party of phantoms.
2. From either client:  `.botsiege <castle> <clan> [<clan2>]`
   - Registers each clan as attacker, puts the phantoms of each online real member's party into that member's clan, starts the siege (doors and guards spawn), flags everyone as attackers and turns guard/door targeting on.
   - With no clan names it uses your own clan.
3. Target a castle guard or a door. The party attacks it (casters from range, others in melee).

## Other commands
- `.botclan`: only the clan join. The phantoms of your party join your clan (runtime-only, nothing written to the database).
- `.botsiege guards on|off`: the target flag alone. It does nothing outside a siege started with `.botsiege`.
- `.botsiege status`
- `.botsiege end`: ends the siege, clears registrations, takes the phantoms out of the clans, flag off.

## What it does not do
- No defender clan and no defender behavior (NPC castle only).
- Phantoms do not pick siege targets on their own: they follow the leader's target (assist mode).

## Defending
Same order as against monsters: the leader's guard target comes first. A guard attacking a bot beats a door target (doors do not hit back). With no siege target, a bot fights any guard that is on the leader or a party member, nearest first. Only while the flag is on and the siege is running.
- Hold, stop and other party orders still apply before the siege branch.

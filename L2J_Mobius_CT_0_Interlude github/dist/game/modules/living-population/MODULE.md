# Living Population (Phases 1-5)

A persistent population of bots that live and progress over time. This is the module wrapper (on by default); the
simulation itself lives in the core package `org.l2jmobius.gameserver.livingpop` and only runs when this
module is enabled.

## What it does

- Seeds a starting population of `PopulationSize` bots, each born at level 1 at its race and class's newbie
  starting location, like a real new player. New bots spread evenly over the nine starting classes (fighters and
  mystics of every race, the Dwarf fighter) and get a random name no character uses. Bots from older versions
  named Human000 and so on are renamed on the next start, their characters too. A growing population is born in
  waves (at least `BirthBatch` bots every `BirthIntervalMinutes`, bigger waves for a large population so all arrive
  within about three hours), so levels spread out over time.
- Advances the population cheaply over time (the "cold" state): bots gain experience and level up as if
  hunting, with no actor spawned in the world.
- Keeps the world peered to the online players (Phase 2 director): bots below the players' median level catch
  up (capped), bots above slow down, in-band and no-players are neutral.
- Hot/cold handoff (Phase 3): when a real player walks up to a cold bot it is materialized as a real,
  persistent character that hunts like a field phantom; when the player leaves it is cooled back and its
  progress is saved. The character (name, class, skills, appearance) is remembered between handoffs and across
  restarts, bound to the cold row by a `char_id` column. The cold row stays the source of truth for level and
  experience.
- Goals and needs economy (Phase 4): while cold, a bot pursues player-like goals abstractly on its row. It earns
  adena as if selling loot, receives the one-time newbie soulshot reward at a milestone level, spends soulshots
  as if firing them, restocks them when low and affordable, and upgrades a gear tier when it can afford the next
  one unlocked by its level. Adena, soulshots and potions are shared with the hot character across the handoff.
- Row-driven hot loadout: a materialized bot renders only what it owns, not a fabricated best-in-grade kit. Gear is
  gated to its bought tier (tier 0 = the real newbie starter items), and it carries exactly the soulshots and potions
  on its row. It fires no shots until it has earned or bought them. (Ephemeral field phantoms are unchanged.)
- Persists everything in its own table `living_population_bots`, so the population survives restarts.
- Writes a monitoring snapshot (`SnapshotFile`, default `log/LivingPopulation.json`) and answers a GM-only
  `.livingpop` voiced command with a one-line status (totals, director target, hot count). `.livingpop <name>` shows
  one bot's state and its most recent decisions.

- Travel, towns and supplies (Phase 5, `TravelEnabled`): each bot hunts in a zone picked for its level from
  `data/zones.xml` (newbies in their race's newbie grounds). A goal planner weighs potions, gear, soulshots and zone
  fit, and logs what it put off and why. When a trip is worth it the bot uses a Scroll of Escape, or walks to the
  nearest town when it has none, runs its errands at the grocer and gatekeeper (now and then going AFK), shops only
  in town while keeping an adena reserve, then pays the gatekeeper or walks to its next zone. Hot bots act this out:
  the escape cast, the walk, sitting AFK, the gear swap after an upgrade and the teleport. With travel off, Phase 4
  restocking in place is kept.
- Gear per slot (`GearSlots`): each bot keeps its own weapon, shield, armor pieces and jewelry. In town it buys, after
  its supplies, the best piece per slot its class uses and can afford, from its town's merchants or (`GearTrade`) at
  the reference price as if from another player, and sells what it replaced. It wears weapon and armor drops that are
  better, and a good upgrade it can afford is worth a trip. A hot bot wears exactly the row's pieces.
- Shadow weapons: from level 40 a cold bot no longer buys normal C and B grade weapons or wears the ones that drop (it sells them); it buys the shadow copy the weapon merchants sell through their multisell lists (about 85,000 to 94,000 adena for 300 minutes of wear; only the designs on those lists exist). They cannot be sold and wear out: at each town visit a shadow weapon has run out with a chance equal to the share of its life used since the last visit, and the bot buys a new one. Above level 60 the bot still takes the normal A grade weapon when it can afford one, and keeps buying B shadow weapons until then. Hot bots wear real shadow items, which the server wears out itself.
- Class-aligned gear: every class change is a stable pick per bot, so a bot's whole path (first, second and third class) is settled at level 1. It buys weapon and armor for the last class of that path from the start, so a fighter bound for a dagger line carries daggers at level 1 and never wastes adena on a bow. A Human or Elven knight line tank rolls once, stable per bot, 50/50 between a one-handed sword and a one-handed blunt weapon (every knight line learns Sword Blunt Mastery, and Tribunal and Judgment take either). The Palus Knight line always takes the sword, because its Sting skill needs one. A fighter bound for dual swords (Duelist, Spectral Dancer) has none to buy before Dual Weapon Mastery at level 40, so until then a Human fighter rolls a one-handed sword or blunt with a shield like a tank, and a dark elf takes the sword and shield; both then switch to dual swords. A caster takes a two-handed weapon when it is a nuker, and a one-handed weapon with a shield when it is a healer or buffer.

`data/zones.xml` is generated by `tools/build_living_zones.py` from the server's spawn, teleport and NPC data. Edit
the hand-picked `ZONES` list in that tool and re-run it rather than editing the XML.

## Upgrading an existing install

New columns are added over time and `CREATE TABLE IF NOT EXISTS` will not add them to an existing table. If your
`living_population_bots` table predates these phases, add the columns once before the next run (or, since the rows
are still throwaway verification data, `DROP TABLE living_population_bots;` and let the next start recreate and
re-seed it). Always deploy the rebuilt `GameServer.jar` and this module folder together.

Note on dropping the table: the bots' persistent characters live on the `living_population` account in the stock
`characters` table, which dropping `living_population_bots` does NOT remove. Re-seeded bots get new random names
that no character uses, so the old characters are left unused. For a clean slate, also delete those characters (`DELETE FROM characters WHERE
account_name='living_population';`, or delete them with the admin tools so item/skill rows cascade). If a
re-seeded row's `char_id` stayed 0 on an older jar, bind it by hand:
`UPDATE living_population_bots b JOIN characters c ON c.char_name=b.name AND c.account_name='living_population' SET b.char_id=c.charId WHERE b.char_id=0;`

- Phase 3: `ALTER TABLE living_population_bots ADD COLUMN char_id BIGINT NOT NULL DEFAULT 0;`
- From Phase 5 on, the module adds any missing columns itself on start (`zone`, `town`, `escapes`, `leg` and the
  earlier ones below), so these manual statements are only needed for an older jar.
- Phase 4: `ALTER TABLE living_population_bots ADD COLUMN soulshots BIGINT NOT NULL DEFAULT 0, ADD COLUMN potions BIGINT NOT NULL DEFAULT 0, ADD COLUMN gear_tier INT NOT NULL DEFAULT 0, ADD COLUMN goal VARCHAR(16) NOT NULL DEFAULT 'hunting', ADD COLUMN reward_claimed TINYINT(1) NOT NULL DEFAULT 0;`

## Enable

1. `Enabled = True` is the default in `config/module.ini` (adjust `PopulationSize`, `BirthBatch` and `BirthIntervalMinutes` if you
   want).
2. Make sure the framework master switch is on (`config/Modules.ini`).
3. Restart the server. On first enable the install script creates the table and the seeder runs.

## Disable

Set `Enabled = False` and restart. The module starts nothing; the stored population stays in the table for
next time. The server behaves as stock.

## Remove

Delete this module directory. To also discard the stored population, run `sql/remove.sql` by hand; the
platform never drops the table on its own because it holds bot progress.

## Removing bots

The l2admin monitor can delete one bot, the ticked bots, or reduce the population to a number (it picks random bots
and lists them first). It cannot reach the server, so it writes `LivingPopulation-requests.json` next to the
snapshot (`log/` by default):

```json
{"remove":[{"id":12,"name":"Aerin"}],"populationSize":40}
```

The module reads and deletes the file at start and on every resolver tick. A bot near a player is cooled first; then
its row, its character and everything the character owns are deleted for good. A bot is only removed when its id
and name both match, and only characters on the `living_population` account are deleted. `populationSize` lowers
the target for this run so waves do not refill the gap; l2admin also writes it to `PopulationSize` so a restart
keeps it.

## Configuration (`config/module.ini`)

| Key | Meaning |
|---|---|
| `Enabled` | Master on/off switch (default `True`). |
| `PopulationSize` | Target number of bots (default 100). Raising it adds bots on the next start; lowering it removes none. |
| `BirthBatch` | Fewest new bots born per wave while the population grows (0 = all at once). Default 10. A large population gets bigger waves so all bots arrive within about three hours. |
| `BirthIntervalMinutes` | Minutes between waves of new bots. Default 20. |
| `DirectorLevelGoal` | Level the director pulls the population toward; 0 follows the online players. Default 0. |
| `ColdResolveIntervalSeconds` | How often the resolver advances due bots. |
| `ColdResolveBatch` | Maximum bots advanced per resolver tick. |
| `ColdExpPerMobLevel` | Representative experience a level-appropriate mob gives, per mob level. |
| `ColdKillsPerMinute` | How many level-appropriate mobs a bot clears per minute. |
| `DirectorEnabled` / `DirectorBandRadius` / `DirectorMaxCatchUp` / `DirectorSlowdown` / `DirectorCatchUpSlope` | Phase 2 population director tuning. |
| `HandoffEnabled` | Phase 3 master switch for the hot/cold handoff. |
| `HandoffCheckSeconds` | How often the proximity scan runs. |
| `HandoffActivationRadius` | How close a player must be for a cold bot to go hot. |
| `HandoffDeactivationRadius` | Wider radius a player must leave before a hot bot may cool (hysteresis). |
| `HandoffCooldownGraceSeconds` | How long no player may be near before a hot bot cools back. |
| `HandoffMaxHotBots` | Ceiling on simultaneously hot bots (default 0 = same as `PopulationSize`). They also count toward `PhantomMaxCount`. |
| `EconomyEnabled` | Phase 4 master switch for the goals/needs economy. |
| `AdenaPerMobLevel` | Adena a level-appropriate kill yields, per mob level (used when `DropIncome` is off or a zone lists no monsters). |
| `SoulshotMilestoneLevel` / `SoulshotMilestoneGrant` | Level of the one-time newbie soulshot reward and how many it grants. |
| `SoulshotsPerKill` | Soulshots a kill consumes. |
| `SoulshotRestockThreshold` / `SoulshotRestockBatch` / `SoulshotCost` | When to restock soulshots, how many to buy, and adena each. |
| `PotionRestockThreshold` / `PotionRestockBatch` / `PotionCost` | When to restock healing potions, how many to buy, and adena each. |
| `GearTierLevelStep` / `GearUpgradeCost` | Levels per unlocked gear tier, and the base adena cost of an upgrade (with travel on, only a fallback: a tier costs a full set of its grade from the item data). With `GearSlots` on no tiers are bought; the tier then follows the weapon's grade. |
| `TravelEnabled` | Phase 5 switch for travel, town visits and shopping only in town (needs `EconomyEnabled`). |
| `ZonesFile` | The zone and town catalog. |
| `PotionStock` / `PotionRestockFraction` | Healing potions a melee bot carries (default 30; tanks x1.5, casters, archers and healers x0.5), and the fraction of that at which it restocks. |
| `SoulshotStockMinutes` / `SoulshotRestockFraction` | Minutes of hunting worth of soulshots to carry, and the fraction at which it restocks. |
| `SoulshotMinPurchase` | Smallest batch of soulshots a bot buys in town (default 200); it buys this many or more, or none on that visit. 0 = any amount. Never more than half the soulshot stock. |
| `SpiritshotsPerKill` | Spiritshots a mystic (mage, summoner, healer, buffer) fires per cold kill (default 2), in place of `SoulshotsPerKill`. Mystics buy spiritshots of their grade. |
| `EscapeStock` | Scrolls of Escape a bot likes to carry. Whatever this is set to, a bot always buys one first, even out of its adena reserve, and does not go to a zone with no way home on foot (an island without a town, such as Devil's Isle) unless it carries one. |
| `ReserveFloor` / `ReservePerLevel` / `ReserveFraction` | The adena reserve a bot never spends on supplies, gear or spellbooks (`ReserveFraction` at most 0.5). |
| `MoveSpeed` | Cold walking speed (units per second). |
| `EscapeCastSeconds` / `ErrandStopSeconds` | Scroll of Escape cast time, and time spent at each town NPC. |
| `AfkChancePercent` / `AfkMinMinutes` / `AfkMaxMinutes` | Chance and length of an AFK break per town visit. |
| `ColdPotionsPerHour` | Potions a cold melee bot drinks per hour of hunting before it has earned anything (the starting figure; tanks half again as many, casters, archers and healers half as many). Afterwards a bot drinks the part of what the zone model says it needs that pays for itself in extra kills (it rests for the rest), capped by a quarter of its income over the price of a potion and by the 10 second reuse delay; one in a party drinks none. It buys whichever of the Lesser, Healing and Greater potion costs least for the HP it heals and can keep up with its need. Stock follows the same need (up to 300). |
| `ZoneCapacity` | Bots per zone before it counts as full (0 = no limit). A bot already there moves on only once it is a quarter over. When every zone that fits is full, a bot goes to the least crowded one instead of waiting or walking. |
| `RetrySeconds` | How long a bot with no way on waits in town before trying again (at least 30). A bot too poor for a gatekeeper walks to the nearest fitting zone instead. |
| `WalkToTownWithin` | A town closer than this is walked to even with a Scroll of Escape. |
| `DropIncome` | Cold kills pay from the zone monsters' real drop lists at this server's rates: adena, plus loot sold at half price on the next town visit. |
| `ColdDeathsPerHour` / `DeathRecoverSeconds` | How often a cold bot dies in the middle of a fitting zone (riskier at the bottom, without potions, with old gear, as a caster), and how long it recovers in town. |
| `ZoneCombat` / `ZoneCombatFile` | Zone combat: each cold bot's kills per minute and deaths per hour come from its gear grade and learned skills against the average monster of the zone it hunts in (`data/zone_combat.tsv`, built from the datapack by `tools/combat_sim/build_zone_combat.py`), instead of one flat rate. Calibrated so a well-geared bot in the median zone gets `ColdKillsPerMinute` and `ColdDeathsPerHour`. `False` (or a missing file) keeps the flat rates. |
| `ColdFightShare` / `SkillDamageFloor` | How much of a kill cycle is actual fighting (the rest, finding mobs and looting, does not depend on gear), and the damage a bot with none of its level's skills does (1.0 with all). |
| `ExpLevelGap` | Like the server (`MonsterExpMaxLevelDifference` in Rates.ini), a cold bot earns no experience or SP while hunting in a zone whose average monster is that many levels or more away from it. |
| `BuffedLeveling` / `BuffShareTank` / `BuffShareMelee` / `BuffShareBow` / `BuffShareMage` | Emulates buffers keeping cold bots buffed while they level: the full buffer party's damage and defence multipliers for the bot's level, blended by a share per role (0 = unbuffed, 1 = everything). Off by default. |
| `ShotsMatter` / `SoulshotDamage` / `SpiritshotDamage` | With zone combat, a bot without its soulshots (spiritshots for a mystic) does less damage for the part of the time it has none: x2.0 with soulshots, x1.41 with spiritshots. Its stock lasts as long as its kills use it up. |
| `ZoneRotationTtk` | Fight time from the sim's best skill rotation per class line and level (real seconds against the zone's defence) instead of the relative model calibrated to `ColdKillsPerMinute`. Skills the bot has not learned are skipped. Classes without a rotation line keep the relative model. On by default. |
| `StartingBuffs` | Solo bots hunt buffed: the Newbie Helper's buffs at levels 8-25, then a Hierophant's or Doom Cryer's buffs at the bot's level (average of the two) from 26. On by default; while it is on, `BuffedLeveling` (the full buffer party) is ignored, so a solo bot never gets more than one buffer's buffs. |
| `SelfHeal` | Solo mages, healers and summoners heal themselves with the heals they have learned (real power, mana, cast time and reuse from the skill data) and summoners repair the servitor with Servitor Heal: less sitting and fewer deaths, at a cost in mana and casting time. |
| `ZoneRangedWalk` | Archers and mages keep this share of the walk between monsters (0.5 = half; 1 = off). |
| `ZoneRest` | Sitting to refill HP and MP lowers a bot's kills (estimated per zone, role and level). A solo bot sits at the HP where a fight becomes riskier than `ZoneFightRisk`, and a bot only casts the share of its skills its mana sustains. A bot with potions drinks `ColdPotionsPerHour` (class factor), which heals part of the HP it would sit for. On by default. |
| `ZoneHpDeaths` / `ZoneFightRisk` / `ZoneExtraMonsterChances` | Deaths come from HP: the monsters' hits add up over a fight, extra monsters can join (the 2nd to 6th with these chances), and a bot sits at the HP where a fight's chance of killing it equals `ZoneFightRisk` (0.002 = one fight in 500; lower = a careful player). Zones without rest data keep the old rate. |
| `ZoneRotationWindowSeconds` | Which rotation window the damage comes from (60 s). |
| `ZoneEvasion` | Monsters miss a bot with evasion, so it takes fewer hits (a lower death rate for archers, a higher one for mages). On by default. |
| `ColdParty` / `PartyHealers` | Bots may hunt in a virtual party of 4 to 9 members (tank, healer, buffer and damage dealer, then a spoiler from 6, a minor buffer from 7, two from 8, the rest random; sizes get bigger with level; same-type buffs replace each other; no real party yet). Healers always party; other bots party once hunting alone is a loss, and while alone still earns they take a party with a chance by level (15% at 20, 25% at 30, 35% at 40, 50% at 50, 65% at 60, 80% at 70, 90% at 76). Needs `ZoneCombat`. |
| `PartyHealCoverage` / `PartyChainChance` / `PartyResetSeconds` / `PartyHealMpPerHp` | Party tuning: the bot gets the server's party experience bonus for the party's size divided by the size (1.30 / 4 for four members, 2.20 / 9 for nine); the healer heals `PartyHealCoverage` of the tank's HP loss; a death takes the next in line with `PartyChainChance` and resets the party `PartyResetSeconds`; the healer only heals: its mana is `PartyHealMpPerHp` per HP healed. |
| `BlessedSpiritshotShare` / `BlessedSpiritshotDamage` | Share of mages (picked by bot id) whose damage uses blessed spiritshots (x2.0, M.Atk x4) instead of plain ones (x1.41). Damage only: what bots buy is not changed. Default 0. |
| `ZoneExp` | Experience per kill from the zone's real monsters (times `RateXp`) instead of `ColdExpPerMobLevel x level`. Faster leveling at the same rate, which is the realistic pace. On by default. |
| `RespawnLimit` / `RespawnUsableShare` | A zone only supplies so many monsters a minute; its bots share them, so crowded or sparse zones cap kills per minute. Needs `ZoneCombat`. |
| `AggroPulls` / `AggroPullRisk` | Zones full of monsters that attack on sight are riskier (packs, pulls): the death rate rises by `AggroPullRisk` times the zone's aggressive share. |
| `MaxKillsPerMinute` / `MinDeathFactor` / `MaxDeathFactor` | Limits on the model's highest kill rate and on its death rate as a multiple of `ColdDeathsPerHour`. |
| `RestEveryMinutes` / `RestSeconds` | Cold bots sit down to rest this long after this much hunting. |
| `AvoidZoneMinutes` | After two deaths in one zone within this time, the bot avoids it this long and picks zones two levels lower. |
| `ClassChanges` / `ClassQuestMinutes1` / `2` / `3` | Bots change class at 20, 40 and 76: take the quest from a class master of their race, hunt that many minutes, then hand it in and change class. Each bot always takes the same branch. The class change comes before every other goal, and a bot sees the master right after it sells its loot, before it shops. When its town has no master it follows the cheapest gatekeeper route, through a hub town when no master is a single hop away. The whole route is priced before the bot sets out and its fees stay in the purse while it shops on the way (potions first). At or below `FreeTeleportLevel` the way costs nothing and needs no money; above it a bot that cannot cover the route runs there instead. A class change that falls due ends a break, a rest or a trip to a zone early. |
| `SkillTraining` | Bots earn SP and learn skills at their trainer (the class masters of their line), paying SP and buying spellbooks at real prices, instead of knowing every skill of their level. |
| `GearSlots` | Bots keep gear per slot and gear up piece by piece: on a town visit they buy, slot by slot, the best piece their class uses, their level allows and they can afford, and they wear the better weapon and armor drops (cold bots roll their zone's real drops, hot bots what they pick up). False keeps whole gear tiers. **Which piece first** (when the zone model and an income figure are available): each candidate piece is put on a copy of the gear and the whole set is valued by the zone model (kills an hour with that gear, each worth the zone's adena and loot plus its experience, less the experience its deaths cost at that defence). The bot buys, within its money, the piece that pays its price back soonest (price less what the old piece sells for, over the hourly gain), and saves for the best-paying piece it cannot afford yet (that is also what the Merchant is asked to craft). Only pieces that pay back inside the time they are worth count first: the hours until the next grade opens up at the bot's net experience an hour (4 to 200). Jewelry the model sees no use in (M.Def does not change kills or deaths) never ranks, so a bot gears weapon and armor first and buys the rest, by the old rule of a piece 15 percent better, only once nothing is left that pays back, since its money has no other use. Without a valuation (no income figure yet after a restart, or an unknown zone) the old rule alone applies. |
| `GearTrade` | Gear no shop in the bot's town sells (town shops stop at D grade) can be bought as if from another player, at the item's reference price. A stand-in until bots trade with each other (`GearCatalog.tradePrice`); with it off, better gear comes only from drops. |
| `FreeTeleportLevel` | The highest level at which gatekeepers teleport a bot for free. -1 (the default) follows the server's `MaxFreeTeleportLevel` (40); 0 makes every bot pay. Bots above it pay the listed fee. |
| `MaxLevel` | Level at which cold progression stops. |
| `SnapshotIntervalSeconds` | How often the monitoring snapshot is written. |
| `SnapshotFile` | Path of the monitoring JSON snapshot. |

**Early warning.** One death in a zone is already a warning: for `AvoidZoneMinutes` the bot prefers another zone that fits its level (it picks it when leaving town to recover) and stays only if nothing else fits. A second death there still makes it avoid the zone and hunt two levels lower.

**Fail-safe.** A young bot nothing else suits (its race has no newbie grounds in the data) goes to the nearest newbie grounds it can use instead of standing still.

**One list of needs.** `ColdLife.needsOf` reads a bot's errands (class change, skills, gear, the Merchant's craft) and whether a class change is pending in one place; the goal planner and the raid director both read it.


Town stock (`TownStock`, table `living_town_listings`): the sellable items bots hunt (cold bots from the zone's drop lists and rates, hot bots from the real items in their bag; weapons and armor are left out of the cold count) go into the stock of the town they sell in, each kept as the hunting bot's own listing, like a back-end auction house. A bot is not paid when items drop. It is paid when they sell, or when the stock clears: every status snapshot, a share of each listing (half of it every `TownStockHalfLifeHours`, default 1, so a bot's unsold drops are vendored within a couple of hours and it can keep going) is vendored and its owner gets the vendor price. The fake-player SELL and PACKAGE vendors within 6000 units of a town's arrival point sell only that town's stock (no rolled goods): a vendor opens a shop once the town has at least two different items for it, checks every 30 seconds, trims its lines as the goods go and closes when they are gone. When a player buys a line, the items leave the stock and the adena goes to the bots they belonged to, split by how many each owned: added to the live character when the bot is in the world, else to its row (the shop price is 1.0 to 1.4 times the reference price, so it pays more than the vendor). BUY, Ancient Adena, craft and chat-arranged deal stores are not changed. With the module off, the SELL and PACKAGE vendors roll their own stock as they did before (shots, materials and gear). The stock is saved with each status snapshot and written to `LivingPopulation-townstock.json` next to it.

The zone combat model (`ZoneCombat`, rotations, zone supply, deaths, experience) is explained in detail in `ZONE_COMBAT.md`.

## Merchant (shot crafting)

An Artisan or Warsmith cold bot supplies the D and C soulshots and blessed spiritshots that no NPC shop sells (`MerchantCrafting`, settings in `module.ini`).

- **Demand.** Each town records the shots its hunters fire and want per hour (`LivingPopulationManager.shotDemand()`). Every hunter's D shot demand is counted as Gludio's and every C shot's as Giran's, and the Merchant reads it there. It is saved to `LivingPopulation-shotdemand.txt` next to the snapshot (on every snapshot and on stop) and loaded at start, with the downtime left out, so a restart does not lose it.
- **Recipes.** It learns the Soulshot D/C and Blessed Spiritshot D/C recipes by buying them from the towns' stock at their reference price (the owner is paid), travelling by gatekeeper to the town that holds one. Recipe ids 1804, 1805, 3953, 3954.
- **Trip.** It buys the materials in Giran (Soul Ore, Spirit Ore, crystals at their production cost), pays the gatekeeper to Devil's Isle (free at or below `FreeTeleportLevel`), and sits there `MerchantCraftMinutes`. The mana cost of crafting is skipped: the materials leave its inventory and the shots replace them. It reads a Scroll of Escape (it always carries one) to land in Giran, then sells in Giran (C) or pays the gatekeeper to Gludio (D) and puts the shots on sale. The town's fake-player vendors sell them, never below production cost plus 1 adena. Unsold shots do not clear from the stock.
- **Craft goal.** The Merchant's shot business is a planner goal, not only something she does when she happens to be in town. When the hunters want shots nobody has on sale (or a recipe she can learn is in stock) and she can afford it, the planner sends her to town at priority 55: below her own supplies (potions, soulshots), gear and a zone change, above the trainer. It is blocked, and she keeps hunting, when she carries unsold goods or has no Scroll of Escape for the isle; there is no errand at all when the stock on sale already covers the demand, no money, or no recipe in reach, so she does not loop. A hunter's gear order is a craft errand too: with a wish quoted and the materials in reach she makes the trip for it, under the same blockers. A bot with no potions and no money to buy any is not picked for a raid.
- **Price.** Never below production cost plus 1 adena. While a shop holds fewer shots than hunters want for `MerchantHoursOfStock` hours, the price is up to `MerchantMaxMarkup` (default 50%) above that, falling linearly as the shop fills; once it holds enough the price is the floor.
- **Shops.** The shots are sold by two named phantoms, SatDShots in Gludio (D) and SatCShots in Giran (C); the town's crowd vendors do not sell them. The Merchant is a dwarf named Sateya, born at level 1 as a Dwarven Fighter, who becomes an Artisan.
- **Travel.** Sateya pays no gatekeeper fee between towns (a merchant moves money by alt and goods by warehouse); only the gatekeeper to Devil's Isle is charged, and that is free at or below `FreeTeleportLevel`.
- **Priorities.** From level 20 it covers all D demand before it spends adena on its own gear; at level 40 it takes on C, still D first.
- **Simplified for the first version.** The Merchant's trips between towns (Giran for materials, Gludio to sell) cost no gatekeeper fee. This stands in for how a min-maxing merchant plays: a character under level 41 uses the free gatekeepers for the shopping trips, and the warehouse or a second account moves goods, so the real merchant pays almost nothing to move. Modeling that alt and warehouse network would add a lot of complexity, so the first version just charges nothing. Only her trip to Devil's Isle follows the normal fee rule (free at or below `FreeTeleportLevel`).
- **Her class path.** She crafts as an Artisan (level 20), a Warsmith (level 40) and a Maestro (level 76). Her class change comes first like any bot's, before her shopping and crafting. After a crafting trip she visits her trainer for any skills that wait, since crafting comes before the trainer in the town errands.
- **Not yet.** Hunters do not buy from these shops yet (they hunt without shots when out); the Merchant does not drop its trips when it is a live character.
- **Buying.** A bot that fires D or better shots (blessed spiritshots for mages from D grade on, since only blessed ones are made) buys them only from the shops: at the grocer they are not for sale. When it needs shots and the shop in Gludio (D) or Giran (C) holds a worthwhile batch, it goes there by gatekeeper (the usual fee above the free level), buys what it can afford above its reserve at the shop's price, and the shots leave the stock and the price goes to Sateya. With the shop empty it hunts without shots, as it always has. Stock of earlier-grade shots it holds when its weapon grade rises is not converted.
- **Outgrown shots.** When the shots a bot fires change (a weapon upgrade, a class change), the shots it still holds are no use. D and C shots go back to the shop that sells them: they leave the bot, go onto the shelf under Sateya's name, the bot is paid the price the shop sells at now, and that adena comes out of Sateya's purse (it cannot go below zero). Any other old shots (no grade and the like) are sold to a vendor at half the reference price. Every sale in SatDShots or SatCShots pays Sateya, whether the shots were her crafts or buy-backs.
### Gear crafting (`MerchantGearCrafting`)

Sateya also crafts armor and weapons, on order. It runs after the shots: when the shots need nothing, she turns to the orders. There is no gear shop; the hunter and Sateya meet and trade.

- **The book of orders.** A hunter that still wishes for a better piece it can wear (the cheapest good upgrade it could not afford) puts a wish in the book (`GearOrders`), with what it can spend. It only counts if she can craft that piece **right now**: her Create Item level reaches the recipe's (levels 5, 20, 28, 36, 43, 49, 55, 62, 70 give 1 to 9), the grade is open to her, the recipe is hers or is in a town's stock or a vendor shop, every material is in a town's stock or a vendor shop, and her price would be under the piece's reference price and under any vendor shop's price. The book is saved (`LivingPopulation-gearorders.txt`); a wish she is working on is never dropped, and a wish nobody refreshes is dropped after `MerchantWishHours`.
- **Priority.** She works the wishes strongest hunter first (highest level, then oldest), so the biggest upgrades get the first grab at her time and at the materials in the town stock.
- **Quote.** For the order she rolls the share of the reference price she pays for stock between `MerchantStockShareMin` and `MerchantStockShareMax` (0.6 to 0.8; the owners of the stock are paid that, a vendor pays 50%), recalculates the cost with it, and quotes the cost plus 10%. What the stock lacks she buys from the vendor shops (the lowest buylist price). A recipe she has to buy is added to the price of that first piece if the hunter can afford it, else 20 to 33% of its cost, so the next crafts bring it back. She goes ahead only if the hunter can pay the price.
- **Advance.** She lays out only what she can afford above her reserve. If she cannot cover the materials, the hunter advances her the difference (it is taken from the hunter at once and counted against the price). If the order goes stale, the advance is returned.
- **One at a time, and slow.** She crafts one order at a time. A craft takes `MerchantGearCraftMinutes` (45) at Create Item level 1, shorter as the level rises (to a quarter), and longer for a recipe near her limit.
- **Travel.** She walks the towns that hold the stock (`MerchantShopLegSeconds` a town: the walk to the gatekeeper, the teleport and the shops), with no gatekeeper fee (a level 1 did the walking), crafts in the last of them, then takes the piece to Gludio (D) or Giran (C and better) and waits `MerchantMeetMinutes`. She pays the gatekeeper to Devil's Isle for shots above `FreeTeleportLevel`, so she pays when she levels at 41+.
- **The trade.** The finished piece is parked in the sale town's stock under her name, reserved for the hunter that ordered it. The hunter sees the order at its next town visit, pays the gatekeeper if it is somewhere else and can still afford what it owes (the price less its advance), goes to the town and buys the piece like any offer: it leaves the stock, she is paid, the order closes. It is one transfer, so two hunters cannot take the same piece. A piece nobody comes for in `MerchantReadyHours` is sold to a vendor at half its reference price and the advance is returned.
- **Reagents.** The town stock clearing never takes crafting reagents or recipes, her shots, or her parked gear; the clearing never touches those, so the quick clearing of everything else (`TownStockHalfLifeHours` 1) does not starve her.
- **Reserves.** She keeps `MerchantWorkingCapital` adena (default 3,000,000) for crafting and shots before she spends any on her own gear or skills, and spends only `MerchantUpgradeShare` (default 0.5) of what is above that plus her usual operating reserve.

- **Staying cold.** Sateya is a live character only while she hunts or walks to her zone: her town business and trips run cold, so a player walking up to her cannot break one. If she is live when she leaves the hunt, she cools.

## Raid parties

Raid bosses are hunted by the cold bots themselves, no bot decides it. Every `RaidCheckMinutes` the module looks at each raid boss of `RaidMinLevel` to `RaidMaxLevel` that is up, highest level first. It builds a team from the cold bots that are hunting, have a Scroll of Escape and are `RaidLevelsBelow` below to `RaidLevelsAbove` above the boss's level: the sturdiest tank, the best healer and buffer, then the strongest damage dealers, the smallest party of `RaidMinParty` to `RaidMaxParty` that clears `RaidMinWinChance`. If none does, the boss waits.

- **The fight** (`RaidPlanner`): the team's damage (each class's rotation against the boss's defence, from the zone combat model, with shots, the baseline buffs every cold bot is taken to have, and the team buffers' multiple on top) over the boss's HP gives the fight length. What hits the party comes from the boss's own data: its plain hits and its single-target damage skills fall on the tank, its area skills on every member, and its minions (the `Privates` list) hit the tank until the party has killed them, which it does first (their HP is added to the work). A skill is used once every `RaidBossSkillSeconds` (or its reuse, if longer); a magic skill is turned to the P.Atk scale with the magic damage formula. `RaidBossBurst` multiplies all of it (1 = as modeled). The healers heal the tank first and then whoever the area skills hurt, with their mana lasting four minutes at full strength. The team wins if the tank (and the rest of the party) outlast the fight; fights longer than `RaidMaxFightMinutes` fail. The win chance runs from 0 at 60% of the fight length to 1 at 140%. The model is deliberately on the optimistic side (perfect healing, minions held by the tank, no stuns, holds or debuffs, no minion skills or respawns).
- **Supplies**: shots and potions work the same way in a raid and in normal cold hunting. A stock lasts as long as the bot uses it: a member does its shot damage while its shots last and its plain damage after, and drinks potions (healing itself) only while its potions last, so a bot with one shot or one potion gets the effect for a moment of the fight, not all of it. The planner sees this when it picks the team. What is used is then taken from the stock: shots at the hunting rule (one volley per attack, the grade's shots each) and recorded as demand in the shop the bot buys them in, so Sateya's production sees it; potions at the bot's hunting rate. In normal hunting the potion help to the kill rate is scaled the same way, by the share of the span the stock covers (it used to be all or nothing on having any). A shadow weapon wears by the clock, so the hours of a raid count toward it.
- **Saved right away**: when a party leaves and when it comes home (win, wipe or turned back), each member's row is written at once, not at its next resolve.
- **Who is free**: a bot with its class change to do (quest under way, or its level due) is not asked. Its other errands (gear, skills, a zone move) do not hold it back, since a raid is rarer and pays more; it stops at the nearest grocer first and stocks up on potions, shots and scrolls (no gear), as a player would before the fight.
- **The run**: the party leaves hunting (activity `raiding`, cold the whole time), gathers `RaidGatherMinutes`, walks to the boss and fights. When it ends, every member reads a Scroll of Escape to the nearest town and runs its normal errands there.
- **The loot** (`RaidLoot`): the boss's real drop list is rolled once at the server's raid rates. Adena is split equally. The items are split as evenly in value as whole items allow, as cooperative players would: the most valuable item goes to whoever holds the least so far. Each member's items go into the town stock under the member's own id, so they sell in the shops and pay the owner. Experience and SP are shared with the party bonus.
- **Deaths**: on a win a member dies with a chance that grows as the margin shrinks; on a wipe all die, with the usual experience loss and recovery. A party that wipes goes again once with the same members (`RaidRetries`, each wipe costs every member the usual death); if it wipes again it is disbanded, and each of its members stays out of every raid party, for this boss or another, for `RaidCooldownMinutes` (30). The boss itself can be taken by another party at once.
- **The world boss** (`RaidWorldSync`, on by default): a party only goes for a boss that stands alive in the world with no player within 3000 units and nobody fighting it, and a win kills the real boss with a command kill, so the server's own respawn timer starts and a player later finds the same state. If a player kills the boss or comes to it while the party is on the way, the party turns back with no loss. A raid does not survive a restart: its party goes back to hunting.
- **Without the sync** (`RaidWorldSync = False`): the module keeps its own respawn clock (the spawn table's delay and random), saved in `LivingPopulation-raids.txt`, and the boss in the world is never touched.

- **Raid tracking**: every fight is recorded per boss (a retry counts as a fight) with the win chance the model gave its party: fights, wins, the wins expected, the average party size, fight length and tank margin, and the latest 20 results. Saved in `LivingPopulation-raidstats.txt` (kept across restarts) and written as `LivingPopulation-raidstats.json` next to the snapshot, bosses sorted by how far their recent results are from the model. A boss is flagged as an outlier when, over its latest 8 to 20 fights, the wins are more than about 2.3 standard deviations from the wins the model expected: "deadlier than the model says" (for example 20 failures at an 80% chance) or "easier than the model says". The server log gets one `raid outlier` warning when a boss becomes one. Use it to find bosses the fight model is wrong about and to tune `RaidBossBurst` and `RaidMinWinChance`.
- **The win chance** (`RaidMinWinChance`, 0.8 by default): the chance of winning a team must have before it goes. Lower it and parties take on harder bosses and wipe more. It is read at start, so restart or reload the module after changing it.
- **Higher levels**: `RaidMaxLevel` (87 by default) and `RaidMinLevel` decide which bosses are open; any range works without code changes. Grand bosses are a different npc type and are never taken; `RaidExcludeBosses` is a list of other boss ids to leave alone. A higher-level boss is taken only when the bots in its level window (`RaidLevelsBelow`/`RaidLevelsAbove`) can fit a team that clears the fight, so the strong bosses simply wait until bots are strong enough. Several raids run at the same time (there is no cap): at each check every boss that is up is offered to whatever ready bots are left, so as one party takes a boss the next party can take the next.
- **Not yet done**: command channels (two or more parties on one boss, for the big raid bosses and grand bosses) are a later step; the party size is capped at one party (`RaidMaxParty`, up to 9).

### Needs a live test

Gear crafting and the player requests also need a live test, in particular: a craft resumed after a restart, the refund when a player or hunter does not pay, the 10-minute question to a player and its yes/no, the `trade` distance check, a live hunter paying for its piece, and the 4 to 10 order streak and the rest after it.

None of the raid code has run on a server yet. The first thing to check is the command kill: a win calls `doDie(null)` on the real boss, which should drop nothing in the world (the party's loot goes to the town stock) and start the server's own respawn timer through the raid boss manager. Check that the boss is gone, that the respawn comes back at its normal delay and that no reward is given to anyone. Then watch the first few raids: how many bosses are taken at once, the wipe rate at the chosen win chance, and the experience a raid pays.

### Gear crafting: player requests and safety controls

- **Player requests**: a player whispers Sateya `craft <exact item name or item id>` (the name must be exactly as the game shows it; she cannot guess, and says so). If she can make it now (her craft level, the recipe and materials to be had, the price within the player's adena), she sets the town stock it needs aside for that player (a ledger: other orders cannot count it) and tells the player the price. A player's request goes ahead of the hunters' queue. Her reply warns the player to keep the adena on hand (she checks it when she starts and when it trades), that an unpaid order is cancelled an hour after it is ready, and to use the item id if the name has odd characters. If she is in the middle of a craft she says she is busy and will message the player when she has finished the current job and started theirs. If she cannot make it, the player may ask again after 5 minutes. When it is ready she whispers the player; the player goes to the sale town (Gludio for D, Giran for C and better) and within 3000 units of her (of the town's arrival point if she has left), whispers `trade`, pays what it owes and receives the piece. Other commands: `cancel` (while still queued), `status`. One request per player an hour (`PlayerRequests.COOLDOWN_MS`), counted from when it was made, whether it was filled, cancelled or lapsed.
- **When a hunter is held up**: if a hunter's wish cannot be filled only because of stock a player's request set aside, and that request has waited 10 minutes, Sateya whispers the player and asks whether to go ahead now. `yes` puts the request first in her queue (she must start it within 30 minutes); `no`, or no answer in 5 minutes, releases the stock.
- **An order nobody pays for**: Sateya lays out her own money on every order. If a player has not paid within an hour of the piece being ready (a hunter within `MerchantReadyHours`), she is refunded what she laid out, keeps the materials in the town stock for other crafts, the piece is removed, and any advance goes back. This is the fail-safe.
- **A blocked wish**: a hunter's wish that she could fill except for stock a player's request has set aside. It is remembered for 30 minutes so the player can be asked (see above).
- **Players skip the safety rest**: a player's request is taken even while she is resting, and does not count toward her streak. While a player's request or order is going, the hunters' wishes wait (no new wishes are posted).
- **A streak, then hunting**: she does 4 to 10 orders in a row (rolled for each streak: `MerchantGearStreakMin`/`Max`), then hunts for at least `MerchantGearHuntMinutes` (120) before the next order, so she never crafts all the time. A craft cut short by a restart is picked up again.
- **Money**: a hunter's money for an open order is spoken for (it cannot spend it elsewhere), the hunter's means are checked again when she decides, a hunter that cannot pay is skipped for the next, and a live hunter is charged only what it holds. Her lesson money stays out of what she lays out on a craft. Pieces of a craft beyond the ones other orders hold are sold to a vendor at half their reference price when the trade is done.

## Named bots

Titles: Sateya "The Merchant", aiSateriok "Dev God", aiSonOfZion "Bot Torturer", aiTeras "Administrator" (shown while they are live characters).

The first birth wave also adds four named bots, each only if no bot or character already has the name: Sateya (the Merchant, above), aiSateriok (a male human Fighter who always becomes a Warrior, Gladiator and Duelist, with no other special behavior), aiSonOfZion (a man: half the time a human Mystic who becomes a Cleric and a Bishop, half an Orc Fighter who becomes a Titan) and aiTeras (a dwarf, a man or a woman by a coin toss, who becomes a Scavenger, a Bounty Hunter and a Fortune Seeker: a spoiler). If a character already uses one of the names, that bot is not born and a warning is logged.

/*
 * Copyright (c) 2013 L2jMobius
 *
 * This file is part of the L2J Mobius project.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.l2jmobius.gameserver.livingpop;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.l2jmobius.gameserver.modules.Json;

/**
 * The durable state of one cold bot: a plain, dependency-free data row that is the single source of truth for that bot
 * across the cold/hot boundary and across server restarts. The database persists exactly these fields; the resolver
 * mutates the progression fields; the monitoring snapshot reads them.
 *
 * <p>It carries no game types on purpose, so it can be built and asserted in the standalone test lane. In Phase 3 the
 * hot/cold handoff materializes a bot as a real persistent character: {@link #getCharId()} binds the row to that
 * character (0 until one exists), and {@link #isHotLock()} guards a bot that is currently hot from also being advanced by
 * the resolver.
 *
 * <p>The fields the handoff touches from the game thread while the resolver runs on its own thread ({@code level},
 * {@code expIntoLevel}, position, {@code phase}, {@code charId}, {@code hotLock} and the resolve stamps) are
 * {@code volatile}. The hot lock is the ordering guard: the resolver skips a locked bot, and only the resolver thread
 * clears it (the handoff queues that step back to it), so a cooled bot's captured state is written and visible before the
 * resolver picks it up again.
 */
public class ColdBot
{
	private long _id;
	private String _name;
	private String _race;
	private int _classId;
	private volatile int _level;
	private volatile long _expIntoLevel;
	private long _sp;
	private long _adena;
	private volatile String _region;
	private volatile int _x;
	private volatile int _y;
	private volatile int _z;
	private volatile String _activity;
	private volatile String _phase;
	private long _createdAt;
	private volatile long _updatedAt;
	private volatile long _lastResolvedAt;
	private volatile long _nextResolveAt;
	private volatile boolean _hotLock;
	private volatile long _charId;
	private volatile long _soulshots;
	private volatile long _potions;
	private volatile int _gearTier;
	private volatile String _goal;
	private volatile boolean _rewardClaimed;
	private volatile String _zone;
	private volatile String _town;
	private volatile long _escapes;
	private volatile TravelLeg _leg;
	private volatile long _loot;
	private volatile int _deaths;
	private volatile ColdRisk.State _risk = ColdRisk.State.EMPTY;
	private volatile ClassPath.Quest _quest;
	private volatile String _skills;
	private volatile String _gear;
	private final DecisionLog _decisions = new DecisionLog();
	// Lifetime hunting counters for the monitor, persisted next to the decision log in stats_json (no schema change).
	private volatile double _partyDeathFactor; // zone model: the death factor while hunting in the virtual party (0 = hunting alone); not saved
	private volatile double _partyDeaths = -1.0; // zone model: deaths an hour in the virtual party from the HP model (-1 = none); not saved
	private volatile double _killRate; // zone model: kills a minute last given (0 = none yet); not saved
	private volatile double _incomeRate; // adena an hour its hunting last earned, kills and loot together (0 = none yet); not saved
	private volatile double _potionCap = -1.0; // potions an hour it can afford to drink (-1 = not worked out yet, use the flat habit); not saved
	private volatile double _potionRate = -1.0; // potions an hour it last needed in its zone (-1 = none yet); not saved
	private volatile double _potionUse = -1.0; // potions an hour it chose to drink after weighing them against resting (-1 = not worked out yet); not saved
	private volatile long _potionPrice; // adena one unit of potion costs it now (0 = unknown); not saved
	private volatile double _shotsPerKill; // zone model: shots a kill takes it with its real skills and shot kind (0 = none yet); not saved
	private volatile double _kills;
	private volatile long _adenaEarned;
	private volatile long _shoppedAt; // when it last finished its errands in town (0 = never), kept with the counters
	private volatile int _shotItem; // the shot item its soulshot stock is of (0 = not noted yet), kept with the counters
	private volatile int _gearStreak; // orders done in the current streak
	private volatile int _gearCap; // orders allowed in the current streak (rolled when it starts, 0 until then)
	private volatile long _gearRestUntil; // a Merchant: she takes no gear order before this time (the rest after a craft keeps her hunting)
	private volatile Set<Integer> _gearRecipes = Set.of(); // a Merchant: the recipe items of the gear she has learned to craft
	private volatile int _recipes; // a Merchant: bits of the shot recipes it has learned (ShotCrafting.bit), kept with the counters
	private volatile int _carryItem; // a Merchant: the shots it crafted and has yet to put on sale
	private volatile long _carryCount;
	private volatile String _carryTown; // where it puts them on sale

	/** @return the death factor of the virtual party the bot hunts in, 0 when it hunts alone (not saved) */
	public double getPartyDeathFactor()
	{
		return _partyDeathFactor;
	}

	/** @return deaths an hour in the virtual party from the HP model, -1 when it has none (hunting alone, or the zone has no rest data) */
	public double getPartyDeathsPerHour()
	{
		return _partyDeaths;
	}

	/** @return the kills a minute the zone model last gave this bot, or 0 before it has one */
	public double getKillRate()
	{
		return _killRate;
	}

	/** @return the shots a kill last took this bot, with its learned skills and the shot kind it fires, or 0 before it has one */
	public double getShotsPerKill()
	{
		return _shotsPerKill;
	}

	public void setShotsPerKill(double perKill)
	{
		_shotsPerKill = Math.max(0.0, perKill);
	}

	/** @return adena an hour the last stretch of hunting earned (kills and loot), or 0 before it has one */
	public double getIncomeRate()
	{
		return _incomeRate;
	}

	public void setIncomeRate(double adenaPerHour)
	{
		_incomeRate = Math.max(0.0, adenaPerHour);
	}

	/** @return potions an hour the bot can afford to drink, or -1 when that has not been worked out (the flat habit applies) */
	public double getPotionCap()
	{
		return _potionCap;
	}

	public void setPotionCap(double perHour)
	{
		_potionCap = (perHour < 0.0) ? -1.0 : perHour;
	}

	/** @return potions an hour the bot chose to drink after weighing them against resting, or -1 before that has been worked out */
	public double getPotionUse()
	{
		return _potionUse;
	}

	public void setPotionUse(double perHour)
	{
		_potionUse = (perHour < 0.0) ? -1.0 : perHour;
	}

	/** @return adena one unit of potion costs the bot, or 0 when not known */
	public long getPotionPrice()
	{
		return _potionPrice;
	}

	public void setPotionPrice(long price)
	{
		_potionPrice = Math.max(0L, price);
	}

	/** @return potions an hour the bot last needed in its zone, or -1 before it has one */
	public double getPotionRate()
	{
		return _potionRate;
	}

	public void setPotionRate(double perHour)
	{
		_potionRate = (perHour < 0.0) ? -1.0 : perHour;
	}

	public void setKillRate(double killsPerMinute)
	{
		_killRate = Math.max(0.0, killsPerMinute);
	}

	public void setPartyDeaths(double factor, double deathsPerHour)
	{
		_partyDeathFactor = factor;
		_partyDeaths = deathsPerHour;
	}

	public long getId()
	{
		return _id;
	}

	public void setId(long id)
	{
		_id = id;
	}

	public String getName()
	{
		return _name;
	}

	public void setName(String name)
	{
		_name = name;
	}

	public String getRace()
	{
		return _race;
	}

	public void setRace(String race)
	{
		_race = race;
	}

	public int getClassId()
	{
		return _classId;
	}

	public void setClassId(int classId)
	{
		_classId = classId;
	}

	public int getLevel()
	{
		return _level;
	}

	public void setLevel(int level)
	{
		_level = level;
	}

	public long getExpIntoLevel()
	{
		return _expIntoLevel;
	}

	public void setExpIntoLevel(long expIntoLevel)
	{
		_expIntoLevel = expIntoLevel;
	}

	public long getSp()
	{
		return _sp;
	}

	public void setSp(long sp)
	{
		_sp = sp;
	}

	public long getAdena()
	{
		return _adena;
	}

	public void setAdena(long adena)
	{
		_adena = adena;
	}

	public String getRegion()
	{
		return _region;
	}

	public void setRegion(String region)
	{
		_region = region;
	}

	public int getX()
	{
		return _x;
	}

	public void setX(int x)
	{
		_x = x;
	}

	public int getY()
	{
		return _y;
	}

	public void setY(int y)
	{
		_y = y;
	}

	public int getZ()
	{
		return _z;
	}

	public void setZ(int z)
	{
		_z = z;
	}

	public String getActivity()
	{
		return _activity;
	}

	public void setActivity(String activity)
	{
		_activity = activity;
	}

	public String getPhase()
	{
		return _phase;
	}

	public void setPhase(String phase)
	{
		_phase = phase;
	}

	public long getCreatedAt()
	{
		return _createdAt;
	}

	public void setCreatedAt(long createdAt)
	{
		_createdAt = createdAt;
	}

	public long getUpdatedAt()
	{
		return _updatedAt;
	}

	public void setUpdatedAt(long updatedAt)
	{
		_updatedAt = updatedAt;
	}

	public long getLastResolvedAt()
	{
		return _lastResolvedAt;
	}

	public void setLastResolvedAt(long lastResolvedAt)
	{
		_lastResolvedAt = lastResolvedAt;
	}

	public long getNextResolveAt()
	{
		return _nextResolveAt;
	}

	public void setNextResolveAt(long nextResolveAt)
	{
		_nextResolveAt = nextResolveAt;
	}

	public boolean isHotLock()
	{
		return _hotLock;
	}

	public void setHotLock(boolean hotLock)
	{
		_hotLock = hotLock;
	}

	/**
	 * @return the object id of the persistent character this bot is bound to, or 0 when it has never been materialized
	 */
	public long getCharId()
	{
		return _charId;
	}

	public void setCharId(long charId)
	{
		_charId = charId;
	}

	/** @return the abstract soulshot count the bot holds (Phase 4 economy; a hot phantom uses its own working stack) */
	public long getSoulshots()
	{
		return _soulshots;
	}

	public void setSoulshots(long soulshots)
	{
		_soulshots = soulshots;
	}

	/** @return the abstract healing-potion count the bot holds (Phase 4 economy) */
	public long getPotions()
	{
		return _potions;
	}

	public void setPotions(long potions)
	{
		_potions = potions;
	}

	/** @return the bot's abstract gear tier (Phase 4 economy; 0 = starting gear) */
	public int getGearTier()
	{
		return _gearTier;
	}

	public void setGearTier(int gearTier)
	{
		_gearTier = gearTier;
	}

	/** @return the bot's current high-level goal (Phase 4): {@code hunting}, {@code restock}, or {@code upgrade} */
	public String getGoal()
	{
		return _goal;
	}

	public void setGoal(String goal)
	{
		_goal = goal;
	}

	/** @return whether the one-time newbie soulshot reward has been granted (Phase 4 milestone) */
	public boolean isRewardClaimed()
	{
		return _rewardClaimed;
	}

	public void setRewardClaimed(boolean rewardClaimed)
	{
		_rewardClaimed = rewardClaimed;
	}

	/** @return the hunting zone the bot is in or heading to (Phase 5), or null */
	public String getZone()
	{
		return _zone;
	}

	public void setZone(String zone)
	{
		_zone = zone;
	}

	/** @return the town the bot is in or heading to (Phase 5), or null while in the field */
	public String getTown()
	{
		return _town;
	}

	public void setTown(String town)
	{
		_town = town;
	}

	/** @return how many Scrolls of Escape the bot carries (Phase 5) */
	public long getEscapes()
	{
		return _escapes;
	}

	public void setEscapes(long escapes)
	{
		_escapes = escapes;
	}

	/** @return what the loot it carries would sell for at a shop, in adena (sold on its next town visit) */
	public long getLoot()
	{
		return _loot;
	}

	public void setLoot(long loot)
	{
		_loot = loot;
	}

	/** @return how many times it has died while cold */
	public int getDeaths()
	{
		return _deaths;
	}

	public void setDeaths(int deaths)
	{
		_deaths = deaths;
	}

	/** @return its rest timing, recent deaths and any zone it is avoiding (never null) */
	public ColdRisk.State getRisk()
	{
		return _risk;
	}

	public void setRisk(ColdRisk.State risk)
	{
		_risk = (risk == null) ? ColdRisk.State.EMPTY : risk;
	}

	/** @return its class-change quest in progress, or null */
	public ClassPath.Quest getQuest()
	{
		return _quest;
	}

	public void setQuest(ClassPath.Quest quest)
	{
		_quest = quest;
	}

	/** @return the skills it has learned, as {@link SkillPlanner#encode} text, or null when not tracked yet */
	public String getSkills()
	{
		return _skills;
	}

	public void setSkills(String skills)
	{
		_skills = skills;
	}

	/** @return the gear it wears, as {@link LivingGear#encode} text, or null when not kept per slot yet */
	public String getGear()
	{
		return _gear;
	}

	public void setGear(String gear)
	{
		_gear = gear;
	}

	/** @return the travel or town step in progress (Phase 5), or null while hunting */
	public TravelLeg getLeg()
	{
		return _leg;
	}

	public void setLeg(TravelLeg leg)
	{
		_leg = leg;
	}

	/** @return the bot's recent decisions, in plain words (persisted in {@code stats_json}) */
	public DecisionLog getDecisions()
	{
		return _decisions;
	}

	/** @return how many mobs the bot has killed while simulated (fractional: kills accrue per resolve) */
	public double getKills()
	{
		return _kills;
	}

	/** @return adena earned from hunting while simulated: adena drops plus the sale value of loot */
	public long getAdenaEarned()
	{
		return _adenaEarned;
	}

	/**
	 * Adds one resolve's hunting gains to the lifetime counters. Negative values are ignored.
	 * @param kills mobs killed
	 * @param adena adena earned (drops plus loot value)
	 */
	public void addHunting(double kills, long adena)
	{
		_kills += Math.max(0.0, kills);
		_adenaEarned = Math.min(Long.MAX_VALUE / 2, _adenaEarned + Math.max(0L, adena));
	}

	/** @return the shot item its stock is of, or 0 */
	public int getShotItem()
	{
		return _shotItem;
	}

	public void setShotItem(int shotItem)
	{
		_shotItem = Math.max(0, shotItem);
	}

	/** @return bits of the shot recipes this bot has learned */
	public int getRecipes()
	{
		return _recipes;
	}

	public void setRecipes(int recipes)
	{
		_recipes = Math.max(0, recipes);
	}

	/** @return the recipe items of the gear this bot has learned to craft */
	public int getGearStreak()
	{
		return _gearStreak;
	}

	public int getGearCap()
	{
		return _gearCap;
	}

	public void setGearStreak(int streak, int cap)
	{
		_gearStreak = Math.max(0, streak);
		_gearCap = Math.max(0, cap);
	}

	public long getGearRestUntil()
	{
		return _gearRestUntil;
	}

	public void setGearRestUntil(long gearRestUntil)
	{
		_gearRestUntil = Math.max(0L, gearRestUntil);
	}

	public Set<Integer> getGearRecipes()
	{
		return _gearRecipes;
	}

	/** @param recipeItemId a gear recipe item this bot has learned */
	public synchronized void addGearRecipe(int recipeItemId)
	{
		final Set<Integer> next = new java.util.TreeSet<>(_gearRecipes);
		next.add(recipeItemId);
		_gearRecipes = Set.copyOf(next);
	}

	/** @return the shot item a Merchant carries to its shop, or 0 */
	public int getCarryItem()
	{
		return _carryItem;
	}

	/** @return how many shots it carries */
	public long getCarryCount()
	{
		return _carryCount;
	}

	/** @return the town it puts them on sale in */
	public String getCarryTown()
	{
		return _carryTown;
	}

	/**
	 * @param item the shot item
	 * @param count how many (0 for nothing)
	 * @param town where they go on sale
	 */
	public void setCarry(int item, long count, String town)
	{
		_carryItem = (count > 0L) ? item : 0;
		_carryCount = Math.max(0L, count);
		_carryTown = (count > 0L) ? town : null;
	}

	/** @return when it last finished its errands in town, or 0 */
	public long getShoppedAt()
	{
		return _shoppedAt;
	}

	public void setShoppedAt(long shoppedAt)
	{
		_shoppedAt = Math.max(0L, shoppedAt);
	}

	/**
	 * @return the {@code stats_json} column value: the bot's decision log, plus its hunting counters once it has any
	 *         ({@code {"decisions":[...],"counters":{"kills":..,"adena":..}}})
	 */
	public String getStatsJson()
	{
		final String decisions = _decisions.toJson();
		if ((_kills <= 0.0) && (_adenaEarned <= 0L) && (_shoppedAt <= 0L) && (_recipes == 0) && _gearRecipes.isEmpty() && (_gearRestUntil <= 0L) && (_gearStreak <= 0) && (_carryCount <= 0L) && (_shotItem == 0))
		{
			return decisions;
		}
		return decisions.substring(0, decisions.length() - 1) + ",\"counters\":{\"kills\":" + String.format(Locale.US, "%.1f", _kills) + ",\"adena\":" + _adenaEarned + ((_shoppedAt > 0L) ? (",\"shoppedAt\":" + _shoppedAt) : "") + ((_recipes != 0) ? (",\"recipes\":" + _recipes) : "") + (_gearRecipes.isEmpty() ? "" : (",\"gearRecipes\":" + new java.util.TreeSet<>(_gearRecipes))) + ((_gearRestUntil > 0L) ? (",\"gearRest\":" + _gearRestUntil) : "") + ((_gearStreak > 0) ? (",\"gearStreak\":" + _gearStreak + ",\"gearCap\":" + _gearCap) : "") + ((_shotItem != 0) ? (",\"shotItem\":" + _shotItem) : "") + ((_carryCount > 0L) ? (",\"carryItem\":" + _carryItem + ",\"carryCount\":" + _carryCount + ",\"carryTown\":\"" + _carryTown + "\"") : "") + "}}";
	}

	/**
	 * Loads the {@code stats_json} column value. Unreadable content is ignored so it never blocks loading the bot.
	 * @param statsJson the stored value
	 */
	public void setStatsJson(String statsJson)
	{
		_decisions.loadJson(statsJson);
		_kills = 0.0;
		_adenaEarned = 0L;
		_shoppedAt = 0L;
		_recipes = 0;
		_gearRecipes = Set.of();
		_gearRestUntil = 0L;
		_gearStreak = 0;
		_gearCap = 0;
		_shotItem = 0;
		setCarry(0, 0L, null);
		if ((statsJson == null) || statsJson.isBlank())
		{
			return;
		}
		try
		{
			if ((Json.parse(statsJson) instanceof Map<?, ?> root) && (root.get("counters") instanceof Map<?, ?> counters))
			{
				if (counters.get("kills") instanceof Number kills)
				{
					_kills = Math.max(0.0, kills.doubleValue());
				}
				// An older row may still carry an "exp" counter; it is no longer kept and drops on the next write.
				if (counters.get("adena") instanceof Number adena)
				{
					_adenaEarned = Math.max(0L, adena.longValue());
				}
				if (counters.get("shoppedAt") instanceof Number shoppedAt)
				{
					_shoppedAt = Math.max(0L, shoppedAt.longValue());
				}
				if (counters.get("shotItem") instanceof Number shotItem)
				{
					_shotItem = Math.max(0, shotItem.intValue());
				}
				if (counters.get("recipes") instanceof Number recipes)
				{
					_recipes = Math.max(0, recipes.intValue());
				}
				if ((counters.get("gearStreak") instanceof Number streak) && (counters.get("gearCap") instanceof Number cap))
				{
					setGearStreak(streak.intValue(), cap.intValue());
				}
				if (counters.get("gearRest") instanceof Number rest)
				{
					_gearRestUntil = Math.max(0L, rest.longValue());
				}
				if (counters.get("gearRecipes") instanceof java.util.List<?> list)
				{
					final Set<Integer> learned = new java.util.TreeSet<>();
					for (Object entry : list)
					{
						if (entry instanceof Number number)
						{
							learned.add(number.intValue());
						}
					}
					_gearRecipes = Set.copyOf(learned);
				}
				if ((counters.get("carryItem") instanceof Number item) && (counters.get("carryCount") instanceof Number count) && (counters.get("carryTown") instanceof String town))
				{
					setCarry(item.intValue(), count.longValue(), town);
				}
			}
		}
		catch (Json.JsonException e)
		{
			// unreadable counters start again from zero; the bot itself still loads
		}
	}
}

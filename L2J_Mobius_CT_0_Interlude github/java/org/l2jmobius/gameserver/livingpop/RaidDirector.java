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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import org.l2jmobius.gameserver.livingpop.ZoneCatalog.Point;
import org.l2jmobius.gameserver.livingpop.ZoneCatalog.Town;

/**
 * Organizes the cold bots into raid parties that kill raid bosses. Whenever a boss is up, it looks for a team among the bots
 * that are hunting and free to go; if a team can kill the boss ({@link RaidPlanner}) it takes them off hunting, they fight,
 * and the loot is split as evenly as whole items allow into the town stock under the members' own ids. If no team can kill it,
 * the boss waits and is looked at again. The bots are cold for the whole raid. Not covered by the standalone tests (it uses
 * game types); the planner and the loot split it relies on are.
 */
public final class RaidDirector
{
	/** Tuning, from the module settings. */
	public record Config(int minLevel, int maxLevel, int levelsBelow, int levelsAbove, long checkMs, long gatherMs, long retryMs, RaidPlanner.Params planner)
	{
	}

	/** A raid boss as the director knows it. */
	public record BossInfo(int id, String name, int level, double hp, double pDef, double mDef, double pAtk, double mAtk, int atkSpeed, Point at, long delayMs, long randomMs, List<RaidLoot.Group> loot, double exp, double sp)
	{
	}

	/** What the director needs from the manager. */
	public interface Host
	{
		/** @return the bot as a team member against this boss, or null when it cannot go (not free, not strong enough to count) */
		RaidPlanner.Member member(ColdBot bot, BossInfo boss, ColdLife.Context life);

		/** @return what an item is worth, for splitting the loot */
		long value(int itemId);

		/** Puts items into a town's stock under a bot. */
		void stock(String town, long botId, int itemId, long count);

		/** Adds experience (and SP) to a bot, with the level-ups. */
		void experience(ColdBot bot, long exp, long sp, List<DecisionLog.Event> events);
	}

	private static final double[] PARTY_EXP = { 1.0, 1.10, 1.20, 1.30, 1.40, 1.50, 2.0, 2.10, 2.20 };

	private record Raid(BossInfo boss, List<ColdBot> members, RaidPlanner.Team team, long endAt, String town)
	{
	}

	private final Random _random;
	private final Config _config;
	private final Map<Integer, Long> _respawnAt = new HashMap<>(); // boss -> when it is up again (0 = up)
	private final Map<Integer, Long> _retryAt = new HashMap<>(); // boss -> not before (after a wipe)
	private final List<Raid> _raids = new ArrayList<>();
	private List<BossInfo> _bosses = List.of();
	private long _nextCheck;
	private volatile boolean _dirty;

	public RaidDirector(Random random, Config config)
	{
		_random = random;
		_config = config;
	}

	/** @param bosses the raid bosses this server has, in the level range (set once the game data is loaded) */
	public synchronized void setBosses(List<BossInfo> bosses)
	{
		_bosses = List.copyOf(bosses);
	}

	public synchronized int bossCount()
	{
		return _bosses.size();
	}

	/** @return whether any raid is under way (the bots in it are held) */
	public synchronized boolean busy(long botId)
	{
		for (Raid raid : _raids)
		{
			for (ColdBot member : raid.members())
			{
				if (member.getId() == botId)
				{
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * Frees bots whose saved activity says they were raiding when the server stopped: raids are not saved, so they go back to hunting.
	 * @param bots all bots
	 */
	public synchronized void recover(List<ColdBot> bots)
	{
		for (ColdBot bot : bots)
		{
			if (ColdLife.RAIDING.equals(bot.getActivity()) && !busy(bot.getId()))
			{
				bot.setLeg(null);
				bot.setActivity(ColdLife.HUNTING);
			}
		}
	}

	/**
	 * One step: ends the raids that are over, then looks for teams for the bosses that are up.
	 * @param now now
	 * @param bots all bots
	 * @param life the shared context
	 * @param host the manager
	 */
	public synchronized void tick(long now, List<ColdBot> bots, ColdLife.Context life, Host host)
	{
		for (Raid raid : new ArrayList<>(_raids))
		{
			if (now >= raid.endAt())
			{
				_raids.remove(raid);
				finish(raid, now, life, host);
			}
		}
		if ((now < _nextCheck) || _bosses.isEmpty())
		{
			return;
		}
		_nextCheck = now + _config.checkMs();
		final Set<Long> taken = new HashSet<>();
		for (Raid raid : _raids)
		{
			for (ColdBot member : raid.members())
			{
				taken.add(member.getId());
			}
		}
		final List<BossInfo> bosses = new ArrayList<>(_bosses);
		bosses.sort((x, y) -> Integer.compare(y.level(), x.level())); // the best loot first
		for (BossInfo boss : bosses)
		{
			if ((_respawnAt.getOrDefault(boss.id(), 0L) > now) || (_retryAt.getOrDefault(boss.id(), 0L) > now) || _raids.stream().anyMatch(raid -> raid.boss().id() == boss.id()))
			{
				continue;
			}
			final Map<Long, ColdBot> byId = new HashMap<>();
			final List<RaidPlanner.Member> candidates = new ArrayList<>();
			for (ColdBot bot : bots)
			{
				if (taken.contains(bot.getId()) || bot.isHotLock() || (bot.getLevel() < (boss.level() - _config.levelsBelow())) || (bot.getLevel() > (boss.level() + _config.levelsAbove())))
				{
					continue;
				}
				final RaidPlanner.Member member = host.member(bot, boss, life);
				if (member != null)
				{
					candidates.add(member);
					byId.put(bot.getId(), bot);
				}
			}
			final RaidPlanner.Team team = RaidPlanner.assemble(new RaidPlanner.Boss(boss.id(), boss.hp(), boss.pAtk(), Math.max(0.1, boss.atkSpeed() / 500.0)), candidates, _config.planner());
			if (team == null)
			{
				continue; // the boss waits
			}
			start(boss, team, byId, now, life, taken);
		}
	}

	private void start(BossInfo boss, RaidPlanner.Team team, Map<Long, ColdBot> byId, long now, ColdLife.Context life, Set<Long> taken)
	{
		final Town town = life.catalog().nearestShoppingTown(boss.at());
		if (town == null)
		{
			return;
		}
		final long travelMs = Math.round(Math.hypot(boss.at().x() - town.arrival().x(), boss.at().y() - town.arrival().y()) * 1.4 / 130.0 * 1000.0);
		final long endAt = now + _config.gatherMs() + travelMs + (long) Math.min(team.killSeconds() * 1000.0, 3_600_000.0);
		final List<ColdBot> members = new ArrayList<>();
		for (RaidPlanner.Member member : team.members())
		{
			final ColdBot bot = byId.get(member.id());
			if (bot == null)
			{
				continue;
			}
			synchronized (bot)
			{
				ColdLife.moveTo(bot, boss.at());
				bot.setZone(null);
				bot.setTown(null);
				bot.setActivity(ColdLife.RAIDING);
				bot.setLeg(TravelLeg.stay(boss.at(), now, Math.max(1L, endAt - now)));
				bot.getDecisions().addTick(now, new ArrayList<>(List.of(new DecisionLog.Event(null, "Joined a raid party of " + team.members().size() + " for " + boss.name() + " (level " + boss.level() + "). Win chance " + Math.round(team.winChance() * 100.0) + "%"))));
			}
			members.add(bot);
			taken.add(bot.getId());
		}
		_raids.add(new Raid(boss, members, team, endAt, town.name()));
	}

	private void finish(Raid raid, long now, ColdLife.Context life, Host host)
	{
		final BossInfo boss = raid.boss();
		final boolean won = _random.nextDouble() < raid.team().winChance();
		final Town town = life.catalog().town(raid.town());
		final List<DecisionLog.Event> shared = new ArrayList<>();
		final int size = raid.members().size();
		List<List<RaidLoot.Drop>> loot = null;
		if (won)
		{
			final List<RaidLoot.Drop> drops = RaidLoot.roll(boss.loot(), org.l2jmobius.gameserver.config.RatesConfig.RATE_RAID_DROP_CHANCE_MULTIPLIER, org.l2jmobius.gameserver.config.RatesConfig.RATE_RAID_DROP_AMOUNT_MULTIPLIER, _random);
			loot = RaidLoot.share(drops, size, DropYield.ADENA, drop -> host.value(drop.itemId()) * drop.count(), _random.nextInt(Math.max(1, size)));
			_respawnAt.put(boss.id(), now + Math.max(60_000L, boss.delayMs() - boss.randomMs() + (long) (_random.nextDouble() * 2.0 * boss.randomMs())));
			_dirty = true;
		}
		else
		{
			_retryAt.put(boss.id(), now + _config.retryMs());
		}
		final double deathChance = won ? Math.max(0.03, Math.min(0.4, 0.35 * (1.6 - raid.team().ratio()))) : 1.0;
		final double expEach = won ? (boss.exp() * Math.max(0.01, org.l2jmobius.gameserver.config.RatesConfig.RATE_XP) * PARTY_EXP[Math.min(size, PARTY_EXP.length) - 1] / size) : 0.0;
		final double spEach = won ? (boss.sp() * Math.max(0.01, org.l2jmobius.gameserver.config.RatesConfig.RATE_SP) * PARTY_EXP[Math.min(size, PARTY_EXP.length) - 1] / size) : 0.0;
		for (int i = 0; i < size; i++)
		{
			final ColdBot bot = raid.members().get(i);
			synchronized (bot)
			{
				final List<DecisionLog.Event> events = new ArrayList<>(shared);
				final Point back = (town == null) ? ColdLife.point(bot) : town.arrival();
				bot.setEscapes(Math.max(0L, bot.getEscapes() - 1L));
				if (town != null)
				{
					ColdLife.moveTo(bot, back);
					bot.setTown(town.name());
				}
				if (won)
				{
					for (RaidLoot.Drop drop : loot.get(i))
					{
						if (drop.itemId() == DropYield.ADENA)
						{
							bot.setAdena(Math.min(ColdEconomy.MAX_ADENA, bot.getAdena() + drop.count()));
						}
						else if (town != null)
						{
							host.stock(town.name(), bot.getId(), drop.itemId(), drop.count());
						}
					}
					host.experience(bot, Math.round(expEach), Math.round(spEach), events);
					events.add(new DecisionLog.Event(null, "Killed " + boss.name() + " with a party of " + size + ". Its share: " + loot.get(i).size() + " drops"));
				}
				else
				{
					events.add(new DecisionLog.Event(null, "The party wiped on " + boss.name()));
				}
				if ((town != null) && (_random.nextDouble() < deathChance))
				{
					ColdLife.diedInRaid(bot, town, boss.name(), now, life, events);
				}
				else
				{
					bot.setActivity(ColdLife.TO_TOWN);
					bot.setLeg(TravelLeg.stay(back, now, life.travel().escapeCastMs()));
				}
				bot.getDecisions().addTick(now, events);
			}
		}
	}

	/** @return the boss timers as text: one {@code boss|respawnAt} line each, the ones still in the future */
	public synchronized String encode(long now)
	{
		final StringBuilder sb = new StringBuilder();
		for (Map.Entry<Integer, Long> entry : _respawnAt.entrySet())
		{
			if (entry.getValue() > now)
			{
				sb.append(entry.getKey()).append('|').append(entry.getValue()).append('\n');
			}
		}
		_dirty = false;
		return sb.toString();
	}

	/** Reads the timers {@link #encode} wrote; lines that cannot be read are skipped. */
	public synchronized void decode(String text)
	{
		_respawnAt.clear();
		if (text == null)
		{
			return;
		}
		for (String line : text.split("\n"))
		{
			try
			{
				final String[] part = line.trim().split("\\|");
				_respawnAt.put(Integer.parseInt(part[0]), Long.parseLong(part[1]));
			}
			catch (RuntimeException e)
			{
				// skip the line
			}
		}
	}

	public boolean dirty()
	{
		return _dirty;
	}
}

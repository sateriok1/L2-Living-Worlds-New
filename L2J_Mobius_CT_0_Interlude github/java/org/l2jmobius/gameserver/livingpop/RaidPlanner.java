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
import java.util.Comparator;
import java.util.List;

/**
 * Whether a team of cold bots can kill a raid boss, and the smallest team that can. A rough fight model, in the spirit of the
 * zone combat model: the team's damage against the boss's HP gives the length of the fight; the tank takes the boss's hits
 * and the healer heals, and the team wins when the tank outlasts the fight. Pure (no game types): the manager works out each
 * member's damage, HP and healing from the combat model and passes them in, so the standalone test lane covers it.
 */
public final class RaidPlanner
{
	/** The seat a member takes in the team. */
	public enum Seat
	{
		TANK, HEALER, BUFFER, DAMAGE
	}

	/**
	 * A raid boss as the fight sees it.
	 * @param id the npc id
	 * @param hp its HP
	 * @param pAtk its physical attack
	 * @param hitsPerSecond its attacks a second
	 */
	public record Boss(int id, double hp, double pAtk, double hitsPerSecond)
	{
	}

	/**
	 * A candidate for the team.
	 * @param id the bot
	 * @param seat the seat its class takes
	 * @param dps its damage a second against the boss, before buffs
	 * @param hp its HP
	 * @param pDef its P.Def
	 * @param healPerSecond the HP a second it heals (healers)
	 * @param buff the damage multiple its buffs give the team (buffers; 1 = none)
	 */
	public record Member(long id, Seat seat, double dps, double hp, double pDef, double healPerSecond, double buff)
	{
	}

	/**
	 * The outcome the model expects.
	 * @param members the team
	 * @param killSeconds how long the fight takes
	 * @param ratio how long the tank lasts over the fight
	 * @param winChance the chance the team wins
	 */
	public record Team(List<Member> members, double killSeconds, double ratio, double winChance)
	{
	}

	/**
	 * Tuning.
	 * @param minSize the smallest raid party
	 * @param maxSize the largest
	 * @param minWinChance the win chance a team needs before it goes
	 * @param hitScale the damage formula's constant (70 x P.Atk over P.Def)
	 * @param burst how much harder the boss hits than its plain attacks (skills)
	 * @param buffCap the most the team's buffers can multiply the damage
	 * @param healSeconds how long a healer can heal at full strength before its mana is spent, after which it heals a quarter as much
	 * @param maxFightSeconds the longest fight the team keeps up (the boss's skills and the party's mana give out)
	 */
	public record Params(int minSize, int maxSize, double minWinChance, double hitScale, double burst, double buffCap, double healSeconds, double maxFightSeconds)
	{
		public static Params defaults()
		{
			return new Params(6, 9, 0.8, 70.0, 1.5, 1.8, 240.0, 1800.0);
		}
	}

	private RaidPlanner()
	{
	}

	/**
	 * Works out a fixed team's fight.
	 * @param boss the boss
	 * @param team the members (a tank, a healer, optional buffers and damage dealers)
	 * @param params the tuning
	 * @return the outcome; a team without a tank or without damage cannot win
	 */
	public static Team fight(Boss boss, List<Member> team, Params params)
	{
		Member tank = null;
		double heal = 0.0;
		double buff = 1.0;
		double dps = 0.0;
		for (Member member : team)
		{
			dps += member.dps();
			if (member.seat() == Seat.TANK)
			{
				if ((tank == null) || (member.hp() * member.pDef() > tank.hp() * tank.pDef()))
				{
					tank = member;
				}
			}
			else if (member.seat() == Seat.HEALER)
			{
				heal += member.healPerSecond();
			}
			else if (member.seat() == Seat.BUFFER)
			{
				buff += Math.max(0.0, member.buff() - 1.0);
			}
		}
		if ((tank == null) || (dps <= 0.0))
		{
			return new Team(List.copyOf(team), Double.POSITIVE_INFINITY, 0.0, 0.0);
		}
		final double kill = boss.hp() / (dps * Math.min(params.buffCap(), buff));
		final double incoming = boss.hitsPerSecond() * params.hitScale() * boss.pAtk() / Math.max(1.0, tank.pDef()) * params.burst();
		final double full = Math.min(1.0, params.healSeconds() / kill);
		final double net = Math.max(0.0, incoming - (heal * (full + (0.25 * (1.0 - full)))));
		final double lasts = (net <= 0.0) ? Double.POSITIVE_INFINITY : (tank.hp() / net);
		final double ratio = lasts / kill;
		final double win = (kill > params.maxFightSeconds()) ? 0.0 : Double.isInfinite(ratio) ? 1.0 : Math.max(0.0, Math.min(1.0, (ratio - 0.6) / 0.8));
		return new Team(List.copyOf(team), kill, ratio, win);
	}

	/**
	 * Builds the smallest team from the candidates that clears the win chance: the sturdiest tank, the best healer, the best
	 * buffer, then damage dealers from the strongest down, growing one member at a time up to the largest team.
	 * @param boss the boss
	 * @param candidates the bots free to go
	 * @param params the tuning
	 * @return the team, or null when no team up to the largest size can win (the boss waits)
	 */
	public static Team assemble(Boss boss, List<Member> candidates, Params params)
	{
		final List<Member> tanks = sorted(candidates, Seat.TANK, Comparator.comparingDouble((Member m) -> m.hp() * m.pDef()).reversed());
		final List<Member> healers = sorted(candidates, Seat.HEALER, Comparator.comparingDouble(Member::healPerSecond).reversed());
		final List<Member> buffers = sorted(candidates, Seat.BUFFER, Comparator.comparingDouble(Member::buff).reversed());
		final List<Member> damage = sorted(candidates, Seat.DAMAGE, Comparator.comparingDouble(Member::dps).reversed());
		if (tanks.isEmpty() || healers.isEmpty())
		{
			return null;
		}
		final List<Member> core = new ArrayList<>(List.of(tanks.get(0), healers.get(0)));
		final List<Member> extras = new ArrayList<>();
		if (!buffers.isEmpty())
		{
			extras.add(buffers.get(0));
		}
		// Damage dealers first, then a second buffer and a second healer if the team has room; the order of the extras is the order they are added in.
		extras.addAll(damage);
		if (buffers.size() > 1)
		{
			extras.add(buffers.get(1));
		}
		if (healers.size() > 1)
		{
			extras.add(healers.get(1));
		}
		for (int size = 2; size <= Math.min(params.maxSize(), core.size() + extras.size()); size++)
		{
			final List<Member> team = new ArrayList<>(core);
			team.addAll(extras.subList(0, size - core.size()));
			if (size < params.minSize())
			{
				continue;
			}
			final Team result = fight(boss, team, params);
			if (result.winChance() >= params.minWinChance())
			{
				return result;
			}
		}
		return null;
	}

	private static List<Member> sorted(List<Member> all, Seat seat, Comparator<Member> order)
	{
		final List<Member> list = new ArrayList<>();
		for (Member member : all)
		{
			if (member.seat() == seat)
			{
				list.add(member);
			}
		}
		list.sort(order);
		return list;
	}
}

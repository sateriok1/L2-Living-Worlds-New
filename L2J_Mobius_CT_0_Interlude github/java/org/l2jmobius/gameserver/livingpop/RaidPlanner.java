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
	 * One kind of attack the boss's side makes besides the boss's own plain hits: a minion's hits, or a boss skill. Its damage is
	 * given as attack power in P.Atk terms (the number the plain hit formula divides by P.Def), so every attack is worked out the same way.
	 * @param power the attack power of one use (a minion's P.Atk; for a boss skill, P.Atk plus the skill's power, or the magic formula's equivalent)
	 * @param perSecond how many uses a second (a minion group counts all its minions)
	 * @param area true when every member is hit, false when it falls on the tank
	 * @param hp 0 for a skill, which the boss keeps using the whole fight; above 0 for minions, the HP of the group: the party kills them first
	 *            (it is added to the damage the party has to do) and the group only hits the tank until it is dead
	 */
	public record Strike(double power, double perSecond, boolean area, double hp)
	{
		/** A skill: used the whole fight. */
		public Strike(double power, double perSecond, boolean area)
		{
			this(power, perSecond, area, 0.0);
		}
	}

	/**
	 * A raid boss as the fight sees it.
	 * @param id the npc id
	 * @param hp its HP
	 * @param pAtk its physical attack
	 * @param hitsPerSecond its attacks a second
	 * @param strikes its minions' hits and its damage skills (see {@link Strike})
	 */
	public record Boss(int id, double hp, double pAtk, double hitsPerSecond, List<Strike> strikes)
	{
		/** A boss with plain hits only. */
		public Boss(int id, double hp, double pAtk, double hitsPerSecond)
		{
			this(id, hp, pAtk, hitsPerSecond, List.of());
		}
	}

	/**
	 * A candidate for the team.
	 * @param id the bot
	 * @param seat the seat its class takes
	 * @param dps its damage a second against the boss with its shots, before buffs
	 * @param hp its HP
	 * @param pDef its P.Def
	 * @param healPerSecond the HP a second it heals (healers)
	 * @param buff the damage multiple its buffs give the team (buffers; 1 = none)
	 * @param plainDps its damage a second once its shots are used up
	 * @param shotSeconds how long its stock of shots lasts in a fight (infinite when it needs none)
	 * @param potionHps the HP a second it gives itself drinking potions
	 * @param potionSeconds how long its stock of potions lasts in a fight
	 */
	public record Member(long id, Seat seat, double dps, double hp, double pDef, double healPerSecond, double buff, double plainDps, double shotSeconds, double potionHps, double potionSeconds)
	{
		/** A member with shots and no potions to count (always shot). */
		public Member(long id, Seat seat, double dps, double hp, double pDef, double healPerSecond, double buff)
		{
			this(id, seat, dps, hp, pDef, healPerSecond, buff, dps, Double.POSITIVE_INFINITY, 0.0, 0.0);
		}

		/** @return the damage it does in this many seconds: with shots while they last, then without */
		double damageIn(double seconds)
		{
			final double shot = Math.min(seconds, shotSeconds);
			return (dps * shot) + (plainDps * Math.max(0.0, seconds - shot));
		}

		/** @return the share of a fight of this length that its potions cover */
		double potionShare(double fightSeconds)
		{
			return (potionHps <= 0.0) ? 0.0 : Math.max(0.0, Math.min(1.0, potionSeconds / fightSeconds));
		}
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
	 * @param burst a multiple on all the damage the party takes (1 = as modeled; the boss's skills and minions are in the model already)
	 * @param buffCap the most the team's buffers can multiply the damage
	 * @param healSeconds how long a healer can heal at full strength before its mana is spent, after which it heals a quarter as much
	 * @param maxFightSeconds the longest fight the team keeps up (the boss's skills and the party's mana give out)
	 * @param skillSeconds the seconds between two uses of one boss skill (at least; a skill with a longer reuse waits that long)
	 */
	public record Params(int minSize, int maxSize, double minWinChance, double hitScale, double burst, double buffCap, double healSeconds, double maxFightSeconds, double skillSeconds)
	{
		public static Params defaults()
		{
			return new Params(6, 9, 0.8, 70.0, 1.0, 1.8, 240.0, 1800.0, 15.0);
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
		double healing = 0.0;
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
				healing += member.healPerSecond();
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
		final double damageMultiple = Math.min(params.buffCap(), buff);
		// What hits the party, in attack power a second: the tank takes the boss's plain hits and its single-target skills, and every member
		// takes the area skills. Minions are held by the tank and killed first: their HP adds to the work, and they hit only until they are dead
		// (on average half of that time, as they fall one by one). Skill effects (holds, stuns) and the minions' skills are not modeled.
		double tankPower = boss.hitsPerSecond() * boss.pAtk();
		double areaPower = 0.0;
		double minionPower = 0.0;
		double minionHp = 0.0;
		for (Strike strike : boss.strikes())
		{
			if (strike.hp() > 0.0)
			{
				minionPower += strike.power() * strike.perSecond();
				minionHp += strike.hp();
			}
			else if (strike.area())
			{
				areaPower += strike.power() * strike.perSecond();
			}
			else
			{
				tankPower += strike.power() * strike.perSecond();
			}
		}
		// Shots run out: a member does its shot damage while its stock lasts and its plain damage after, so the fight length is found from the damage done by then.
		final double kill = timeFor(boss.hp() + minionHp, team, damageMultiple);
		if (Double.isInfinite(kill))
		{
			return new Team(List.copyOf(team), Double.POSITIVE_INFINITY, 0.0, 0.0);
		}
		final double clear = (minionHp > 0.0) ? timeFor(minionHp, team, damageMultiple) : 0.0;
		final double scale = params.hitScale() * params.burst();
		final double tankLoad = scale * (tankPower + areaPower) / Math.max(1.0, tank.pDef());
		final double minionLoad = 0.5 * scale * minionPower / Math.max(1.0, tank.pDef());
		double othersLoad = 0.0;
		double othersHp = 0.0;
		double othersPotions = 0.0;
		for (Member member : team)
		{
			if (member != tank)
			{
				othersLoad += scale * areaPower / Math.max(1.0, member.pDef());
				othersHp += member.hp();
				othersPotions += member.potionHps() * member.potionShare(kill);
			}
		}
		final double tankPotions = tank.potionHps() * tank.potionShare(kill); // a member drinks only while its potions last
		final double full = Math.min(1.0, params.healSeconds() / kill);
		final double heal = healing * (full + (0.25 * (1.0 - full)));
		// The healers heal the tank first, then whoever else the area skills hurt (a perfect healer: optimistic).
		final double tankNet = Math.max(0.0, tankLoad - heal - tankPotions);
		final double tankNetEarly = Math.max(0.0, tankLoad + minionLoad - heal - tankPotions); // while the minions are up
		final double othersNet = Math.max(0.0, othersLoad - Math.max(0.0, heal - Math.max(0.0, tankLoad - tankPotions)) - othersPotions);
		final double tankLasts;
		if (tankNetEarly * clear >= tank.hp())
		{
			tankLasts = tank.hp() / tankNetEarly;
		}
		else
		{
			final double left = tank.hp() - (tankNetEarly * clear);
			tankLasts = (tankNet <= 0.0) ? Double.POSITIVE_INFINITY : (clear + (left / tankNet));
		}
		final double othersLast = (othersNet <= 0.0) ? Double.POSITIVE_INFINITY : (othersHp / othersNet);
		final double lasts = Math.min(tankLasts, othersLast);
		final double ratio = lasts / kill;
		final double win = (kill > params.maxFightSeconds()) ? 0.0 : Double.isInfinite(ratio) ? 1.0 : Math.max(0.0, Math.min(1.0, (ratio - 0.6) / 0.8));
		return new Team(List.copyOf(team), kill, ratio, win);
	}

	/** @return the seconds the team needs to do this much damage (infinite when it never gets there) */
	private static double timeFor(double work, List<Member> team, double multiple)
	{
		double hi = 1.0;
		while (done(team, hi, multiple) < work)
		{
			hi *= 2.0;
			if (hi > 1.0e7)
			{
				return Double.POSITIVE_INFINITY;
			}
		}
		double lo = 0.0;
		for (int i = 0; i < 60; i++)
		{
			final double mid = (lo + hi) / 2.0;
			if (done(team, mid, multiple) >= work)
			{
				hi = mid;
			}
			else
			{
				lo = mid;
			}
		}
		return hi;
	}

	private static double done(List<Member> team, double seconds, double multiple)
	{
		double total = 0.0;
		for (Member member : team)
		{
			total += member.damageIn(seconds);
		}
		return total * multiple;
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

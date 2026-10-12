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
import java.util.function.Predicate;
import java.util.regex.Pattern;

import org.l2jmobius.gameserver.managers.FakePlayerNameFactory;

/**
 * Pure planner for the cold population: which bots to create (race, starting class and name), not where to place them.
 * The manager resolves each spec's newbie starting location from the stock {@code PlayerTemplateData} at seed time, so a
 * bot is born at level 1 at its race and class's starting point exactly like a real new player.
 *
 * <p>New bots spread over the nine starting classes: each one takes the class the population has least of (fighters
 * first on a tie, so an empty population starts with one fighter per race, the old verification set). Names come from
 * the same syllable generator as town bots and phantoms, and never repeat a name already taken.
 */
public final class ColdSeedPlan
{
	/** The nine starting classes, fighters first: {race label, base class id}. */
	private static final Object[][] BASE_CLASSES =
	{
		{
			"Human",
			0
		},
		{
			"Elf",
			18
		},
		{
			"DarkElf",
			31
		},
		{
			"Orc",
			44
		},
		{
			"Dwarf",
			53
		},
		{
			"Human",
			10
		},
		{
			"Elf",
			25
		},
		{
			"DarkElf",
			38
		},
		{
			"Orc",
			49
		},
	};

	/** How the first seeds were named (Human000, Elf001 ...); such a bot gets a real name on its next start. */
	private static final Pattern PLACEHOLDER = Pattern.compile("(Human|Elf|DarkElf|Orc|Dwarf)\\d{3}");

	private static final int NAME_ATTEMPTS = 200;

	/** The owner's name: a human Fighter who always becomes a Duelist, born with the first wave. */
	public static final String OWNER = "aiSateriok";
	/** A friend's name: a male bot, half the time a human Mystic who becomes a Bishop and half an Orc Fighter who becomes a Titan. */
	public static final String ZION = "aiSonOfZion";
	/** A friend's name: a bot of a random race and class, born with the first wave. */
	public static final String FRIEND = "aiTeras";

	/**
	 * @param name a bot's name
	 * @return the custom title that bot wears in the world, or null for none
	 */
	public static String titleOf(String name)
	{
		if (OWNER.equalsIgnoreCase(name))
		{
			return "Dev God";
		}
		if (ZION.equalsIgnoreCase(name))
		{
			return "Bot Torturer";
		}
		if (FRIEND.equalsIgnoreCase(name))
		{
			return "Administrator";
		}
		return ShotCrafting.MERCHANT.equalsIgnoreCase(name) ? ShotCrafting.MERCHANT_TITLE : null;
	}

	private ColdSeedPlan()
	{
	}

	/**
	 * @param name a bot's name
	 * @return the custom title the named bots carry in the world, or null
	 */
	public static String title(String name)
	{
		if (ShotCrafting.MERCHANT.equalsIgnoreCase(name))
		{
			return ShotCrafting.MERCHANT_TITLE;
		}
		if (OWNER.equalsIgnoreCase(name))
		{
			return "Dev God";
		}
		if (ZION.equalsIgnoreCase(name))
		{
			return "Bot Torturer";
		}
		return FRIEND.equalsIgnoreCase(name) ? "The Admin" : null;
	}

	/** How much more experience the three named bots earn in the cold simulation (30%), until they are within a level of the top. */
	public static final double NAMED_EXP_BONUS = 1.3;

	/**
	 * The experience multiplier of a cold bot: the three named bots (the owner, aiSonOfZion and aiTeras) earn 30% more
	 * until they come within one level of the highest-level bot, so they stay competitive without being pushed to the top.
	 * @param name the bot's name
	 * @param level its level
	 * @param topLevel the highest level of any bot
	 * @return 1.3 or 1.0
	 */
	public static double expBonus(String name, int level, int topLevel)
	{
		final boolean named = OWNER.equalsIgnoreCase(name) || ZION.equalsIgnoreCase(name) || FRIEND.equalsIgnoreCase(name);
		return (named && ((topLevel - level) > 1)) ? NAMED_EXP_BONUS : 1.0;
	}

	/**
	 * @param random the source
	 * @return aiSonOfZion, a human Mystic or an Orc Fighter by a coin toss
	 */
	public static SeedSpec zion(Random random)
	{
		return random.nextBoolean() ? new SeedSpec("Human", 10, ZION) : new SeedSpec("Orc", 44, ZION);
	}

	/**
	 * @param name the name to give
	 * @param random the source
	 * @return a new bot of that name in a random starting class
	 */
	public static SeedSpec randomNamed(String name, Random random)
	{
		final Object[] pick = BASE_CLASSES[random.nextInt(BASE_CLASSES.length)];
		return new SeedSpec((String) pick[0], (int) pick[1], name);
	}

	/**
	 * Plans new bots.
	 * @param count how many to create
	 * @param classCounts how many bots each starting class already has, by base class id (others are ignored; null is
	 *            empty)
	 * @param random the name source
	 * @param taken whether a name is already used (case is the caller's business); names planned here are added on top
	 * @return the specs, in creation order; empty when count is zero or less
	 */
	public static List<SeedSpec> plan(int count, Map<Integer, Integer> classCounts, Random random, Predicate<String> taken)
	{
		final List<SeedSpec> specs = new ArrayList<>();
		final Map<Integer, Integer> counts = new HashMap<>();
		for (Object[] base : BASE_CLASSES)
		{
			final int classId = (int) base[1];
			counts.put(classId, ((classCounts == null) || (classCounts.get(classId) == null)) ? 0 : classCounts.get(classId));
		}
		final Set<String> planned = new HashSet<>();
		for (int index = 0; index < count; index++)
		{
			Object[] pick = BASE_CLASSES[0];
			for (Object[] base : BASE_CLASSES)
			{
				if (counts.get((int) base[1]) < counts.get((int) pick[1]))
				{
					pick = base;
				}
			}
			final int classId = (int) pick[1];
			counts.merge(classId, 1, Integer::sum);
			final String name = newName(random, candidate -> planned.contains(candidate.toLowerCase()) || taken.test(candidate));
			planned.add(name.toLowerCase());
			specs.add(new SeedSpec((String) pick[0], classId, name));
		}
		return specs;
	}

	/**
	 * @param random the name source
	 * @param taken whether a name is already used
	 * @return a pronounceable name (at most 16 characters) that is not taken and does not look like a placeholder
	 */
	public static String newName(Random random, Predicate<String> taken)
	{
		for (int attempt = 0; attempt < NAME_ATTEMPTS; attempt++)
		{
			final String name = FakePlayerNameFactory.generateName(random);
			if (!placeholder(name) && !taken.test(name))
			{
				return name;
			}
		}
		// The syllable pools are nearly used up: add a number, as players do when their name is taken.
		while (true)
		{
			final String base = FakePlayerNameFactory.generateName(random);
			final String name = base.substring(0, Math.min(base.length(), 13)) + (10 + random.nextInt(990));
			if (!taken.test(name))
			{
				return name;
			}
		}
	}

	/**
	 * @param name a bot name
	 * @return whether it is one of the first, numbered names (Human000 and so on)
	 */
	public static boolean placeholder(String name)
	{
		return (name != null) && PLACEHOLDER.matcher(name).matches();
	}

	/**
	 * @param classId a class id
	 * @return the race label of that starting class, or null when it is not one
	 */
	public static String raceOf(int classId)
	{
		for (Object[] base : BASE_CLASSES)
		{
			if ((int) base[1] == classId)
			{
				return (String) base[0];
			}
		}
		return null;
	}

	/**
	 * One planned bot: its race label, base class id, and name. The manager resolves the class id to a newbie starting
	 * location.
	 * @param race the race label
	 * @param classId the base class id
	 * @param name the character name
	 */
	public record SeedSpec(String race, int classId, String name)
	{
	}
}

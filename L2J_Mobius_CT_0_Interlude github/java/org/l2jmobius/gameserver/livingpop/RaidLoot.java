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
import java.util.List;
import java.util.Random;
import java.util.function.ToLongFunction;

/**
 * What a raid boss drops, rolled for real the way the server rolls it: each group fires at its chance and gives one of its
 * items (the items' chances add up to 100 inside a group), each ungrouped entry fires on its own. A raid boss is killed
 * once, so the drops are real items, not expected values. Pure (no game types), so the standalone test lane covers it.
 */
public final class RaidLoot
{
	private RaidLoot()
	{
	}

	/**
	 * One item of a drop list.
	 * @param itemId the item
	 * @param chance the item's chance in percent (inside its group, or alone)
	 * @param min the smallest amount
	 * @param max the largest amount
	 */
	public record Entry(int itemId, double chance, long min, long max)
	{
	}

	/**
	 * A drop group: one roll at the group's chance, and the item is picked by the items' chances.
	 * @param chance the group's chance in percent
	 * @param entries the items
	 */
	public record Group(double chance, List<Entry> entries)
	{
	}

	/**
	 * @param item an item
	 * @param count how many
	 */
	public record Drop(int itemId, long count)
	{
	}

	/**
	 * Rolls a raid boss's death drops.
	 * @param groups the groups; an ungrouped entry is a group of its own with chance 100 and the entry's chance as its item chance of 100
	 * @param chanceMultiplier the server's raid drop chance rate
	 * @param amountMultiplier the server's raid drop amount rate
	 * @param random the dice
	 * @return the items dropped, adena included
	 */
	public static List<Drop> roll(List<Group> groups, double chanceMultiplier, double amountMultiplier, Random random)
	{
		final List<Drop> drops = new ArrayList<>();
		for (Group group : groups)
		{
			if (random.nextDouble() * 100.0 >= Math.min(100.0, group.chance() * chanceMultiplier))
			{
				continue;
			}
			double total = 0.0;
			for (Entry entry : group.entries())
			{
				total += entry.chance();
			}
			double pick = random.nextDouble() * Math.max(100.0, total); // chances that add up to less than 100 leave a share that drops nothing
			for (Entry entry : group.entries())
			{
				pick -= entry.chance();
				if (pick < 0.0)
				{
					final long span = Math.max(0L, entry.max() - entry.min());
					final long count = Math.round((entry.min() + (long) (random.nextDouble() * (span + 1))) * amountMultiplier);
					if (count > 0L)
					{
						drops.add(new Drop(entry.itemId(), count));
					}
					break;
				}
			}
		}
		return drops;
	}

	/**
	 * Splits the drops as evenly as whole items allow, as cooperative players would: adena equally, then the items from the
	 * most valuable down, each to the member who holds the least value so far. Not perfect (a lone weapon goes to one
	 * member), but close, and the same bot is not first in line every time (ties go to the member after {@code start}).
	 * @param drops the rolled drops
	 * @param members how many members
	 * @param adenaId the adena item id
	 * @param value what a drop is worth (its price times its count)
	 * @param start the member who wins ties
	 * @return per member, the drops they get (the list at index i is member i's)
	 */
	public static List<List<Drop>> share(List<Drop> drops, int members, int adenaId, ToLongFunction<Drop> value, int start)
	{
		final List<List<Drop>> result = new ArrayList<>();
		final long[] held = new long[Math.max(0, members)];
		for (int i = 0; i < members; i++)
		{
			result.add(new ArrayList<>());
		}
		if (members <= 0)
		{
			return result;
		}
		final List<Drop> items = new ArrayList<>();
		for (Drop drop : drops)
		{
			if (drop.itemId() == adenaId)
			{
				final long each = drop.count() / members;
				final long rest = drop.count() % members;
				for (int i = 0; i < members; i++)
				{
					final long mine = each + (Math.floorMod(i - start, members) < rest ? 1L : 0L);
					if (mine > 0L)
					{
						result.get(i).add(new Drop(adenaId, mine));
						held[i] += mine;
					}
				}
			}
			else
			{
				items.add(drop);
			}
		}
		items.sort((x, y) -> Long.compare(value.applyAsLong(y), value.applyAsLong(x)));
		for (Drop drop : items)
		{
			int best = Math.floorMod(start, members);
			for (int step = 1; step < members; step++)
			{
				final int i = Math.floorMod(start + step, members);
				if (held[i] < held[best])
				{
					best = i;
				}
			}
			result.get(best).add(drop);
			held[best] += Math.max(0L, value.applyAsLong(drop));
		}
		return result;
	}
}

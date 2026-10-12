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
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * What the hunters of each town fire, so a Merchant can size its production. A town records, for every shot item, how
 * many shots its hunters fired and how many more they would have fired had they had them (a bot with an empty stock keeps
 * hunting without shots), in hourly buckets. The rates are averages over the last few hours. Pure (no game types), so the
 * standalone test lane covers it. Kept in memory: it starts empty after a restart and is read as rates once an hour has
 * passed, never as totals.
 */
public final class ShotDemand
{
	private static final long HOUR_MS = 3_600_000L;
	private static final int KEEP_HOURS = 48;
	private static final double MIN_HOURS = 1.0 / 60.0; // never divide by less than a minute

	/**
	 * Shots per hour for one town and one shot item.
	 * @param fired shots the hunters used
	 * @param wanted shots the hunters needed (used and missed for want of stock)
	 */
	public record Rate(double fired, double wanted)
	{
		/** @return shots per hour hunters would have used but could not */
		public double missed()
		{
			return Math.max(0.0, wanted - fired);
		}
	}

	private record Key(String town, int itemId)
	{
	}

	private final Map<Key, TreeMap<Long, long[]>> _buckets = new HashMap<>(); // hour index -> {fired, wanted}
	private long _since = Long.MAX_VALUE; // when it first recorded anything

	/**
	 * Adds a span of hunting.
	 * @param town the town the bot hunts from (ignored when null)
	 * @param itemId the shot item it fires
	 * @param fired shots it fired
	 * @param wanted shots it needed, at least {@code fired}
	 * @param nowMs the end of the span
	 */
	public synchronized void record(String town, int itemId, long fired, long wanted, long nowMs)
	{
		if ((town == null) || (wanted <= 0))
		{
			return;
		}
		_since = Math.min(_since, nowMs);
		final TreeMap<Long, long[]> hours = _buckets.computeIfAbsent(new Key(town, itemId), key -> new TreeMap<>());
		final long[] bucket = hours.computeIfAbsent(Math.floorDiv(nowMs, HOUR_MS), hour -> new long[2]);
		bucket[0] += Math.max(0L, Math.min(fired, wanted));
		bucket[1] += wanted;
		while (!hours.isEmpty() && (hours.firstKey() < (Math.floorDiv(nowMs, HOUR_MS) - KEEP_HOURS)))
		{
			hours.pollFirstEntry();
		}
	}

	/**
	 * @param town a town
	 * @param itemId a shot item
	 * @param hours how many hours to average over (the current hour counts for the part that has passed)
	 * @param nowMs now
	 * @return the average shots per hour; an average over less time than {@code hours} when the tracker is younger than that
	 */
	public synchronized Rate perHour(String town, int itemId, int hours, long nowMs)
	{
		final TreeMap<Long, long[]> buckets = _buckets.get(new Key(town, itemId));
		if ((buckets == null) || (hours <= 0))
		{
			return new Rate(0.0, 0.0);
		}
		final long current = Math.floorDiv(nowMs, HOUR_MS);
		long fired = 0L;
		long wanted = 0L;
		for (long[] bucket : buckets.subMap(current - hours + 1, true, current, true).values())
		{
			fired += bucket[0];
			wanted += bucket[1];
		}
		final double window = (hours - 1) + ((nowMs - (current * HOUR_MS)) / (double) HOUR_MS);
		final double observed = Math.max(MIN_HOURS, (nowMs - _since) / (double) HOUR_MS);
		final double span = Math.max(MIN_HOURS, Math.min(window, observed));
		return new Rate(fired / span, wanted / span);
	}

	/**
	 * @param town a town
	 * @param hours how many hours to average over
	 * @param nowMs now
	 * @return shot item id to its average shots per hour in that town (items with nothing recorded are left out)
	 */
	public synchronized Map<Integer, Rate> town(String town, int hours, long nowMs)
	{
		final Map<Integer, Rate> rates = new HashMap<>();
		for (Key key : new ArrayList<>(_buckets.keySet()))
		{
			if (key.town().equals(town))
			{
				final Rate rate = perHour(town, key.itemId(), hours, nowMs);
				if (rate.wanted() > 0.0)
				{
					rates.put(key.itemId(), rate);
				}
			}
		}
		return rates;
	}

	/** @return the towns that have recorded anything */
	public synchronized List<String> towns()
	{
		final List<String> towns = new ArrayList<>();
		for (Key key : _buckets.keySet())
		{
			if (!towns.contains(key.town()))
			{
				towns.add(key.town());
			}
		}
		towns.sort(null);
		return towns;
	}
}

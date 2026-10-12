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
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Gear bought the way a player weighs it: by what a piece earns back. Every candidate piece is put on a copy of the gear the
 * bot wears and the whole set is valued (what the zone model says that gear earns an hour), so a weapon that lifts the kill
 * rate pays back fast, armor pays back by the deaths it prevents, and a ring that changes nothing the fights notice never
 * pays back and is not bought. The bot buys in order of payback (what the piece costs after the old one is sold, divided by
 * the hourly gain) while it has the money, and only pieces that pay back inside the horizon. Pure: the valuation is passed
 * in, so it is tested without a server.
 */
public final class GearPlanner
{
	private GearPlanner()
	{
	}

	/** What a set of gear earns an hour (any one currency, as long as it is the same for every call). */
	@FunctionalInterface
	public interface Valuation
	{
		/**
		 * @param gear the gear worn (item per slot)
		 * @return what hunting with it earns an hour
		 */
		double perHour(Map<LivingGear.Slot, Integer> gear);
	}

	/**
	 * A piece weighed.
	 * @param change the purchase (slot, piece, price, gain, what it replaces)
	 * @param gainPerHour what it adds an hour
	 * @param paybackHours hours of hunting for the gain to cover the cost net of the old piece's resale
	 */
	public record Pick(LivingGear.Change change, double gainPerHour, double paybackHours)
	{
	}

	/**
	 * Shops for gear by payback: again and again takes the piece that pays back soonest within the money left, until nothing
	 * affordable pays back inside the horizon. What a piece replaces is taken off (the caller sells it) and counts toward its
	 * cost at its sell price.
	 * @param gear what it wears; updated in place
	 * @param fit what the class wears
	 * @param level the bot's level
	 * @param budget the adena it may spend
	 * @param offers what is for sale
	 * @param items item facts
	 * @param locked slots it does not shop for (bought earlier in the same visit)
	 * @param value what a set of gear earns an hour
	 * @param horizonHours the longest payback it accepts, in hours of hunting
	 * @return what it bought, in order
	 */
	public static List<LivingGear.Change> shop(Map<LivingGear.Slot, Integer> gear, LivingGear.Fit fit, int level, long budget, List<LivingGear.Offer> offers, LivingGear.Items items, Set<LivingGear.Slot> locked, Valuation value, double horizonHours)
	{
		final List<LivingGear.Change> bought = new ArrayList<>();
		final Set<LivingGear.Slot> closed = EnumSet.noneOf(LivingGear.Slot.class);
		closed.addAll(locked);
		long left = Math.max(0L, budget);
		while (true)
		{
			final Pick best = best(gear, fit, level, left, offers, items, closed, value, horizonHours);
			if (best == null)
			{
				return bought;
			}
			final LivingGear.Change change = best.change();
			final List<Integer> removed = LivingGear.put(gear, change.slot(), change.piece(), items);
			left -= change.price();
			closed.add(change.slot());
			bought.add(new LivingGear.Change(change.slot(), change.piece(), change.price(), change.shop(), change.gain(), true, removed));
		}
	}

	/**
	 * What it would save up for: the piece that pays back soonest inside the horizon, whatever it costs. Null when nothing for
	 * sale pays back, so a bot does not save for a piece that would not earn its price.
	 * @return the piece, or null
	 */
	public static Pick wish(Map<LivingGear.Slot, Integer> gear, LivingGear.Fit fit, int level, List<LivingGear.Offer> offers, LivingGear.Items items, Valuation value, double horizonHours)
	{
		return best(gear, fit, level, Long.MAX_VALUE, offers, items, Set.of(), value, horizonHours);
	}

	/** The piece with the shortest payback among those within the money, inside the horizon (null when none). */
	private static Pick best(Map<LivingGear.Slot, Integer> gear, LivingGear.Fit fit, int level, long left, List<LivingGear.Offer> offers, LivingGear.Items items, Set<LivingGear.Slot> closed, Valuation value, double horizonHours)
	{
		final double base = value.perHour(gear);
		Pick best = null;
		for (LivingGear.Slot slot : LivingGear.SHOP_ORDER)
		{
			if (closed.contains(slot))
			{
				continue;
			}
			for (LivingGear.Offer offer : frontier(gear, slot, fit, level, left, offers, items))
			{
				final Map<LivingGear.Slot, Integer> trial = new EnumMap<>(LivingGear.Slot.class);
				trial.putAll(gear);
				final List<Integer> removed = LivingGear.put(trial, slot, offer.piece(), items);
				final double gain = value.perHour(trial) - base;
				if (gain <= 0.0)
				{
					continue;
				}
				long resale = 0L;
				for (int itemId : removed)
				{
					final LivingGear.Piece old = items.piece(itemId);
					resale += (old == null) ? 0L : old.sellPrice();
				}
				final double payback = Math.max(1.0, offer.price() - resale) / gain;
				if (payback > horizonHours)
				{
					continue;
				}
				if ((best == null) || (payback < best.paybackHours()) || ((payback == best.paybackHours()) && (offer.price() < best.change().price())))
				{
					best = new Pick(new LivingGear.Change(slot, offer.piece(), offer.price(), offer.shop(), LivingGear.gain(gear, slot, offer.piece(), fit, items), true, removed), gain, payback);
				}
			}
		}
		return best;
	}

	/**
	 * The offers worth valuing for a slot: those it can wear there, afford and that are an improvement, without those another
	 * offer beats on both price and stat (so the model is asked about a handful of pieces, not every item in the shop).
	 */
	private static List<LivingGear.Offer> frontier(Map<LivingGear.Slot, Integer> gear, LivingGear.Slot slot, LivingGear.Fit fit, int level, long left, List<LivingGear.Offer> offers, LivingGear.Items items)
	{
		final List<LivingGear.Offer> usable = new ArrayList<>();
		for (LivingGear.Offer offer : offers)
		{
			if ((offer.piece().kind() == slot.kind()) && (offer.price() > 0) && (offer.price() <= left) && (LivingGear.target(gear, offer.piece(), fit, level, items) == slot))
			{
				usable.add(offer);
			}
		}
		final List<LivingGear.Offer> kept = new ArrayList<>();
		for (LivingGear.Offer offer : usable)
		{
			final int gain = LivingGear.gain(gear, slot, offer.piece(), fit, items);
			boolean beaten = false;
			for (LivingGear.Offer other : usable)
			{
				if (other == offer)
				{
					continue;
				}
				final int otherGain = LivingGear.gain(gear, slot, other.piece(), fit, items);
				if ((other.price() <= offer.price()) && (otherGain >= gain) && ((other.price() < offer.price()) || (otherGain > gain) || (other.piece().itemId() < offer.piece().itemId())))
				{
					beaten = true;
					break;
				}
			}
			if (!beaten)
			{
				kept.add(offer);
			}
		}
		return kept;
	}
}

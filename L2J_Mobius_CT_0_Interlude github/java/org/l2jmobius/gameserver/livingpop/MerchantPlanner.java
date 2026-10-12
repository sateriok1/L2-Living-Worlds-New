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

import java.util.Map;

/**
 * What a Merchant crafts next, from what its customers want. For every recipe it knows, it compares the shots the
 * hunters want in an hour times the hours of stock it keeps against what its shop already holds, and crafts the biggest
 * gap it can afford. Pure (no game types), so the standalone test lane covers it.
 */
public final class MerchantPlanner
{
	/**
	 * @param recipe what to craft
	 * @param crafts how many crafts
	 * @param shots the shots they make
	 * @param cost the adena the materials cost
	 */
	public record Plan(ShotCrafting.Recipe recipe, long crafts, long shots, long cost)
	{
	}

	private MerchantPlanner()
	{
	}

	/**
	 * @param known bits of the recipes it has learned
	 * @param level its level
	 * @param wantedPerHour shot item id to the shots hunters want an hour
	 * @param onSale shot item id to the shots its shop holds
	 * @param hoursOfStock how many hours of demand a batch should cover
	 * @param maxCrafts the most crafts one trip makes
	 * @param budget the adena it can spend on materials
	 * @param prices the material prices
	 * @return the plan, or null when nothing is worth crafting
	 */
	public static Plan decide(int known, int level, Map<Integer, Double> wantedPerHour, Map<Integer, Long> onSale, double hoursOfStock, long maxCrafts, long budget, ShotCrafting.Prices prices)
	{
		// D grade first: the Merchant covers all D demand before it turns to C.
		final Plan d = decide(known, level, wantedPerHour, onSale, hoursOfStock, maxCrafts, budget, prices, false);
		return (d != null) ? d : decide(known, level, wantedPerHour, onSale, hoursOfStock, maxCrafts, budget, prices, true);
	}

	private static Plan decide(int known, int level, Map<Integer, Double> wantedPerHour, Map<Integer, Long> onSale, double hoursOfStock, long maxCrafts, long budget, ShotCrafting.Prices prices, boolean cGrade)
	{
		Plan best = null;
		double bestGap = 0.0;
		for (ShotCrafting.Recipe recipe : ShotCrafting.RECIPES)
		{
			if ((recipe.cGrade() != cGrade) || ((known & ShotCrafting.bit(recipe)) == 0) || (level < recipe.minLevel()))
			{
				continue;
			}
			final double gap = (wantedPerHour.getOrDefault(recipe.shotItemId(), 0.0) * hoursOfStock) - onSale.getOrDefault(recipe.shotItemId(), 0L);
			if (gap <= 0.0)
			{
				continue;
			}
			final long cost = ShotCrafting.craftCost(recipe, prices);
			final long crafts = Math.min(maxCrafts, Math.min((long) Math.ceil(gap / recipe.perCraft()), (cost > 0L) ? (budget / cost) : 0L));
			final double value = Math.min(gap, crafts * (double) recipe.perCraft()) * ShotCrafting.unitCost(recipe, prices);
			if ((crafts >= 1L) && (value > bestGap))
			{
				bestGap = value;
				best = new Plan(recipe, crafts, crafts * recipe.perCraft(), crafts * cost);
			}
		}
		return best;
	}

	/**
	 * @param known bits of the recipes it has learned
	 * @param level its level
	 * @param wantedPerHour shot item id to the shots hunters want an hour
	 * @return the recipe it should learn next (the unlearned one its level allows that hunters want most), or null
	 */
	public static ShotCrafting.Recipe recipeToLearn(int known, int level, Map<Integer, Double> wantedPerHour)
	{
		ShotCrafting.Recipe best = null;
		double bestWant = 0.0;
		for (ShotCrafting.Recipe recipe : ShotCrafting.RECIPES)
		{
			final double want = wantedPerHour.getOrDefault(recipe.shotItemId(), 0.0);
			if (((known & ShotCrafting.bit(recipe)) == 0) && (level >= recipe.minLevel()) && (want > bestWant))
			{
				bestWant = want;
				best = recipe;
			}
		}
		return best;
	}

	/**
	 * What a shot sells for: the floor price (production cost plus 1) once the shop holds enough shots for the demand,
	 * and more the fewer it holds, up to {@code maxMarkup} above the floor when it holds none. The Merchant keeps prices
	 * high while it is short of shots and lets them fall to the floor once it supplies all of the demand.
	 * @param floor the floor price
	 * @param onSale shots the shop holds
	 * @param demand shots the demand calls for (the hourly want times the hours of stock)
	 * @param maxMarkup the markup at an empty shop (0.5 = 50%)
	 * @return the unit price, a whole number of adena
	 */
	public static long price(long floor, long onSale, double demand, double maxMarkup)
	{
		if (demand <= 0.0)
		{
			return floor;
		}
		final double shortage = Math.max(0.0, Math.min(1.0, 1.0 - (onSale / demand)));
		return (long) Math.ceil(floor * (1.0 + (Math.max(0.0, maxMarkup) * shortage)));
	}
}

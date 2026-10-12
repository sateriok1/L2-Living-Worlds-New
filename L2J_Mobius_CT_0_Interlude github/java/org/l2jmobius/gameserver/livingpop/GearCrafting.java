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
import java.util.Set;

/**
 * The gear the Merchant crafts for the hunters: which recipes she can use right now, what the materials cost her, what
 * she sells the piece for and what she crafts next. She is benevolent: a piece sells at its cost plus a small margin.
 * Materials and recipes are bought from the towns' stock at a share of their reference price (better than the vendor
 * pays, so the bots that hunted them gain, and cheap enough not to drain her), and from the vendor shops when the stock
 * has none. Pure (no game types), so the standalone test lane covers it.
 */
public final class GearCrafting
{
	/** The share of the reference price she pays a bot for materials and recipes in the town stock (a vendor pays 50%). */
	public static final double STOCK_SHARE = 0.60;
	/** What she adds to the cost of a piece when she sells it. */
	public static final double MARGIN = 0.10;
	/** The phantom shops that sell her gear: D grade in Gludio, C grade and better in Giran. */
	public static final String D_SHOP = "SatDGear";
	public static final String C_SHOP = "SatCGear";
	public static final String D_TOWN = "Gludio";
	public static final String C_TOWN = "Giran";

	/**
	 * A piece she can craft.
	 * @param recipeItemId the recipe item she buys and learns
	 * @param productId the piece it makes
	 * @param perCraft pieces one craft makes
	 * @param ingredients ingredient item id to the count one craft takes
	 * @param grade the grade of the piece (1 D, 2 C, 3 B, 4 A, 5 S)
	 */
	public record Recipe(int recipeItemId, int productId, int perCraft, Map<Integer, Integer> ingredients, int grade)
	{
		/** @return the town where she sells it */
		public String saleTown()
		{
			return (grade <= 1) ? D_TOWN : C_TOWN;
		}

		/** @return the name of the phantom that sells it */
		public String shopName()
		{
			return (grade <= 1) ? D_SHOP : C_SHOP;
		}
	}

	/** What the world holds and charges. */
	public interface Market
	{
		/** @return how many of an item the towns' stock holds, all towns together */
		long inStock(int itemId);

		/** @return the reference price of an item, 0 when it has none */
		long referencePrice(int itemId);

		/** @return the lowest price a vendor shop in any town sells an item at, 0 when none does */
		long vendorPrice(int itemId);
	}

	/**
	 * What she pays for a number of an item.
	 * @param adena the price (what the owners of stock get, plus the vendor's price for the rest)
	 * @param fromStock how many come out of the town stock
	 */
	public record Cost(long adena, long fromStock)
	{
	}

	/**
	 * @param recipe what she crafts
	 * @param crafts how many crafts
	 * @param pieces the pieces they make
	 * @param materials the adena the materials cost
	 * @param recipeCost the adena the recipe costs (0 when she knows it)
	 */
	public record Plan(Recipe recipe, long crafts, long pieces, long materials, long recipeCost)
	{
		/** @return all the adena the plan takes */
		public long cost()
		{
			return materials + recipeCost;
		}
	}

	private GearCrafting()
	{
	}

	/** @return what a bot is paid for an item in the town stock: its share of the reference price, a whole adena at least */
	public static long stockPrice(long reference)
	{
		return Math.max(1L, (long) Math.floor(reference * STOCK_SHARE));
	}

	/**
	 * @param itemId an item
	 * @param count how many
	 * @param market the world
	 * @return what it costs: the stock first at its price, the rest at the vendor's, or null when neither holds that many
	 */
	public static Cost cost(int itemId, long count, Market market)
	{
		if (count <= 0L)
		{
			return new Cost(0L, 0L);
		}
		final long reference = market.referencePrice(itemId);
		final long fromStock = (reference > 0L) ? Math.min(count, Math.max(0L, market.inStock(itemId))) : 0L;
		final long rest = count - fromStock;
		long adena = fromStock * ((reference > 0L) ? stockPrice(reference) : 0L);
		if (rest > 0L)
		{
			final long vendor = market.vendorPrice(itemId);
			if (vendor <= 0L)
			{
				return null;
			}
			adena += rest * vendor;
		}
		return new Cost(adena, fromStock);
	}

	/** @return the adena the materials of one craft cost, or -1 when some are not to be had */
	public static long craftCost(Recipe recipe, Market market)
	{
		long total = 0L;
		for (Map.Entry<Integer, Integer> ingredient : recipe.ingredients().entrySet())
		{
			final Cost cost = cost(ingredient.getKey(), ingredient.getValue(), market);
			if (cost == null)
			{
				return -1L;
			}
			total += cost.adena();
		}
		return total;
	}

	/** @return the adena her recipe costs: 0 when she knows it, -1 when no stock or vendor has it */
	public static long recipeCost(Recipe recipe, Set<Integer> known, Market market)
	{
		if (known.contains(recipe.recipeItemId()))
		{
			return 0L;
		}
		final Cost cost = cost(recipe.recipeItemId(), 1L, market);
		return (cost == null) ? -1L : cost.adena();
	}

	/**
	 * Whether she can craft the piece now. A piece nobody can make now is not worth a demand signal, so the hunters only
	 * ask for what this says yes to.
	 * @param recipe the recipe
	 * @param level her level (the highest grade she wears is the highest she crafts)
	 * @param known the recipe items she has learned
	 * @param market the world
	 * @return whether she is able to craft one right now and it is worth it: the grade is open to her, the recipe is hers or to be had, every material is to be had, and her price is under the piece's reference price and under any vendor's
	 */
	public static boolean craftable(Recipe recipe, int level, Set<Integer> known, Market market)
	{
		if ((recipe.grade() < 1) || (recipe.grade() > LivingSupplies.gradeFor(level)) || (recipeCost(recipe, known, market) < 0L) || (craftCost(recipe, market) < 0L))
		{
			return false;
		}
		// It has to beat what a hunter pays anyway: the piece at its reference price (the trade stand-in), and the vendor shops of every town.
		final long price = price(recipe, market);
		final long reference = market.referencePrice(recipe.productId());
		final long vendor = market.vendorPrice(recipe.productId());
		return ((reference <= 0L) || (price < reference)) && ((vendor <= 0L) || (price < vendor));
	}

	/** @return the lowest price she sells one piece at: its materials' cost plus the margin, at least 1 adena above the cost */
	public static long price(Recipe recipe, Market market)
	{
		final long craft = craftCost(recipe, market);
		if (craft < 0L)
		{
			return -1L;
		}
		final double unit = craft / (double) Math.max(1, recipe.perCraft());
		return (long) Math.max(Math.ceil(unit * (1.0 + MARGIN)), Math.ceil(unit + 1.0));
	}

	/**
	 * What she crafts next: of the pieces she can craft now, the one the hunters want most that her shop does not already
	 * hold, as many as the demand calls for that she can pay.
	 * @param known the recipe items she has learned
	 * @param level her level
	 * @param recipes every recipe she could use
	 * @param wantedPerHour product id to the pieces hunters ask for an hour
	 * @param onSale product id to the pieces her shop holds
	 * @param hoursOfStock how many hours of demand a batch should cover
	 * @param maxCrafts the most crafts one trip makes
	 * @param budget the adena she can spend (recipe and materials)
	 * @param market the world
	 * @return the plan, or null when nothing is worth crafting
	 */
	public static Plan decide(Set<Integer> known, int level, Iterable<Recipe> recipes, Map<Integer, Double> wantedPerHour, Map<Integer, Long> onSale, double hoursOfStock, long maxCrafts, long budget, Market market)
	{
		Plan best = null;
		double bestValue = 0.0;
		for (Recipe recipe : recipes)
		{
			final double wanted = wantedPerHour.getOrDefault(recipe.productId(), 0.0);
			if ((wanted <= 0.0) || !craftable(recipe, level, known, market))
			{
				continue;
			}
			// Any wish is worth at least one piece; more wishes an hour call for more.
			final double gap = Math.max(1.0, wanted * hoursOfStock) - onSale.getOrDefault(recipe.productId(), 0L);
			if (gap <= 0.0)
			{
				continue;
			}
			final long recipeCost = recipeCost(recipe, known, market);
			final long craft = craftCost(recipe, market);
			if (budget < (recipeCost + craft))
			{
				continue;
			}
			// Later crafts may cost more (the stock runs out and a vendor sells the rest) or not be had at all: cost the whole batch, and take fewer crafts until it fits.
			long crafts = Math.max(1L, Math.min(Math.min(maxCrafts, (long) Math.ceil(gap / recipe.perCraft())), (craft > 0L) ? ((budget - recipeCost) / craft) : maxCrafts));
			long materials = batchCost(recipe, crafts, market);
			while ((crafts > 1L) && ((materials < 0L) || ((materials + recipeCost) > budget)))
			{
				crafts--;
				materials = batchCost(recipe, crafts, market);
			}
			if ((materials < 0L) || ((materials + recipeCost) > budget))
			{
				continue;
			}
			final double value = Math.min(gap, crafts * (double) recipe.perCraft()) * (craft / (double) Math.max(1, recipe.perCraft()));
			if (value > bestValue)
			{
				bestValue = value;
				best = new Plan(recipe, crafts, crafts * recipe.perCraft(), materials, recipeCost);
			}
		}
		return best;
	}

	/** @return the adena the materials of a number of crafts cost (the stock first, then the vendors), or -1 when they are not to be had */
	public static long batchCost(Recipe recipe, long crafts, Market market)
	{
		long total = 0L;
		for (Map.Entry<Integer, Integer> ingredient : recipe.ingredients().entrySet())
		{
			final Cost cost = cost(ingredient.getKey(), ingredient.getValue() * crafts, market);
			if (cost == null)
			{
				return -1L;
			}
			total += cost.adena();
		}
		return total;
	}
}

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
 * The gear the Merchant crafts on order: which recipes she can use right now (her craft level, the recipe and the materials
 * to be had), what the materials cost her, and what she charges the hunter that asked for the piece. She is benevolent: a
 * piece sells at its cost plus a small margin. Materials and recipes are bought from the towns' stock at a rolled share of
 * their reference price (better than the vendor pays, so the bots that hunted them gain), and from the vendor shops when the
 * stock has none. Pure (no game types), so the standalone test lane covers it.
 */
public final class GearCrafting
{
	/** The share of the reference price she pays a bot for materials and recipes in the town stock when nothing else is said (a vendor pays 50%). */
	public static final double STOCK_SHARE = 0.60;
	/** What she adds to the cost of a piece when she sells it. */
	public static final double MARGIN = 0.10;
	/** Her town of sale: D grade in Gludio, C grade and better in Giran. */
	public static final String D_TOWN = "Gludio";
	public static final String C_TOWN = "Giran";
	/** The levels at which she learns Create Item 1 to 9 (Dwarven Fighter 5, Artisan 20, 28, 36, Warsmith 43, 49, 55, 62, 70). */
	private static final int[] CRAFT_LEVELS =
	{
		5,
		20,
		28,
		36,
		43,
		49,
		55,
		62,
		70
	};

	/** The share of a recipe's cost added to a price when the hunter cannot afford the whole of it: 20 to 33%, so it comes back in a few crafts. */
	public static final double SURCHARGE_MIN = 0.20;
	public static final double SURCHARGE_MAX = 0.33;

	/**
	 * How she runs the gear trade.
	 * @param workingCapital the adena she keeps for crafting and shots before she spends any on herself
	 * @param upgradeShare the share of what is above that she may spend on her own upgrades
	 * @param craftBaseMinutes the minutes a craft takes at Create Item level 1 (see {@link #craftMinutes})
	 * @param shopLegMs how long each town of a buying trip takes (the walk to the gatekeeper, the teleport, the shops)
	 * @param meetMs how long she waits in the town for the hunter to come for its piece
	 * @param shareMin the lowest share of the reference price she pays for stock in a purchase
	 * @param shareMax the highest
	 * @param readyMs how long a finished piece waits for its hunter
	 * @param wishMs how long a wish lives without being refreshed
	 */
	public record Params(long workingCapital, double upgradeShare, double craftBaseMinutes, long shopLegMs, long meetMs, double shareMin, double shareMax, long readyMs, long wishMs, int streakMin, int streakMax, long huntMs)
	{
		/** Without a streak she does {@link #STREAK_MIN} to {@link #STREAK_MAX} orders in a row, then hunts {@link #HUNT_MS}. */
		public Params(long workingCapital, double upgradeShare, double craftBaseMinutes, long shopLegMs, long meetMs, double shareMin, double shareMax, long readyMs, long wishMs)
		{
			this(workingCapital, upgradeShare, craftBaseMinutes, shopLegMs, meetMs, shareMin, shareMax, readyMs, wishMs, STREAK_MIN, STREAK_MAX, HUNT_MS);
		}

		/** @return how many orders she may do in a row this time, rolled from the range (the first streak of the range when the roll is 0, the last at 1) */
		public int streakFor(double roll)
		{
			final int lo = Math.max(1, Math.min(streakMin, streakMax));
			final int hi = Math.max(lo, Math.max(streakMin, streakMax));
			return lo + (int) Math.floor(Math.max(0.0, Math.min(0.999999, roll)) * (hi - lo + 1));
		}
	}

	/** The safety control: she does this many orders (rolled between the two) in a row, then hunts for a guaranteed time before the next order. */
	public static final int STREAK_MIN = 4;
	public static final int STREAK_MAX = 10;
	public static final long HUNT_MS = 2 * 3_600_000L;

	/**
	 * A piece she can craft.
	 * @param recipeItemId the recipe item she buys and learns
	 * @param productId the piece it makes
	 * @param perCraft pieces one craft makes
	 * @param ingredients ingredient item id to the count one craft takes
	 * @param grade the grade of the piece (1 D, 2 C, 3 B, 4 A, 5 S)
	 * @param craftLevel the Create Item level the recipe takes (1 to 9)
	 */
	public record Recipe(int recipeItemId, int productId, int perCraft, Map<Integer, Integer> ingredients, int grade, int craftLevel)
	{
		/** @return the town where she sells it */
		public String saleTown()
		{
			return (grade <= 1) ? D_TOWN : C_TOWN;
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

		/** @return the share of the reference price she pays for stock in this purchase */
		default double stockShare()
		{
			return STOCK_SHARE;
		}
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
	 * What a piece costs her and what the hunter pays.
	 * @param recipe what she crafts
	 * @param materials the adena the materials of the craft cost
	 * @param recipeCost the adena the recipe costs (0 when she knows it)
	 * @param price what the hunter pays for the piece
	 * @param surcharge the part of the recipe's cost in the price (the whole of it when the hunter can afford that, else a share)
	 * @param share the share of the reference price she pays the owners of the stock in this purchase
	 */
	public record Quote(Recipe recipe, long materials, long recipeCost, long price, long surcharge, double share)
	{
		/** @return all the adena she lays out to craft it */
		public long outlay()
		{
			return materials + recipeCost;
		}
	}

	private GearCrafting()
	{
	}

	/** @return the Create Item level she has at a character level (0 before she learns it) */
	public static int craftLevelFor(int level)
	{
		int craft = 0;
		for (int i = 0; i < CRAFT_LEVELS.length; i++)
		{
			if (level >= CRAFT_LEVELS[i])
			{
				craft = i + 1;
			}
		}
		return craft;
	}

	/**
	 * How long a craft takes: slow at first, quicker as her craft level rises, and longer for a recipe near her limit.
	 * @param baseMinutes the minutes a craft of her limit takes at craft level 1
	 * @param recipeLevel the recipe's craft level
	 * @param herLevel her craft level
	 * @return the minutes (at least a quarter of the base)
	 */
	public static double craftMinutes(double baseMinutes, int recipeLevel, int herLevel)
	{
		final int her = Math.max(1, herLevel);
		final double closeness = 0.5 + (0.5 * Math.min(1.0, recipeLevel / (double) her));
		return baseMinutes * closeness * Math.max(0.25, 1.0 - (0.1 * (her - 1)));
	}

	/** @return what a bot is paid for an item in the town stock at the default share, a whole adena at least */
	public static long stockPrice(long reference)
	{
		return stockPrice(reference, STOCK_SHARE);
	}

	/** @return what a bot is paid for an item in the town stock at a share of its reference price, a whole adena at least */
	public static long stockPrice(long reference, double share)
	{
		return Math.max(1L, (long) Math.floor(reference * share));
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
		long adena = fromStock * ((reference > 0L) ? stockPrice(reference, market.stockShare()) : 0L);
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
	 * @param level her level (her Create Item level and the grade she may craft follow it)
	 * @param known the recipe items she has learned
	 * @param market the world
	 * @return whether she is able to craft one right now and it is worth it: her craft level reaches the recipe's, the recipe is hers or to be had, every material is to be had, and her price is under the piece's reference price and under any vendor's
	 */
	public static boolean craftable(Recipe recipe, int level, Set<Integer> known, Market market)
	{
		return quote(recipe, level, known, market, Long.MAX_VALUE, 0.0) != null;
	}

	/**
	 * What she would charge for the piece, or null when she cannot or should not craft it.
	 * @param recipe the recipe
	 * @param level her level
	 * @param known the recipe items she has learned
	 * @param market the world (with the share she pays in this purchase)
	 * @param budget what the hunter can pay (use {@link Long#MAX_VALUE} to ask only whether it can be crafted)
	 * @param surchargeShare the share of the recipe's cost to add to the price when the hunter cannot afford the whole of it
	 * @return the quote: the price is the cost plus 10%. When she must buy the recipe its cost is added to this first sale if the hunter can afford that, else a share of it (so the next crafts bring it back)
	 */
	public static Quote quote(Recipe recipe, int level, Set<Integer> known, Market market, long budget, double surchargeShare)
	{
		if ((recipe.grade() < 1) || (recipe.grade() > LivingSupplies.gradeFor(level)) || (recipe.craftLevel() > craftLevelFor(level)))
		{
			return null;
		}
		final long recipeCost = recipeCost(recipe, known, market);
		final long materials = craftCost(recipe, market);
		if ((recipeCost < 0L) || (materials < 0L))
		{
			return null;
		}
		final double unit = materials / (double) Math.max(1, recipe.perCraft());
		long surcharge = recipeCost;
		long price = priceOf(unit, surcharge);
		if ((recipeCost > 0L) && (price > budget))
		{
			surcharge = (long) Math.ceil(recipeCost * Math.max(0.0, surchargeShare));
			price = priceOf(unit, surcharge);
		}
		if (price > budget)
		{
			return null;
		}
		// It has to beat what a hunter pays anyway: the piece at its reference price (the trade stand-in), and the vendor shops of every town.
		final long reference = market.referencePrice(recipe.productId());
		final long vendor = market.vendorPrice(recipe.productId());
		if (((reference > 0L) && (price >= reference)) || ((vendor > 0L) && (price >= vendor)))
		{
			return null;
		}
		return new Quote(recipe, materials, recipeCost, price, surcharge, market.stockShare());
	}

	private static long priceOf(double unit, long surcharge)
	{
		final double basis = unit + surcharge;
		return (long) Math.max(Math.ceil(basis * (1.0 + MARGIN)), Math.ceil(basis + 1.0));
	}
}

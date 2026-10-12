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

import java.util.List;

/**
 * The four shot recipes a Merchant crafts (D and C grade soulshots and blessed spiritshots): what each makes, what it
 * takes, what that costs and the lowest price the Merchant sells at. The ingredient counts come from Recipes.xml.
 * Pure (no game types), so the standalone test lane covers it.
 */
public final class ShotCrafting
{
	/** Which ore a recipe takes. */
	public enum Ore
	{
		SOUL,
		SPIRIT
	}

	/**
	 * @param recipeItemId the recipe item a bot buys and learns
	 * @param shotItemId the shot it makes
	 * @param perCraft shots one craft makes
	 * @param ore the ore it takes
	 * @param oreCount ore per craft
	 * @param cGrade whether its crystals are C grade (else D)
	 * @param crystals crystals per craft
	 * @param minLevel the lowest level that can craft it
	 * @param saleTown where the Merchant sells it
	 */
	public record Recipe(int recipeItemId, int shotItemId, int perCraft, Ore ore, int oreCount, boolean cGrade, int crystals, int minLevel, String saleTown)
	{
		/** @return whether this is a blessed spiritshot recipe */
		public boolean blessed()
		{
			return ore == Ore.SPIRIT;
		}

		/** @return the name of the phantom that sells this shot */
		public String shopName()
		{
			return cGrade ? C_SHOP : D_SHOP;
		}
	}

	/**
	 * What the materials cost, per piece. Crystals are valued at what it costs to produce them, never at the vendor value.
	 * @param soulOre a Soul Ore
	 * @param spiritOre a Spirit Ore
	 * @param dCrystal a D crystal
	 * @param cCrystal a C crystal
	 */
	public record Prices(long soulOre, long spiritOre, long dCrystal, long cCrystal)
	{
		/** Giran's ore prices with its 10% tax, and the crystals at their production cost. */
		public static Prices standard()
		{
			return new Prices(275L, 440L, 596L, 2799L);
		}
	}

	/** The Merchant starts on the D recipes at level 20 (Create Item 2) and takes on the C ones at level 40 (Warsmith). */
	public static final int D_LEVEL = 20;
	public static final int C_LEVEL = 40;

	/** The Merchant's character name: the one dwarf that crafts. */
	public static final String MERCHANT = "Sateya";
	/** Her title in the world. */
	public static final String MERCHANT_TITLE = "The Merchant";
	/** The phantom that sells the D shots in Gludio and the one that sells the C shots in Giran. */
	public static final String D_SHOP = "SatDShots";
	public static final String C_SHOP = "SatCShots";

	public static final List<Recipe> RECIPES = List.of(
		new Recipe(1804, 1463, 156, Ore.SOUL, 3, false, 1, D_LEVEL, "Gludio"), // Soulshot D
		new Recipe(1805, 1464, 476, Ore.SOUL, 15, true, 1, C_LEVEL, "Giran"), // Soulshot C
		new Recipe(3953, 3948, 100, Ore.SPIRIT, 8, false, 2, D_LEVEL, "Gludio"), // Blessed Spiritshot D
		new Recipe(3954, 3949, 200, Ore.SPIRIT, 30, true, 2, C_LEVEL, "Giran")); // Blessed Spiritshot C

	private ShotCrafting()
	{
	}

	/** @return the recipe with this recipe item id, or null */
	public static Recipe byRecipe(int recipeItemId)
	{
		for (Recipe recipe : RECIPES)
		{
			if (recipe.recipeItemId() == recipeItemId)
			{
				return recipe;
			}
		}
		return null;
	}

	/** @return the recipe that makes this shot, or null */
	public static Recipe byShot(int shotItemId)
	{
		for (Recipe recipe : RECIPES)
		{
			if (recipe.shotItemId() == shotItemId)
			{
				return recipe;
			}
		}
		return null;
	}

	/** @return the adena the materials of one craft cost */
	public static long craftCost(Recipe recipe, Prices prices)
	{
		final long ore = recipe.oreCount() * ((recipe.ore() == Ore.SOUL) ? prices.soulOre() : prices.spiritOre());
		return ore + (recipe.crystals() * (recipe.cGrade() ? prices.cCrystal() : prices.dCrystal()));
	}

	/** @return the adena one shot costs to make */
	public static double unitCost(Recipe recipe, Prices prices)
	{
		return craftCost(recipe, prices) / (double) recipe.perCraft();
	}

	/** @return the lowest price the Merchant sells one shot at: its cost plus 1 adena, rounded up to a whole adena */
	public static long floorPrice(Recipe recipe, Prices prices)
	{
		return (long) Math.ceil(unitCost(recipe, prices) + 1.0);
	}

	/** @return bit of a recipe in the set of recipes a bot has learned */
	public static int bit(Recipe recipe)
	{
		return 1 << RECIPES.indexOf(recipe);
	}
}

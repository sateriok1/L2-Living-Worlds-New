package org.l2jmobius.gameserver.livingpop;

import java.util.function.DoubleUnaryOperator;

/**
 * Weighs potions against resting. A potion heals part of the HP a bot would otherwise sit to regain, so a bot that drinks hunts more of the hour; it
 * costs adena. The bot drinks the amount (up to what it needs and can afford) where the extra kills it makes are worth more than the potions cost,
 * and rests for the rest. Pure: a function of its inputs.
 */
public final class PotionPlanner
{
	/** The amounts tried, as shares of the potions the bot needs an hour. */
	private static final double[] STEPS =
	{
		0.0,
		0.25,
		0.5,
		0.75,
		1.0
	};

	private PotionPlanner()
	{
	}

	/**
	 * @param killsPerMinuteAt the bot's kills a minute when it drinks this many potions an hour
	 * @param need the most potions an hour that are of any use to it (what its fights cost, already cut to what it can afford); at most this many are tried
	 * @param valuePerKill what one kill is worth to it, in adena (the adena and loot it drops, plus what its experience is valued at); 0 or less means unknown
	 * @param unitPrice adena one potion costs
	 * @return the potions an hour to drink: the amount that leaves the most value after paying for the potions, the smaller one when equal. All of the need when the
	 *         value of a kill or the price is not known
	 */
	public static double choose(DoubleUnaryOperator killsPerMinuteAt, double need, double valuePerKill, double unitPrice)
	{
		if (need <= 0.0)
		{
			return 0.0;
		}
		if ((valuePerKill <= 0.0) || (unitPrice <= 0.0))
		{
			return need;
		}
		double best = 0.0;
		double bestProfit = Double.NEGATIVE_INFINITY;
		for (double share : STEPS)
		{
			final double potions = need * share;
			final double profit = (killsPerMinuteAt.applyAsDouble(potions) * 60.0 * valuePerKill) - (potions * unitPrice);
			if (profit > (bestProfit + 1e-9))
			{
				bestProfit = profit;
				best = potions;
			}
		}
		return best;
	}
}

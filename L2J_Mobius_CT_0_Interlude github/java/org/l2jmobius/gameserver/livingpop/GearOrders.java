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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The book of gear orders that the hunters and the Merchant share. A hunter that wishes for a piece she can craft puts a
 * wish in the book; she works through the wishes strongest hunter first (the highest level gets the first grab at her
 * time and at the materials), turns one into an order she crafts and, when the piece is ready, parks it in a town for the
 * hunter to come and trade for. A wish stays in the book until it is filled or goes stale, so demand does not vanish
 * while she crafts. Pure (no game types), so the standalone test lane covers it. {@link #encode} and {@link #decode} keep it
 * across a restart.
 */
public final class GearOrders
{
	/** Where an order is. */
	public enum State
	{
		/** The hunter wishes for it. */
		WISH,
		/** She has bought the materials and is crafting it. */
		CRAFTING,
		/** It is crafted and parked in a town for the hunter. */
		READY
	}

	/**
	 * @param botId the hunter
	 * @param botLevel its level, when it last wished (the strongest are served first)
	 * @param productId the piece
	 * @param budget what it could spend on it when it last wished
	 * @param state where the order is
	 * @param price what it pays in all (0 until she quotes)
	 * @param advance what it has already paid her toward the price
	 * @param town the town the finished piece is parked in (READY)
	 * @param craftMs how long she crafts it (CRAFTING)
	 * @param at when the order last changed state or was refreshed
	 */
	public record Order(long botId, int botLevel, int productId, long budget, State state, long price, long advance, String town, long craftMs, long at)
	{
		/** @return what the hunter still owes when it comes for the piece */
		public long due()
		{
			return Math.max(0L, price - advance);
		}
	}

	private final Map<Long, Order> _orders = new LinkedHashMap<>();
	private volatile boolean _dirty;

	/**
	 * A hunter wishes for a piece. A wish already being crafted or ready stays as it is.
	 * @param budget what the hunter can spend on it now
	 */
	public synchronized void wish(long botId, int botLevel, int productId, long budget, long now)
	{
		final Order old = _orders.get(botId);
		if ((old != null) && (old.state() != State.WISH))
		{
			return;
		}
		_orders.put(botId, new Order(botId, botLevel, productId, budget, State.WISH, 0L, 0L, null, 0L, now));
		_dirty = true;
	}

	/** A hunter no longer wishes for anything: only a wish is dropped, never an order she is crafting or has ready. */
	public synchronized void clearWish(long botId)
	{
		final Order old = _orders.get(botId);
		if ((old != null) && (old.state() == State.WISH))
		{
			_orders.remove(botId);
			_dirty = true;
		}
	}

	/** @return the hunter's order, or null */
	public synchronized Order of(long botId)
	{
		return _orders.get(botId);
	}

	/** @return every wish, the strongest hunter first (the oldest first among equals) */
	public synchronized List<Order> wishes()
	{
		final List<Order> wishes = new ArrayList<>();
		for (Order order : _orders.values())
		{
			if (order.state() == State.WISH)
			{
				wishes.add(order);
			}
		}
		wishes.sort(Comparator.comparingInt(Order::botLevel).reversed().thenComparingLong(Order::at).thenComparingLong(Order::botId));
		return wishes;
	}

	/** @return how many orders are in a state */
	public synchronized int count(State state)
	{
		int count = 0;
		for (Order order : _orders.values())
		{
			count += (order.state() == state) ? 1 : 0;
		}
		return count;
	}

	/** @return how many orders for a piece are ready (parked, reserved for their hunters) */
	public synchronized int ready(int productId)
	{
		int count = 0;
		for (Order order : _orders.values())
		{
			count += ((order.state() == State.READY) && (order.productId() == productId)) ? 1 : 0;
		}
		return count;
	}

	/**
	 * She takes the wish and starts crafting it.
	 * @param route the last town of her buying trip (where she crafts)
	 * @return the order, or null when there was no wish
	 */
	public synchronized Order accept(long botId, long price, long advance, long craftMs, String route, long now)
	{
		final Order old = _orders.get(botId);
		if ((old == null) || (old.state() != State.WISH))
		{
			return null;
		}
		final Order order = new Order(botId, old.botLevel(), old.productId(), old.budget(), State.CRAFTING, price, advance, route, craftMs, now);
		_orders.put(botId, order);
		_dirty = true;
		return order;
	}

	/** The piece is crafted and parked in a town. @return the order, or null when it is gone */
	public synchronized Order ready(long botId, String town, long now)
	{
		final Order old = _orders.get(botId);
		if ((old == null) || (old.state() != State.CRAFTING))
		{
			return null;
		}
		final Order order = new Order(botId, old.botLevel(), old.productId(), old.budget(), State.READY, old.price(), old.advance(), town, 0L, now);
		_orders.put(botId, order);
		_dirty = true;
		return order;
	}

	/** The order is done or cancelled. @return the order that was removed, or null */
	public synchronized Order remove(long botId)
	{
		final Order old = _orders.remove(botId);
		_dirty |= old != null;
		return old;
	}

	/** @return the order Sateya is crafting now, or null (she crafts one at a time) */
	public synchronized Order crafting()
	{
		for (Order order : _orders.values())
		{
			if (order.state() == State.CRAFTING)
			{
				return order;
			}
		}
		return null;
	}

	/**
	 * Drops what has gone stale: a wish nobody has refreshed, and a piece nobody came for.
	 * @param wishMs how long a wish lives without being refreshed
	 * @param readyMs how long a finished piece waits for its hunter
	 * @param craftingMs how long an order may sit in CRAFTING (it is lost if the Merchant's trip was lost, say in a restart)
	 * @return the orders that were dropped
	 */
	public synchronized List<Order> expire(long now, long wishMs, long readyMs, long craftingMs)
	{
		final List<Order> gone = new ArrayList<>();
		for (Order order : new ArrayList<>(_orders.values()))
		{
			final boolean stale = ((order.state() == State.WISH) && ((now - order.at()) > wishMs)) || ((order.state() == State.READY) && ((now - order.at()) > readyMs)) || ((order.state() == State.CRAFTING) && ((now - order.at()) > craftingMs));
			if (stale)
			{
				_orders.remove(order.botId());
				gone.add(order);
			}
		}
		_dirty |= !gone.isEmpty();
		return gone;
	}

	public boolean dirty()
	{
		return _dirty;
	}

	/** @return the book as text, one order a line */
	public synchronized String encode()
	{
		final StringBuilder sb = new StringBuilder();
		for (Order o : _orders.values())
		{
			sb.append(o.botId()).append('|').append(o.botLevel()).append('|').append(o.productId()).append('|').append(o.budget()).append('|').append(o.state()).append('|').append(o.price()).append('|').append(o.advance()).append('|').append((o.town() == null) ? "" : o.town()).append('|').append(o.craftMs()).append('|').append(o.at()).append('\n');
		}
		_dirty = false;
		return sb.toString();
	}

	/** Reads what {@link #encode} wrote; lines that cannot be read are skipped. */
	public synchronized void decode(String text)
	{
		_orders.clear();
		if (text == null)
		{
			return;
		}
		for (String line : text.split("\n"))
		{
			try
			{
				final String[] p = line.split("\\|", -1);
				final long botId = Long.parseLong(p[0]);
				_orders.put(botId, new Order(botId, Integer.parseInt(p[1]), Integer.parseInt(p[2]), Long.parseLong(p[3]), State.valueOf(p[4]), Long.parseLong(p[5]), Long.parseLong(p[6]), p[7].isEmpty() ? null : p[7], Long.parseLong(p[8]), Long.parseLong(p[9])));
			}
			catch (RuntimeException e)
			{
				// skip the line
			}
		}
	}
}

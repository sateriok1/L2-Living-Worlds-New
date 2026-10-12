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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The requests real players make of the Merchant, Sateya, for a piece of gear. A request sets aside the town stock its piece
 * needs (a ledger: the stock stays where it is, but nobody else's order may count it) until the order is filled. When a hunter
 * is held up by what is set aside and the request has waited {@link #ASK_AFTER_MS}, she asks the player whether to go ahead
 * now; a no, or no answer in {@link #ANSWER_MS}, releases the stock. Pure (no game types), so the standalone tests cover it.
 */
public final class PlayerRequests
{
	/** How long a request waits, fulfillable, before a hunter held up by it makes her ask the player. */
	public static final long ASK_AFTER_MS = 10 * 60_000L;
	/** How long the player has to answer her. */
	public static final long ANSWER_MS = 5 * 60_000L;
	/** How long a request lives in all, and a confirmed one before she must have started it. */
	public static final long LIFE_MS = 24 * 3_600_000L;
	public static final long CONFIRMED_LIFE_MS = 30 * 60_000L;

	/**
	 * One request.
	 * @param playerId the player's object id
	 * @param name the player's name
	 * @param level the player's level
	 * @param productId the piece asked for
	 * @param mats the town stock set aside: item id to count
	 * @param since when the request was made
	 * @param askedAt when she asked the player to go ahead (0 not asked)
	 * @param confirmedAt when the player said yes (0 not confirmed)
	 */
	public record Request(long playerId, String name, int level, int productId, Map<Integer, Long> mats, long since, long askedAt, long confirmedAt)
	{
	}

	/** How long a player has, from the moment its piece is ready, to come and pay before she gets her money back. */
	public static final long HOLD_MS = 60 * 60_000L;

	/**
	 * What the Merchant laid out on a player's order, kept until the player fulfills it: if it does not within {@link #HOLD_MS}
	 * she is refunded {@code outlay} and keeps {@code mats} for other crafts.
	 * @param playerId the player
	 * @param outlay the adena she spent (materials and recipe)
	 * @param mats the materials of the craft: item id to count
	 */
	public record Spent(long playerId, long outlay, Map<Integer, Long> mats)
	{
	}

	/** The least time between two requests from one player, kept whether the first was filled, cancelled or lapsed, so no one can tie her up. */
	public static final long COOLDOWN_MS = 60 * 60_000L;

	private final Map<Long, Long> _lastOpened = new LinkedHashMap<>();
	private final Map<Long, Spent> _spent = new LinkedHashMap<>();
	private final Map<Long, Request> _requests = new LinkedHashMap<>();
	private volatile boolean _dirty;

	/** A player makes a request, replacing an earlier one of its own. */
	public synchronized void open(long playerId, String name, int level, int productId, Map<Integer, Long> mats, long now)
	{
		_requests.put(playerId, new Request(playerId, (name == null) ? "" : name.replace('|', ' '), level, productId, new TreeMap<>(mats), now, 0L, 0L));
		_lastOpened.put(playerId, now);
		_dirty = true;
	}

	/** @return how long this player must wait before it may make a request (0 when it may now) */
	public synchronized long cooldownLeft(long playerId, long now)
	{
		final Long last = _lastOpened.get(playerId);
		return (last == null) ? 0L : Math.max(0L, (last + COOLDOWN_MS) - now);
	}

	public synchronized Request of(long playerId)
	{
		return _requests.get(playerId);
	}

	/** @return every request, oldest first */
	public synchronized List<Request> all()
	{
		return new ArrayList<>(_requests.values());
	}

	/** The request is cancelled or filled; what it set aside is free. @return the request, or null */
	public synchronized Request remove(long playerId)
	{
		final Request old = _requests.remove(playerId);
		_dirty |= old != null;
		return old;
	}

	/**
	 * @param exceptPlayer a player whose own request does not count, or {@link Long#MIN_VALUE} for none excepted... use {@link #ALL_FREE} to count none
	 * @return the stock set aside, item id to count, by every request but that player's
	 */
	public synchronized Map<Integer, Long> reserved(long exceptPlayer)
	{
		final Map<Integer, Long> total = new TreeMap<>();
		if (exceptPlayer == ALL_FREE)
		{
			return total;
		}
		for (Request request : _requests.values())
		{
			if (request.playerId() != exceptPlayer)
			{
				request.mats().forEach((item, count) -> total.merge(item, count, Long::sum));
			}
		}
		return total;
	}

	/** Passed to {@link #reserved} to see the stock as if no request had set any aside. */
	public static final long ALL_FREE = Long.MIN_VALUE;

	/** She asks the player to go ahead. @return whether the request exists and had not been asked */
	public synchronized boolean ask(long playerId, long now)
	{
		final Request old = _requests.get(playerId);
		if ((old == null) || (old.askedAt() > 0L) || (old.confirmedAt() > 0L))
		{
			return false;
		}
		_requests.put(playerId, new Request(old.playerId(), old.name(), old.level(), old.productId(), old.mats(), old.since(), now, 0L));
		_dirty = true;
		return true;
	}

	/** @return whether she is waiting for this player's answer */
	public synchronized boolean asked(long playerId)
	{
		final Request old = _requests.get(playerId);
		return (old != null) && (old.askedAt() > 0L) && (old.confirmedAt() <= 0L);
	}

	/** The player says yes. @return the request, or null when she had not asked */
	public synchronized Request confirm(long playerId, long now)
	{
		final Request old = _requests.get(playerId);
		if ((old == null) || (old.askedAt() <= 0L) || (old.confirmedAt() > 0L))
		{
			return null;
		}
		final Request next = new Request(old.playerId(), old.name(), old.level(), old.productId(), old.mats(), old.since(), old.askedAt(), now);
		_requests.put(playerId, next);
		_dirty = true;
		return next;
	}

	/** @return the requests that have waited long enough for her to ask (never asked, not confirmed), oldest first */
	public synchronized List<Request> askable(long now)
	{
		final List<Request> list = new ArrayList<>();
		for (Request request : _requests.values())
		{
			if ((request.askedAt() <= 0L) && (request.confirmedAt() <= 0L) && ((now - request.since()) >= ASK_AFTER_MS))
			{
				list.add(request);
			}
		}
		return list;
	}

	/**
	 * Drops what has run out: a question the player did not answer, a yes she did not start in time, and an old request.
	 * @return the requests that were dropped
	 */
	public synchronized List<Request> expire(long now)
	{
		final List<Request> gone = new ArrayList<>();
		for (Request request : new ArrayList<>(_requests.values()))
		{
			final boolean unanswered = (request.askedAt() > 0L) && (request.confirmedAt() <= 0L) && ((now - request.askedAt()) > ANSWER_MS);
			final boolean unstarted = (request.confirmedAt() > 0L) && ((now - request.confirmedAt()) > CONFIRMED_LIFE_MS);
			if (unanswered || unstarted || ((now - request.since()) > LIFE_MS))
			{
				_requests.remove(request.playerId());
				gone.add(request);
			}
		}
		_dirty |= !gone.isEmpty();
		return gone;
	}

	/** She has laid out money on this player's order. */
	public synchronized void spend(long playerId, long outlay, Map<Integer, Long> mats)
	{
		_spent.put(playerId, new Spent(playerId, Math.max(0L, outlay), new TreeMap<>(mats)));
		_dirty = true;
	}

	/** @return what she laid out on the player's order, or null */
	public synchronized Spent spent(long playerId)
	{
		return _spent.get(playerId);
	}

	/** The player fulfilled it, or she has been refunded. @return what was kept, or null */
	public synchronized Spent settle(long playerId)
	{
		final Spent old = _spent.remove(playerId);
		_dirty |= old != null;
		return old;
	}

	public boolean dirty()
	{
		return _dirty;
	}

	/** @return the requests as text, one a line: {@code player|name|level|product|since|asked|confirmed|item:count;item:count} */
	public synchronized String encode()
	{
		final StringBuilder sb = new StringBuilder();
		for (Map.Entry<Long, Long> opened : _lastOpened.entrySet())
		{
			sb.append("C|").append(opened.getKey()).append('|').append(opened.getValue()).append('\n');
		}
		for (Spent s : _spent.values())
		{
			sb.append("S|").append(s.playerId()).append('|').append(s.outlay()).append('|');
			boolean first = true;
			for (Map.Entry<Integer, Long> mat : s.mats().entrySet())
			{
				sb.append(first ? "" : ";").append(mat.getKey()).append(':').append(mat.getValue());
				first = false;
			}
			sb.append('\n');
		}
		for (Request r : _requests.values())
		{
			sb.append(r.playerId()).append('|').append(r.name()).append('|').append(r.level()).append('|').append(r.productId()).append('|').append(r.since()).append('|').append(r.askedAt()).append('|').append(r.confirmedAt()).append('|');
			boolean first = true;
			for (Map.Entry<Integer, Long> mat : r.mats().entrySet())
			{
				sb.append(first ? "" : ";").append(mat.getKey()).append(':').append(mat.getValue());
				first = false;
			}
			sb.append('\n');
		}
		_dirty = false;
		return sb.toString();
	}

	/** Reads what {@link #encode} wrote; lines that cannot be read are skipped. */
	public synchronized void decode(String text)
	{
		_requests.clear();
		_spent.clear();
		_lastOpened.clear();
		if (text == null)
		{
			return;
		}
		for (String line : text.split("\n"))
		{
			try
			{
				final String[] p = line.split("\\|", -1);
				final Map<Integer, Long> mats = new TreeMap<>();
				if ("C".equals(p[0]))
				{
					_lastOpened.put(Long.parseLong(p[1]), Long.parseLong(p[2]));
					continue;
				}
				if ("S".equals(p[0]))
				{
					if (!p[3].isEmpty())
					{
						for (String pair : p[3].split(";"))
						{
							final String[] bits = pair.split(":");
							mats.put(Integer.parseInt(bits[0]), Long.parseLong(bits[1]));
						}
					}
					_spent.put(Long.parseLong(p[1]), new Spent(Long.parseLong(p[1]), Long.parseLong(p[2]), mats));
					continue;
				}
				if (!p[7].isEmpty())
				{
					for (String pair : p[7].split(";"))
					{
						final String[] bits = pair.split(":");
						mats.put(Integer.parseInt(bits[0]), Long.parseLong(bits[1]));
					}
				}
				final long id = Long.parseLong(p[0]);
				_requests.put(id, new Request(id, p[1], Integer.parseInt(p[2]), Integer.parseInt(p[3]), mats, Long.parseLong(p[4]), Long.parseLong(p[5]), Long.parseLong(p[6])));
			}
			catch (RuntimeException e)
			{
				// skip the line
			}
		}
	}
}

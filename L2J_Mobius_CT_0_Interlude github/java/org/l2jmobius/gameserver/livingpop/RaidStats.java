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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What the raid parties have done against each boss, kept in the background so the fight model can be checked against what happens:
 * how often parties won, how often the model said they would, and which bosses are deadlier or easier than predicted. Every fight
 * (a retry counts as one) is recorded with the win chance the model gave its party. A boss whose recent fights are far from that
 * ("the last 20 parties failed it at an 80% chance") is flagged as an outlier. Pure (no game types), so the standalone tests cover it.
 */
public final class RaidStats
{
	/** The z-score (about 1%, two-sided) beyond which recent results are called an outlier. */
	public static final double OUTLIER_Z = 2.33;
	/** The fewest recent fights before a boss can be called an outlier. */
	public static final int MIN_SAMPLES = 8;

	private record Result(double predicted, boolean won)
	{
	}

	private static final class Boss
	{
		final int _id;
		String _name;
		int _level;
		long _attempts;
		long _wins;
		double _expected;
		long _members;
		double _killSeconds;
		double _ratio;
		boolean _flagged;
		final Deque<Result> _recent = new ArrayDeque<>();

		Boss(int id)
		{
			_id = id;
		}
	}

	/** One boss's figures. {@code z} is how far the recent wins are from the wins expected (negative: deadlier than predicted). */
	public record Line(int id, String name, int level, long attempts, long wins, double expectedWins, double avgPredicted, double avgPartySize, double avgKillSeconds, double avgRatio, int recentAttempts, int recentWins, double recentExpected, double z, String flag)
	{
	}

	private final int _window;
	private final Map<Integer, Boss> _bosses = new LinkedHashMap<>();
	private final List<String> _alerts = new ArrayList<>();
	private volatile boolean _dirty;

	/** @param window how many of a boss's latest fights the outlier check looks at */
	public RaidStats(int window)
	{
		_window = Math.max(MIN_SAMPLES, window);
	}

	/**
	 * Records one fight.
	 * @param predicted the win chance the model gave the party (0 to 1)
	 * @param won whether the party won
	 * @param partySize how many fought
	 * @param killSeconds how long the model gave the fight
	 * @param ratio how long the tank lasts over the fight, as the model saw it
	 */
	public synchronized void record(int bossId, String name, int level, double predicted, boolean won, int partySize, double killSeconds, double ratio)
	{
		final Boss boss = _bosses.computeIfAbsent(bossId, Boss::new);
		boss._name = (name == null) ? String.valueOf(bossId) : name.replace('|', ' ').replace('\n', ' ');
		boss._level = level;
		final double chance = Math.max(0.0, Math.min(1.0, predicted));
		boss._attempts++;
		boss._wins += won ? 1 : 0;
		boss._expected += chance;
		boss._members += partySize;
		boss._killSeconds += Double.isFinite(killSeconds) ? killSeconds : 0.0;
		boss._ratio += Double.isFinite(ratio) ? Math.min(ratio, 10.0) : 10.0;
		boss._recent.addLast(new Result(chance, won));
		while (boss._recent.size() > _window)
		{
			boss._recent.removeFirst();
		}
		final String flag = flagOf(boss);
		if ((flag != null) && !boss._flagged)
		{
			final Line line = line(boss);
			_alerts.add(boss._name + " (level " + boss._level + "): " + line.recentWins() + " wins in the last " + line.recentAttempts() + " fights where the model expected " + String.format("%.1f", line.recentExpected()) + ". " + flag);
		}
		boss._flagged = flag != null;
		_dirty = true;
	}

	/** @return the alerts raised since the last call (a boss that has just become an outlier), and clears them */
	public synchronized List<String> takeAlerts()
	{
		final List<String> alerts = new ArrayList<>(_alerts);
		_alerts.clear();
		return alerts;
	}

	public boolean dirty()
	{
		return _dirty;
	}

	/** @return the figures of every boss that has been fought, the most out of line with the model first */
	public synchronized List<Line> lines()
	{
		final List<Line> lines = new ArrayList<>();
		for (Boss boss : _bosses.values())
		{
			lines.add(line(boss));
		}
		lines.sort(Comparator.comparingDouble((Line l) -> -Math.abs(l.z())).thenComparingInt(Line::id));
		return lines;
	}

	/** @return the figures of one boss, or null when it has not been fought */
	public synchronized Line line(int bossId)
	{
		final Boss boss = _bosses.get(bossId);
		return (boss == null) ? null : line(boss);
	}

	private Line line(Boss boss)
	{
		double expected = 0.0;
		int wins = 0;
		for (Result result : boss._recent)
		{
			expected += result.predicted();
			wins += result.won() ? 1 : 0;
		}
		final double variance = variance(boss);
		final double z = (boss._recent.size() < MIN_SAMPLES) ? 0.0 : ((wins - expected) / Math.sqrt(Math.max(0.25, variance)));
		final double attempts = Math.max(1L, boss._attempts);
		return new Line(boss._id, boss._name, boss._level, boss._attempts, boss._wins, boss._expected, boss._expected / attempts, boss._members / attempts, boss._killSeconds / attempts, boss._ratio / attempts, boss._recent.size(), wins, expected, z, flagOf(boss));
	}

	private static double variance(Boss boss)
	{
		double variance = 0.0;
		for (Result result : boss._recent)
		{
			variance += result.predicted() * (1.0 - result.predicted());
		}
		return variance;
	}

	private static String flagOf(Boss boss)
	{
		if (boss._recent.size() < MIN_SAMPLES)
		{
			return null;
		}
		double expected = 0.0;
		int wins = 0;
		for (Result result : boss._recent)
		{
			expected += result.predicted();
			wins += result.won() ? 1 : 0;
		}
		final double variance = variance(boss);
		// With almost no spread in the predictions (all near 0 or 1) a single miss is not a statistic; use a floor so a certain win that fails still shows.
		final double z = (wins - expected) / Math.sqrt(Math.max(0.25, variance));
		if (z <= -OUTLIER_Z)
		{
			return "Deadlier than the model says: the fight model is too easy on this boss.";
		}
		if (z >= OUTLIER_Z)
		{
			return "Easier than the model says: the fight model is too hard on this boss.";
		}
		return null;
	}

	/** @return the figures as text, one line per boss: {@code id|name|level|attempts|wins|expected|members|kill|ratio|recent} where recent is {@code predicted:won} pairs, oldest first */
	public synchronized String encode()
	{
		final StringBuilder sb = new StringBuilder();
		for (Boss boss : _bosses.values())
		{
			sb.append(boss._id).append('|').append(boss._name).append('|').append(boss._level).append('|').append(boss._attempts).append('|').append(boss._wins).append('|').append(boss._expected).append('|').append(boss._members).append('|').append(boss._killSeconds).append('|').append(boss._ratio).append('|');
			boolean first = true;
			for (Result result : boss._recent)
			{
				sb.append(first ? "" : ",").append(result.predicted()).append(':').append(result.won() ? 1 : 0);
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
		_bosses.clear();
		if (text == null)
		{
			return;
		}
		for (String line : text.split("\n"))
		{
			try
			{
				final String[] part = line.split("\\|", -1);
				final Boss boss = new Boss(Integer.parseInt(part[0]));
				boss._name = part[1];
				boss._level = Integer.parseInt(part[2]);
				boss._attempts = Long.parseLong(part[3]);
				boss._wins = Long.parseLong(part[4]);
				boss._expected = Double.parseDouble(part[5]);
				boss._members = Long.parseLong(part[6]);
				boss._killSeconds = Double.parseDouble(part[7]);
				boss._ratio = Double.parseDouble(part[8]);
				if (!part[9].isEmpty())
				{
					for (String pair : part[9].split(","))
					{
						final String[] bits = pair.split(":");
						boss._recent.addLast(new Result(Double.parseDouble(bits[0]), "1".equals(bits[1])));
					}
				}
				while (boss._recent.size() > _window)
				{
					boss._recent.removeFirst();
				}
				boss._flagged = flagOf(boss) != null; // already known: no new alert for it after a restart
				_bosses.put(boss._id, boss);
			}
			catch (RuntimeException e)
			{
				// skip the line
			}
		}
	}

	/** @return the figures as JSON for the monitor: every boss fought, the most out of line first */
	public synchronized String toJson()
	{
		final StringBuilder sb = new StringBuilder("{\"outlierZ\":").append(OUTLIER_Z).append(",\"bosses\":[");
		boolean first = true;
		for (Line l : lines())
		{
			sb.append(first ? "" : ",").append("{\"id\":").append(l.id()).append(",\"name\":\"").append(l.name().replace("\\", "").replace("\"", "")).append("\",\"level\":").append(l.level()).append(",\"attempts\":").append(l.attempts()).append(",\"wins\":").append(l.wins())
				.append(",\"expectedWins\":").append(String.format(java.util.Locale.ROOT, "%.2f", l.expectedWins())).append(",\"avgPredicted\":").append(String.format(java.util.Locale.ROOT, "%.3f", l.avgPredicted())).append(",\"avgPartySize\":").append(String.format(java.util.Locale.ROOT, "%.1f", l.avgPartySize()))
				.append(",\"avgKillSeconds\":").append(String.format(java.util.Locale.ROOT, "%.0f", l.avgKillSeconds())).append(",\"avgRatio\":").append(String.format(java.util.Locale.ROOT, "%.2f", l.avgRatio())).append(",\"recentAttempts\":").append(l.recentAttempts()).append(",\"recentWins\":").append(l.recentWins())
				.append(",\"recentExpected\":").append(String.format(java.util.Locale.ROOT, "%.2f", l.recentExpected())).append(",\"z\":").append(String.format(java.util.Locale.ROOT, "%.2f", l.z())).append(",\"flag\":").append((l.flag() == null) ? "null" : ("\"" + l.flag() + "\"")).append('}');
			first = false;
		}
		return sb.append("]}").toString();
	}
}

/*
 * This file is part of the L2J Mobius project.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.l2jmobius.gameserver.managers;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.l2jmobius.gameserver.data.sql.ClanTable;
import org.l2jmobius.gameserver.model.actor.Creature;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.actor.instance.Defender;
import org.l2jmobius.gameserver.model.actor.instance.Door;
import org.l2jmobius.gameserver.model.WorldObject;
import org.l2jmobius.gameserver.model.clan.Clan;
import org.l2jmobius.gameserver.model.clan.ClanMember;
import org.l2jmobius.gameserver.model.siege.Castle;

/**
 * Test support for sieges with phantom parties (NPC-owned castle, attackers only).
 *
 * <p>
 * Three pieces, all off by default:
 * <ul>
 * <li>{@link #joinLeaderClan}: puts the phantoms of a real player's party into that player's clan as runtime-only
 * members (no database rows, no packets), so they are online clan members and siege registration, siege state flags
 * and attacker relations treat them like the leader.</li>
 * <li>{@link #start} / {@link #end}: one-call setup of a siege on a castle - attacker clans registered, clan members
 * flagged, doors and guards spawned - and teardown that clears everything again.</li>
 * <li>{@link #isSiegeTarget}: the target flag. While it is on AND the siege is running, phantom parties treat castle
 * guards ({@link Defender}) and castle doors as legal targets. The party code is typed to monsters, so this is the
 * single entry point it asks.</li>
 * </ul>
 */
public final class PhantomSiege
{
	/** Phantoms we put into a real clan, so teardown can take them out again: phantom object id to clan. */
	private static final Map<Integer, Clan> JOINED = new ConcurrentHashMap<>();
	
	private static volatile boolean _targetGuards = false;
	private static volatile Castle _castle = null;
	
	private PhantomSiege()
	{
	}
	
	/** @return {@code true} while the target flag is on. */
	public static boolean isTargetFlagOn()
	{
		return _targetGuards;
	}
	
	/**
	 * Turns the target flag on or off. It only has an effect while a siege started through {@link #start} is running.
	 * @param on the new state
	 */
	public static void setTargetFlag(boolean on)
	{
		_targetGuards = on;
	}
	
	/** @return the castle of the siege started through {@link #start}, or {@code null}. */
	public static Castle getCastle()
	{
		return _castle;
	}
	
	/**
	 * @param phantom the phantom asking
	 * @param object a candidate target
	 * @return {@code true} if the flag is on, the siege is running, and {@code object} is a live castle guard or door
	 *         the phantom may attack
	 */
	public static boolean isSiegeTarget(Player phantom, WorldObject object)
	{
		final Castle castle = _castle;
		if (!_targetGuards || (castle == null) || (phantom == null) || !(object instanceof Creature target))
		{
			return false;
		}
		if (!(target instanceof Defender) && !(target instanceof Door))
		{
			return false;
		}
		if (target.isDead() || !castle.getSiege().isInProgress() || !castle.getZone().isActive())
		{
			return false;
		}
		if ((target instanceof Door door) && (door.getCastle() != castle))
		{
			return false;
		}
		return target.isAutoAttackable(phantom); // the game's own answer: siege state, side, door flags
	}
	
	/** @return {@code true} while the flag is on and the test siege is running. */
	public static boolean isActive()
	{
		final Castle castle = _castle;
		return _targetGuards && (castle != null) && castle.getSiege().isInProgress() && castle.getZone().isActive();
	}
	
	/**
	 * Finds the nearest castle guard that is fighting the party: its most hated is the leader or a party member (or,
	 * with {@code onlyMe}, only {@code phantom} itself).
	 * @param phantom the phantom looking for something to defend against
	 * @param owner the party leader
	 * @param onlyMe {@code true} to count only guards on {@code phantom} itself
	 * @return the guard, or {@code null}
	 */
	public static Defender guardOnParty(Player phantom, Player owner, boolean onlyMe)
	{
		if (!isActive() || (owner == null) || phantom.isDead())
		{
			return null;
		}
		Defender best = null;
		double bestDistance = Double.MAX_VALUE;
		for (Defender guard : org.l2jmobius.gameserver.model.World.getInstance().getVisibleObjectsInRange(owner, Defender.class, 1500))
		{
			if (guard.isDead() || !(guard.getMostHated() instanceof Player victim))
			{
				continue;
			}
			final boolean hitsMe = victim == phantom;
			final boolean hitsParty = (victim == owner) || (owner.isInParty() && (victim.getParty() == owner.getParty()));
			if (!(hitsMe || (!onlyMe && hitsParty)) || !isSiegeTarget(phantom, guard))
			{
				continue;
			}
			final double distance = phantom.calculateDistance2D(guard);
			if (distance < bestDistance)
			{
				best = guard;
				bestDistance = distance;
			}
		}
		return best;
	}
	
	/**
	 * Puts every phantom in {@code leader}'s party into the leader's clan.
	 * @param leader a real player with a clan
	 * @return how many phantoms joined (0 if the leader has no clan or party)
	 */
	public static int joinLeaderClan(Player leader)
	{
		final Clan clan = leader.getClan();
		if ((clan == null) || !leader.isInParty())
		{
			return 0;
		}
		int joined = 0;
		for (Player member : new ArrayList<>(leader.getParty().getMembers()))
		{
			if ((member == leader) || !PhantomManager.getInstance().isPhantom(member))
			{
				continue;
			}
			if (member.getClan() == clan)
			{
				joined++;
				continue;
			}
			if (member.getClan() != null)
			{
				continue; // a bot-clan phantom keeps its flavour clan; party phantoms normally have none
			}
			clan.addBotMember(new ClanMember(clan, member));
			member.setClan(clan);
			JOINED.put(member.getObjectId(), clan);
			joined++;
		}
		return joined;
	}
	
	/** Removes every phantom this class put into a clan, and any runtime member whose player has gone. */
	public static void leaveClans()
	{
		for (Map.Entry<Integer, Clan> entry : JOINED.entrySet())
		{
			final Clan clan = entry.getValue();
			clan.removeBotMember(entry.getKey());
			final Player phantom = org.l2jmobius.gameserver.model.World.getInstance().getPlayer(entry.getKey());
			if ((phantom != null) && (phantom.getClan() == clan))
			{
				phantom.setClan(null);
				phantom.setSiegeState((byte) 0);
				phantom.setSiegeSide(0);
				phantom.setInSiege(false);
			}
		}
		JOINED.clear();
	}
	
	/**
	 * Starts a test siege: registers each clan as attacker, brings its phantom parties into it, starts the siege (doors
	 * and guards spawn) and turns the target flag on.
	 * @param castle the castle (NPC-owned expected)
	 * @param clans the attacker clans, each with at least one online real member
	 * @return a one-line result for the caller
	 */
	public static String start(Castle castle, List<Clan> clans)
	{
		if (castle.getSiege().isInProgress())
		{
			return castle.getName() + " is already under siege. Use .botsiege end first.";
		}
		castle.getSiege().clearSiegeClan(); // stale registrations from earlier tests
		int phantoms = 0;
		for (Clan clan : clans)
		{
			Player registrar = null;
			for (Player online : clan.getOnlineMembers(0))
			{
				if (!PhantomManager.getInstance().isPhantom(online))
				{
					registrar = online;
					phantoms += joinLeaderClan(online);
				}
			}
			if (registrar == null)
			{
				return "Clan " + clan.getName() + " has no real member online.";
			}
			castle.getSiege().registerAttacker(registrar, true);
		}
		castle.getSiege().startSiege();
		if (!castle.getSiege().isInProgress())
		{
			leaveClans();
			return "The siege did not start (no attacker clans registered?).";
		}
		castle.getSiege().updatePlayerSiegeStateFlags(false); // flag the phantoms that joined after load
		_castle = castle;
		_targetGuards = true;
		return "Siege of " + castle.getName() + " started: " + clans.size() + " attacker clan(s), " + phantoms + " phantom(s) joined, guards target flag ON.";
	}
	
	/** Ends the test siege, clears registrations, takes the phantoms out of the clans and turns the flag off. */
	public static String end()
	{
		final Castle castle = _castle;
		_targetGuards = false;
		_castle = null;
		if (castle == null)
		{
			leaveClans();
			return "No test siege is running.";
		}
		if (castle.getSiege().isInProgress())
		{
			castle.getSiege().endSiege();
		}
		castle.getSiege().clearSiegeClan();
		leaveClans();
		return "Siege of " + castle.getName() + " ended and cleared.";
	}
	
	/** @return the clan for {@code name}, or {@code null}. */
	public static Clan clan(String name)
	{
		return ClanTable.getInstance().getClanByName(name);
	}
}

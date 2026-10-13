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
package modules.botsiege;

import java.util.ArrayList;
import java.util.List;
import java.util.StringTokenizer;

import org.l2jmobius.gameserver.handler.IVoicedCommandHandler;
import org.l2jmobius.gameserver.managers.CastleManager;
import org.l2jmobius.gameserver.managers.PhantomSiege;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.clan.Clan;
import org.l2jmobius.gameserver.model.siege.Castle;
import org.l2jmobius.gameserver.modules.GameModule;
import org.l2jmobius.gameserver.modules.ModuleContext;

/**
 * GM test commands for sieges with phantom parties, as a module (NPC-owned castle, attackers only).
 * <ul>
 * <li>{@code .botclan}: the phantoms of your party join your clan.</li>
 * <li>{@code .botsiege <castle> <clan> [<clan2>]}: registers the clans as attackers, brings their phantom parties
 * into them, starts the siege (doors and guards spawn) and turns the guard/door target flag on.</li>
 * <li>{@code .botsiege guards on|off}: the target flag alone (phantom parties attack guards and doors).</li>
 * <li>{@code .botsiege end}: ends the siege, clears registrations, takes the phantoms out of the clans.</li>
 * </ul>
 */
public class BotSiegeModule implements GameModule
{
	@Override
	public void onEnable(ModuleContext context)
	{
		if (!context.config().getBoolean("Enabled", true))
		{
			return; // Switch off: register nothing, behave as stock.
		}
		
		context.handlers().registerVoicedCommand(new BotSiegeCommand());
		context.logging().info("Bot Siege module enabled, registered voiced commands .botsiege and .botclan");
	}
	
	private static class BotSiegeCommand implements IVoicedCommandHandler
	{
		private static final String[] VOICED_COMMANDS =
		{
			"botsiege",
			"botclan"
		};
	
		@Override
		public boolean onCommand(String command, Player activeChar, String params)
		{
			if (!activeChar.isGM())
			{
				return false;
			}
		
			if (command.equals("botclan"))
			{
				final int joined = PhantomSiege.joinLeaderClan(activeChar);
				activeChar.sendSysMessage(joined + " phantom(s) of your party are in your clan." + (activeChar.getClan() == null ? " You have no clan." : ""));
				return true;
			}
		
			final StringTokenizer st = new StringTokenizer(params == null ? "" : params);
			if (!st.hasMoreTokens())
			{
				activeChar.sendSysMessage("Usage: .botsiege <castle> <clan> [<clan2>] | guards on|off | end | status");
				return true;
			}
			final String first = st.nextToken();
			switch (first.toLowerCase())
			{
				case "end":
				{
					activeChar.sendSysMessage(PhantomSiege.end());
					return true;
				}
				case "guards":
				{
					final String state = st.hasMoreTokens() ? st.nextToken() : "";
					PhantomSiege.setTargetFlag(state.equalsIgnoreCase("on"));
					activeChar.sendSysMessage("Phantom guard/door targeting: " + (PhantomSiege.isTargetFlagOn() ? "ON" : "OFF") + (PhantomSiege.getCastle() == null ? " (only effective in a siege started with .botsiege)." : "."));
					return true;
				}
				case "status":
				{
					final Castle castle = PhantomSiege.getCastle();
					activeChar.sendSysMessage("Test siege: " + (castle == null ? "none" : castle.getName()) + ", guard targeting " + (PhantomSiege.isTargetFlagOn() ? "ON" : "OFF"));
					return true;
				}
				default:
				{
					break;
				}
			}
		
			final Castle castle = CastleManager.getInstance().getCastle(first);
			if (castle == null)
			{
				activeChar.sendSysMessage("Unknown castle: " + first);
				return true;
			}
			final List<Clan> clans = new ArrayList<>();
			while (st.hasMoreTokens())
			{
				final String name = st.nextToken();
				final Clan clan = PhantomSiege.clan(name);
				if (clan == null)
				{
					activeChar.sendSysMessage("Unknown clan: " + name);
					return true;
				}
				clans.add(clan);
			}
			if (clans.isEmpty() && (activeChar.getClan() != null))
			{
				clans.add(activeChar.getClan());
			}
			if (clans.isEmpty())
			{
				activeChar.sendSysMessage("Name at least one attacker clan.");
				return true;
			}
			activeChar.sendSysMessage(PhantomSiege.start(castle, clans));
			return true;
		}
	
		@Override
		public String[] getCommandList()
		{
			return VOICED_COMMANDS;
		}
	}
}

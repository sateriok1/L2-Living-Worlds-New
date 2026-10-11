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

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.l2jmobius.commons.database.DatabaseFactory;

/**
 * Loads and saves the {@link TownStock} listings in the module's own table (created by install.sql).
 */
public class TownStockDao
{
	private static final Logger LOGGER = Logger.getLogger(TownStockDao.class.getName());

	/**
	 * @param stock receives the saved rows
	 * @return false when the table could not be read (logged), so the stock is not mistaken for an empty one
	 */
	public boolean load(TownStock stock)
	{
		try (Connection con = DatabaseFactory.getConnection();
			PreparedStatement ps = con.prepareStatement("SELECT town,owner_id,item_id,quantity FROM living_town_listings");
			ResultSet rs = ps.executeQuery())
		{
			while (rs.next())
			{
				stock.add(rs.getString(1), rs.getLong(2), rs.getInt(3), rs.getLong(4));
			}
			stock.clean();
			return true;
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, "LivingPopulation: could not read the town stock: " + e.getMessage(), e);
			return false;
		}
	}

	/**
	 * Writes the listings that changed since the last save: a changed one is replaced with its current count, an emptied
	 * one is deleted (FPC-303: a sold or cleared listing must not come back on the next load). On a failure the changes
	 * stay marked for the next save.
	 * @param stock the stock to write
	 */
	public void save(TownStock stock)
	{
		final List<TownStock.Listing> changes = stock.changes();
		if (changes.isEmpty())
		{
			return;
		}
		try (Connection con = DatabaseFactory.getConnection();
			PreparedStatement replace = con.prepareStatement("REPLACE INTO living_town_listings (town,owner_id,item_id,quantity) VALUES (?,?,?,?)");
			PreparedStatement delete = con.prepareStatement("DELETE FROM living_town_listings WHERE town=? AND owner_id=? AND item_id=?"))
		{
			for (TownStock.Listing listing : changes)
			{
				if (listing.count() > 0)
				{
					replace.setString(1, listing.town());
					replace.setLong(2, listing.owner());
					replace.setInt(3, listing.itemId());
					replace.setLong(4, listing.count());
					replace.addBatch();
				}
				else
				{
					delete.setString(1, listing.town());
					delete.setLong(2, listing.owner());
					delete.setInt(3, listing.itemId());
					delete.addBatch();
				}
			}
			replace.executeBatch();
			delete.executeBatch();
		}
		catch (Exception e)
		{
			stock.unsaved(changes);
			LOGGER.log(Level.WARNING, "LivingPopulation: could not save the town stock: " + e.getMessage(), e);
		}
	}
}

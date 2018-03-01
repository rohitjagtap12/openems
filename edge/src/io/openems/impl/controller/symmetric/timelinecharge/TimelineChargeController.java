/*******************************************************************************
 * OpenEMS - Open Source Energy Management System
 * Copyright (c) 2016, 2017 FENECON GmbH and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 *
 * Contributors:
 *   FENECON GmbH - initial API and implementation and initial documentation
 *******************************************************************************/
package io.openems.impl.controller.symmetric.timelinecharge;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import io.openems.api.channel.ConfigChannel;
import io.openems.api.channel.thingstate.ThingStateChannels;
import io.openems.api.controller.Controller;
import io.openems.api.device.nature.ess.EssNature;
import io.openems.api.doc.ChannelInfo;
import io.openems.api.doc.ThingInfo;
import io.openems.api.exception.ConfigException;
import io.openems.api.exception.InvalidValueException;
import io.openems.api.exception.ReflectionException;
import io.openems.common.session.Role;
import io.openems.core.utilities.AvgFiFoQueue;
import io.openems.core.utilities.JsonUtils;
import io.openems.core.utilities.power.symmetric.PowerException;

@ThingInfo(title = "Timeline charge (Symmetric)")
public class TimelineChargeController extends Controller {

	private ThingStateChannels thingState = new ThingStateChannels(this);
	/*
	 * Constructors
	 */
	public TimelineChargeController() {
		super();
	}

	public TimelineChargeController(String thingId) {
		super(thingId);
	}

	/*
	 * Config
	 */
	@ChannelInfo(title = "Ess", description = "Sets the Ess device.", type = Ess.class)
	public final ConfigChannel<Ess> ess = new ConfigChannel<Ess>("ess", this);

	@ChannelInfo(title = "Grid-Meter", description = "Sets the grid meter.", type = Meter.class)
	public final ConfigChannel<Meter> meter = new ConfigChannel<>("meter", this);

	@ChannelInfo(title = "Max-ApparentPower", description = "How much apparent power the grid connection can take.", type = Long.class)
	public final ConfigChannel<Long> allowedApparent = new ConfigChannel<>("allowedApparent", this);

	@ChannelInfo(title = "Charger", description = "Sets the Chargers connected to the ess.", type = Charger.class, isArray = true)
	public final ConfigChannel<Set<Charger>> chargers = new ConfigChannel<Set<Charger>>("chargers", this);

	@ChannelInfo(title = "Monday", description = "Sets the soc limits for monday.", type = JsonArray.class, writeRoles = {
			Role.OWNER })
	public ConfigChannel<JsonArray> monday = new ConfigChannel<>("monday", this);

	@ChannelInfo(title = "Tuesday", description = "Sets the soc limits for tuesday.", type = JsonArray.class, writeRoles = {
			Role.OWNER })
	public ConfigChannel<JsonArray> tuesday = new ConfigChannel<>("tuesday", this);

	@ChannelInfo(title = "Wednesday", description = "Sets the soc limits for wednesday.", type = JsonArray.class, writeRoles = {
			Role.OWNER })
	public ConfigChannel<JsonArray> wednesday = new ConfigChannel<>("wednesday", this);

	@ChannelInfo(title = "Thursday", description = "Sets the soc limits for thursday.", type = JsonArray.class, writeRoles = {
			Role.OWNER })
	public ConfigChannel<JsonArray> thursday = new ConfigChannel<>("thursday", this);

	@ChannelInfo(title = "Friday", description = "Sets the soc limits for friday.", type = JsonArray.class, writeRoles = {
			Role.OWNER })
	public ConfigChannel<JsonArray> friday = new ConfigChannel<>("friday", this);

	@ChannelInfo(title = "Saturday", description = "Sets the soc limits for saturday.", type = JsonArray.class, writeRoles = {
			Role.OWNER })
	public ConfigChannel<JsonArray> saturday = new ConfigChannel<>("saturday", this);

	@ChannelInfo(title = "Sunday", description = "Sets the soc limits for sunday.", type = JsonArray.class, writeRoles = {
			Role.OWNER })
	public ConfigChannel<JsonArray> sunday = new ConfigChannel<>("sunday", this);

	/*
	 * Fields
	 */
	private AvgFiFoQueue floatingChargerPower = new AvgFiFoQueue(10, 1);
	private State currentState = State.NORMAL;

	public enum State {
		NORMAL, MINSOC, CHARGESOC
	}

	/*
	 * Methods
	 */
	@Override
	public void run() {
		try {
			// Check if all parameters are available
			Ess ess  = this.ess.value();
			Meter meter = this.meter.value();
			Set<Charger> chargers = this.chargers.value();
			long essCapacity = ess.capacity.value();
			long essSoc = ess.soc.value();

			// start controller logic
			if (ess.gridMode.labelOptional().equals(Optional.of(EssNature.ON_GRID))) {
				long pNull = (meter.activePower.value() - ess.activePower.value())*-1;
				long qNull = (meter.reactivePower.value() - ess.reactivePower.value())*-1;
				ess.maxApparentPower.setSMax(allowedApparent.value(), pNull, qNull);
				try {
					ess.power.applyLimitation(ess.maxApparentPower);
				} catch (PowerException e1) {
					log.error("Failed to set Power!",e1);
				}
				long chargerPower = 0L;
				for (Charger c : chargers) {
					try {
						chargerPower += c.power.value();
					} catch (InvalidValueException e) {
						log.error("TimelineChargeController error: Unable to read power from Charger [" + c.id() + "]: " + e.getMessage());
					}
				}
				floatingChargerPower.add(chargerPower);
				SocPoint socPoint = getSoc();
				double requiredEnergy = (essCapacity / 100.0 * socPoint.getSoc())
						- (essCapacity / 100.0 * essSoc);
				long requiredTimeCharger = (long) (requiredEnergy / (floatingChargerPower.avg() * 3600.0));
				// limit time to one day
				if(requiredTimeCharger > 60*60*24) {
					requiredTimeCharger = 60*60*24;
				}
				long requiredTimeGrid = (long) (requiredEnergy / ((floatingChargerPower.avg() + (ess.power.getMinP().orElse(0L)+ess.activePower.value())*-1)
						* 3600.0));
				// limit time to one day
				if(requiredTimeGrid > 60*60*24) {
					requiredTimeGrid = 60*60*24;
				}
				log.info("RequiredTimeCharger: " + requiredTimeCharger + ", RequiredTimeGrid: " + requiredTimeGrid);
				if (floatingChargerPower.avg() >= 1000
						&& !LocalDateTime.now().plusSeconds(requiredTimeCharger).isBefore(socPoint.getTime())
						&& LocalDateTime.now().plusSeconds(requiredTimeGrid).isBefore(socPoint.getTime())) {
					// Prevent discharge -> load with Pv
					ess.maxActivePowerlimit.setP(0L);
					try {
						ess.power.applyLimitation(ess.maxActivePowerlimit);
					} catch (PowerException e) {
						log.error("Failed to set Power [" + ess.id() + "]: ",e);
					}
				} else if (requiredTimeGrid > 0
						&& !LocalDateTime.now().plusSeconds(requiredTimeGrid).isBefore(socPoint.getTime())
						&& socPoint.getTime().isAfter(LocalDateTime.now())) {
					// Load with grid + pv
					ess.maxActivePowerlimit.setP(ess.power.getMinP().get());
					try {
						ess.power.applyLimitation(ess.maxActivePowerlimit);
					} catch (PowerException e) {
						log.error("Failed to set Power!",e);
					}
				} else {
					// soc point in the past -> Hold load
					int minSoc = getCurrentSoc().getSoc();
					int chargeSoc = minSoc - 5;
					if (chargeSoc <= 1) {
						chargeSoc = 1;
					}
					switch (currentState) {
					case CHARGESOC:
						if (essSoc > minSoc) {
							currentState = State.MINSOC;
						} else {
							try {
								ess.maxActivePowerlimit.setP(ess.maxNominalPower.valueOptional().orElse(-1000L));
								ess.power.applyLimitation(ess.maxActivePowerlimit);
							} catch (PowerException e) {
								log.error("Failed to set Power!",e);
							}
						}
						break;
					case MINSOC:
						if (essSoc < chargeSoc) {
							currentState = State.CHARGESOC;
						} else if (essSoc >= minSoc + 5) {
							currentState = State.NORMAL;
						} else {
							ess.maxActivePowerlimit.setP(0L);
							try {
								ess.power.applyLimitation(ess.maxActivePowerlimit);
							} catch (PowerException e) {
								log.error("Failed to set Power!",e);
							}
						}
						break;
					case NORMAL:
						if (essSoc <= minSoc) {
							currentState = State.MINSOC;
						}
						break;
					}
				}
			}
		} catch (InvalidValueException | NullPointerException e) {
			log.error("TimelineChargeController error: " + e.getMessage());
			return;
		}
	}

	private JsonArray getJsonOfDay(DayOfWeek day) throws InvalidValueException {
		switch (day) {
		case FRIDAY:
			return friday.value();
		case SATURDAY:
			return saturday.value();
		case SUNDAY:
			return sunday.value();
		case THURSDAY:
			return thursday.value();
		case TUESDAY:
			return tuesday.value();
		case WEDNESDAY:
			return wednesday.value();
		default:
		case MONDAY:
			return monday.value();
		}
	}

	private SocPoint getCurrentSoc() {
		SocPoint soc = null;
		JsonArray jHours;
		try {
			jHours = getJsonOfDay(LocalDate.now().getDayOfWeek());
			LocalTime time = LocalTime.now();
			int count = 1;
			while (soc == null && count < 8) {
				try {
					Entry<LocalTime, Integer> entry = floorSoc(jHours, time);
					soc = new SocPoint(LocalDateTime.of(LocalDate.now().minusDays(count), entry.getKey()),
							entry.getValue());
				} catch (IndexOutOfBoundsException e) {
					time = LocalTime.MIN;
					jHours = getJsonOfDay(LocalDate.now().getDayOfWeek().minus(count));
				}
				count++;
			}
		} catch (InvalidValueException | ConfigException e1) {
			log.error("failed to find soc", e1);
		}
		if (soc == null) {
			soc = new SocPoint(LocalDateTime.MIN, 10);
		}
		return soc;
	}

	private SocPoint getSoc() {
		SocPoint soc = null;
		JsonArray jHours;
		try {
			jHours = getJsonOfDay(LocalDate.now().getDayOfWeek());
			LocalTime time = LocalTime.now();
			int count = 1;
			while (soc == null && count < 8) {
				try {
					Entry<LocalTime, Integer> entry = higherSoc(jHours, time);
					soc = new SocPoint(LocalDateTime.of(LocalDate.now().plusDays(count - 1), entry.getKey()),
							entry.getValue());
				} catch (IndexOutOfBoundsException e) {
					time = LocalTime.MIN;
					jHours = getJsonOfDay(LocalDate.now().getDayOfWeek().plus(count));
				}
				count++;
			}
		} catch (InvalidValueException | ConfigException e1) {
			log.error("failed to find soc", e1);
		}
		if (soc == null) {
			soc = new SocPoint(LocalDateTime.MIN, 10);
		}
		return soc;
	}

	private Entry<LocalTime, Integer> floorSoc(JsonArray jHours, LocalTime time) throws ConfigException {
		try {
			// fill times map; sorted by hour
			TreeMap<LocalTime, Integer> times = new TreeMap<>();
			for (JsonElement jHourElement : jHours) {
				JsonObject jHour = JsonUtils.getAsJsonObject(jHourElement);
				String hourTime = JsonUtils.getAsString(jHour, "time");
				int jsoc = JsonUtils.getAsInt(jHourElement, "soc");
				times.put(LocalTime.parse(hourTime), jsoc);
			}
			// return matching controllers
			if (times.floorEntry(time) != null) {
				return times.floorEntry(time);
			} else {
				throw new IndexOutOfBoundsException("No smaller time found");
			}
		} catch (ReflectionException e) {
			throw new ConfigException("cant read config", e);
		}
	}

	private Map.Entry<LocalTime, Integer> higherSoc(JsonArray jHours, LocalTime time) throws ConfigException {
		// fill times map; sorted by hour
		try {
			TreeMap<LocalTime, Integer> times = new TreeMap<>();
			for (JsonElement jHourElement : jHours) {
				JsonObject jHour = JsonUtils.getAsJsonObject(jHourElement);
				String hourTime = JsonUtils.getAsString(jHour, "time");
				int jsoc = JsonUtils.getAsInt(jHourElement, "soc");
				times.put(LocalTime.parse(hourTime), jsoc);
			}
			// return matching controllers
			if (times.higherEntry(time) != null) {
				return times.higherEntry(time);
			} else {
				throw new IndexOutOfBoundsException("No smaller time found");
			}
		} catch (ReflectionException e) {
			throw new ConfigException("cant read config", e);
		}
	}

	private class SocPoint {
		private final LocalDateTime time;
		private final int soc;

		public SocPoint(java.time.LocalDateTime time, int soc) {
			super();
			this.time = time;
			this.soc = soc;
		}

		public LocalDateTime getTime() {
			return time;
		}

		public int getSoc() {
			return soc;
		}

	}

	@Override
	public ThingStateChannels getStateChannel() {
		return this.thingState;
	}

}

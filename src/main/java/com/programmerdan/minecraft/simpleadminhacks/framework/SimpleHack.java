package com.programmerdan.minecraft.simpleadminhacks.framework;

import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.programmerdan.minecraft.simpleadminhacks.SimpleAdminHacks;
import com.programmerdan.minecraft.simpleadminhacks.framework.autoload.AutoLoad;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.util.List;
import java.util.stream.Collectors;
import org.apache.commons.lang.reflect.FieldUtils;
import org.bukkit.ChatColor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.util.NumberConversions;
import vg.civcraft.mc.civmodcore.util.NullCoalescing;
import vg.civcraft.mc.civmodcore.util.TextUtil;

/**
 * Baseline structure for a managed hack.
 *
 * @author ProgrammerDan
 */
public abstract class SimpleHack<T extends SimpleHackConfig> {

	protected final SimpleAdminHacks plugin;

	protected final T config;

	private boolean enabled;

	public SimpleHack(final SimpleAdminHacks plugin, final T config) {
		Preconditions.checkNotNull(plugin, "Plugin cannot be null!");
		Preconditions.checkNotNull(config, "Config cannot be null!");
		this.plugin = plugin;
		this.config = config;
	}

	public final SimpleAdminHacks plugin() {
		return this.plugin;
	}

	public final T config() {
		return this.config;
	}

	public final String getName() {
		return getClass().getSimpleName();
	}

	public boolean isEnabled() {
		return this.enabled;
	}

	/**
	 * @return Returns true if this hack should be enabled automatically due to its `enabled` config option.
	 */
	public boolean shouldEnable() {
		return this.config.isEnabled();
	}

	public final void enable() {
		this.enabled = true;
		populateAutoLoadFields();
		onEnable();
		dataBootstrap();
		registerCommands();
		registerListeners();
		this.plugin.info(ChatColor.AQUA + getName() + " enabled!");
	}

	public final void disable() {
		unregisterListeners();
		unregisterCommands();
		dataCleanup();
		onDisable();
		this.enabled = false;
		this.plugin.info(ChatColor.AQUA + getName() + " disabled!");
	}

	/**
	 * Called when this hack is loaded.
	 */
	public void onLoad() { }

	/**
	 * Called when this hack is enabled.
	 */
	public void onEnable() { }

	/**
	 * Called when this hack is disabled.
	 */
	public void onDisable() {

	}

	/**
	 * @deprecated Use {@link #onEnable()} instead.
	 */
	@Deprecated
	public void registerListeners() {}

	/**
	 * @deprecated Use {@link #onEnable()} instead.
	 */
	@Deprecated
	public void registerCommands() {}

	/**
	 * @deprecated Use {@link #onEnable()} instead.
	 */
	@Deprecated
	public void dataBootstrap() {}

	/**
	 * @deprecated Use {@link #onDisable()} instead.
	 */
	@Deprecated
	public void unregisterListeners() {}

	/**
	 * @deprecated Use {@link #onDisable()} instead.
	 */
	@Deprecated
	public void unregisterCommands() {}

	/**
	 * @deprecated Use {@link #onDisable()} instead.
	 */
	@Deprecated
	public void dataCleanup() {}

	/**
	 * Not optional; customized status for this SimpleHack for display in CnC
	 */
	public String status() {
		final StringBuilder genStatus = new StringBuilder();
		genStatus.append(this.getClass().getSimpleName());
		genStatus.append(" is ");
		if (!isEnabled()) {
			genStatus.append("disabled");
			return genStatus.toString();
		}
		genStatus.append("enabled\n");
		for(final Field field : getClass().getDeclaredFields()) {
			genStatus.append(field.getName());
			genStatus.append(" = ");
			field.setAccessible(true);
			try {
				genStatus.append(field.get(this));
			}
			catch (final IllegalArgumentException | IllegalAccessException exception) {
				plugin().warning("Failed to read field", exception);
			}
			genStatus.append('\n');
		}
		return genStatus.toString();
	}

	/**
	 * Simple name equality. Don't have two hacks with the same name please.
	 */
	@Override
	public boolean equals(final Object object) {
		if (object instanceof SimpleHack) {
			final SimpleHack<?> other = (SimpleHack<?>) object;
			if (TextUtil.stringEquals(getName(), other.getName())) {
				return true;
			}
		}
		return false;
	}

	private void populateAutoLoadFields() {
		final Class<?> hackClass = getClass();
		final ConfigurationSection config = config().getBase();
		for (final Field field : hackClass.getDeclaredFields()) {
			final AutoLoad autoLoad = field.getAnnotation(AutoLoad.class);
			if (autoLoad == null) {
				continue;
			}
			final String hackName = getClass().getSimpleName();
			final String identifier = Strings.isNullOrEmpty(autoLoad.id()) ? field.getName() : autoLoad.id();
			// Value type
			Class<?> clazz;
			if (field.getType().getName().split("\\.").length == 1) { // TODO: Is this really necessary?
				clazz = Array.get(Array.newInstance(field.getType(), 1), 0).getClass(); // unwrap primitives
			}
			else {
				clazz = field.getType();
			}
			// Value itself
			Object value;
			try {
				// Handle primitives (ints, booleans, and doubles are handled just fine already)
				if (byte.class.isAssignableFrom(clazz) || Byte.class.isAssignableFrom(clazz)) {
					value = NumberConversions.toByte(config.getInt(identifier));
				}
				else if (short.class.isAssignableFrom(clazz) || Short.class.isAssignableFrom(clazz)) {
					value = NumberConversions.toShort(config.getInt(identifier));
				}
				else if (long.class.isAssignableFrom(clazz) || Long.class.isAssignableFrom(clazz)) {
					value = config.getLong(identifier);
				}
				else if (float.class.isAssignableFrom(clazz) || Float.class.isAssignableFrom(clazz)) {
					value = NumberConversions.toFloat(config.getDouble(identifier));
				}
				else if (char.class.isAssignableFrom(clazz) || Character.class.isAssignableFrom(clazz)) {
					final String temp = config.getString(identifier);
					value = Strings.isNullOrEmpty(temp) ? 0 : temp.toCharArray()[0];
				}
				else {
					value = config.getObject(identifier, clazz, null);
				}
				if (value == null) {
					// do nothing
				}
				else if (List.class.isAssignableFrom(clazz)) {
					value = ((List<?>) value).stream()
							.map(autoLoad.processor()::parse)
							.collect(Collectors.toList());
				}
				// TODO: Create a map parser
				// else if (Map.class.isAssignableFrom(clazz)) { }
				else {
					value = autoLoad.processor().parse(value);
				}
			}
			catch (final Exception exception) {
				throw new IllegalArgumentException("Hack \"" + hackName + "\" failed to read parameter \"" +
						identifier + "\"", exception);
			}
			if (value == null) {
				this.plugin.warning("Hack \"" + hackName + " has no value for field \"" + identifier + "\"");
				continue;
			}
			try {
				FieldUtils.writeField(field, this, value, true);
			}
			catch (final IllegalArgumentException | IllegalAccessException exception) {
				throw new IllegalStateException("\"" + clazz.getSimpleName() + "\" in \"" + hackName + "\" could " +
						"not be set.", exception);
			}
			this.plugin.info("Loaded \"" + identifier + "\" = \"" + value + "\"");
		}
	}

}


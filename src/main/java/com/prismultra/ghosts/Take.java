package com.prismultra.ghosts;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/** One recorded performance: a frame per server tick plus equipment changes. */
public final class Take {
	/** Equipment slots copied onto the ghost, in save order. */
	public static final EquipmentSlot[] SLOTS = {
		EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND,
		EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
	};
	/** Poses a mannequin can show. Index is what gets saved. */
	public static final String[] POSES = {"standing", "crouching", "swimming", "fall_flying", "sleeping"};

	/** swing: 0 = none, 1 = main hand, 2 = off hand. */
	public record Frame(double x, double y, double z, float yaw, float pitch, float headYaw, float bodyYaw, int pose, int swing) {}

	public final String name;
	/** Player name whose online skin the ghost wears (used when skinTexture is null). */
	public String skinName;
	/** Resource-pack texture id for the skin, e.g. "hollowfall:skin/mossie". Overrides skinName. */
	public String skinTexture;
	public boolean slim;
	/** Name tag shown above the ghost. Empty = no name tag. */
	public String displayName;
	public final List<Frame> frames = new ArrayList<>();
	/** tick -> full equipment snapshot (only stored on the ticks it changed). */
	public final TreeMap<Integer, ItemStack[]> equipment = new TreeMap<>();

	public Take(String name) {
		this.name = name;
	}

	public ItemStack[] equipmentAt(int tick) {
		Map.Entry<Integer, ItemStack[]> e = equipment.floorEntry(tick);
		return e == null ? null : e.getValue();
	}

	public String seconds() {
		int s = frames.size() / 20;
		return (s / 60) + ":" + String.format("%02d", s % 60);
	}

	// ---- saving -------------------------------------------------------------------------------

	public void save(Path file, HolderLookup.Provider registries) throws IOException {
		DynamicOps<JsonElement> ops = registries.createSerializationContext(JsonOps.INSTANCE);
		JsonObject root = new JsonObject();
		root.addProperty("version", 1);
		root.addProperty("name", name);
		if (skinName != null) root.addProperty("skinName", skinName);
		if (skinTexture != null) root.addProperty("skinTexture", skinTexture);
		root.addProperty("slim", slim);
		if (displayName != null) root.addProperty("displayName", displayName);

		JsonArray fr = new JsonArray();
		for (Frame f : frames) {
			JsonArray a = new JsonArray();
			a.add(round(f.x()));
			a.add(round(f.y()));
			a.add(round(f.z()));
			a.add(round(f.yaw()));
			a.add(round(f.pitch()));
			a.add(round(f.headYaw()));
			a.add(round(f.bodyYaw()));
			a.add(f.pose());
			a.add(f.swing());
			fr.add(a);
		}
		root.add("frames", fr);

		JsonArray eq = new JsonArray();
		for (Map.Entry<Integer, ItemStack[]> e : equipment.entrySet()) {
			JsonObject o = new JsonObject();
			o.addProperty("t", e.getKey());
			JsonArray items = new JsonArray();
			for (ItemStack s : e.getValue()) {
				items.add(ItemStack.OPTIONAL_CODEC.encodeStart(ops, s).result().orElse(JsonNull.INSTANCE));
			}
			o.add("items", items);
			eq.add(o);
		}
		root.add("equipment", eq);

		Files.createDirectories(file.getParent());
		Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
		try (Writer w = new OutputStreamWriter(new GZIPOutputStream(Files.newOutputStream(tmp)), StandardCharsets.UTF_8)) {
			w.write(root.toString());
		}
		Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
	}

	public static Take load(Path file, HolderLookup.Provider registries) throws IOException {
		DynamicOps<JsonElement> ops = registries.createSerializationContext(JsonOps.INSTANCE);
		JsonObject root;
		try (Reader r = new InputStreamReader(new GZIPInputStream(Files.newInputStream(file)), StandardCharsets.UTF_8)) {
			root = JsonParser.parseReader(r).getAsJsonObject();
		}
		Take t = new Take(root.get("name").getAsString());
		if (root.has("skinName")) t.skinName = root.get("skinName").getAsString();
		if (root.has("skinTexture")) t.skinTexture = root.get("skinTexture").getAsString();
		if (root.has("slim")) t.slim = root.get("slim").getAsBoolean();
		if (root.has("displayName")) t.displayName = root.get("displayName").getAsString();

		for (JsonElement el : root.getAsJsonArray("frames")) {
			JsonArray a = el.getAsJsonArray();
			t.frames.add(new Frame(
				a.get(0).getAsDouble(), a.get(1).getAsDouble(), a.get(2).getAsDouble(),
				a.get(3).getAsFloat(), a.get(4).getAsFloat(), a.get(5).getAsFloat(), a.get(6).getAsFloat(),
				a.get(7).getAsInt(), a.get(8).getAsInt()));
		}
		for (JsonElement el : root.getAsJsonArray("equipment")) {
			JsonObject o = el.getAsJsonObject();
			JsonArray items = o.getAsJsonArray("items");
			ItemStack[] stacks = new ItemStack[SLOTS.length];
			for (int i = 0; i < SLOTS.length; i++) {
				JsonElement it = i < items.size() ? items.get(i) : JsonNull.INSTANCE;
				stacks[i] = it.isJsonNull() ? ItemStack.EMPTY
					: ItemStack.OPTIONAL_CODEC.parse(ops, it).result().orElse(ItemStack.EMPTY);
			}
			t.equipment.put(o.get("t").getAsInt(), stacks);
		}
		return t;
	}

	private static double round(double v) {
		return Math.round(v * 10000.0) / 10000.0;
	}
}

package com.prismultra.ghosts;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Hollowfall Ghosts: record yourself, replay the take as a mannequin wearing a character's skin,
 * and act against it while recording the next character. Flashback captures all of it at once.
 *
 * Commands live under /ghost. Everything runs on the logical server, so it works in singleplayer.
 */
public class GhostsMod implements ModInitializer {
	public static final String MOD_ID = "hollowfall_ghosts";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
	static final String GHOST_TAG = "hf_ghost";

	static final Map<String, Take> CACHE = new HashMap<>();
	static final List<Playback> PLAYBACKS = new ArrayList<>();
	static Recording recording;

	static Runnable pending;
	static int countdownTicks;
	static UUID countdownViewer;

	static int countdownSeconds = 3;
	static boolean hits = true;

	@Override
	public void onInitialize() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> register(dispatcher));
		ServerTickEvents.END_SERVER_TICK.register(GhostsMod::tick);
		ServerLifecycleEvents.SERVER_STARTING.register(server -> {
			CACHE.clear();
			PLAYBACKS.clear();
			recording = null;
			pending = null;
		});
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> stopAll(server));
		// Attacks always swing the arm; count them in case the swing itself was missed.
		AttackEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
			if (recording != null && player.getUUID().equals(recording.player)) {
				recording.attackThisTick = true;
			}
			return InteractionResult.PASS;
		});
		LOGGER.info("Hollowfall Ghosts ready: /ghost");
	}

	// ============================================================================================
	// Tick
	// ============================================================================================

	static void tick(MinecraftServer server) {
		if (pending != null) {
			if (countdownTicks > 0 && countdownTicks % 20 == 0) {
				actionbar(server, countdownViewer, "Starting in " + (countdownTicks / 20) + "...", "gold");
			}
			countdownTicks--;
			if (countdownTicks <= 0) {
				Runnable r = pending;
				pending = null;
				actionbar(server, countdownViewer, "ACTION", "green");
				r.run();
			}
			return;
		}
		for (Playback p : List.copyOf(PLAYBACKS)) {
			p.step(server);
		}
		if (recording != null) {
			recording.step(server);
		}
	}

	/** Records one player, one frame per tick. */
	static final class Recording {
		final UUID player;
		final Take take;
		Object lastSwing;
		boolean attackThisTick;
		ItemStack[] lastEquip;

		Recording(UUID player, Take take) {
			this.player = player;
			this.take = take;
		}

		void step(MinecraftServer server) {
			ServerPlayer p = server.getPlayerList().getPlayer(player);
			if (p == null) {
				finishRecording(server);
				return;
			}
			int tick = take.frames.size();

			// 26.x keeps the current swing as an object; a new object means a new swing.
			Object current = p.isSwinging() ? p.getCurrentSwing() : null;
			boolean newSwing = current != null && current != lastSwing;
			lastSwing = current;
			int swing = 0;
			if (newSwing) {
				swing = 1;
			} else if (attackThisTick) {
				swing = 1;
			}
			attackThisTick = false;

			take.frames.add(new Take.Frame(
				p.getX(), p.getY(), p.getZ(),
				p.getYRot(), p.getXRot(), p.getYHeadRot(), p.yBodyRot,
				poseIndex(p.getPose()), swing));

			ItemStack[] now = new ItemStack[Take.SLOTS.length];
			boolean changed = lastEquip == null;
			for (int i = 0; i < Take.SLOTS.length; i++) {
				now[i] = p.getItemBySlot(Take.SLOTS[i]).copy();
				if (!changed && !ItemStack.matches(now[i], lastEquip[i])) changed = true;
			}
			if (changed) {
				take.equipment.put(tick, now);
				lastEquip = now;
			}

			if (tick % 10 == 0) {
				actionbar(server, player, "● REC  " + take.name + "  " + take.seconds(), "red");
			}
		}
	}

	/** Drives one mannequin through a take. */
	static final class Playback {
		final Take take;
		final UUID ghost;
		final ServerLevel level;
		int tick;
		int lastPose = -1;

		Playback(Take take, UUID ghost, ServerLevel level) {
			this.take = take;
			this.ghost = ghost;
			this.level = level;
		}

		void step(MinecraftServer server) {
			int n = take.frames.size();
			Entity e = level.getEntity(ghost);
			if (n == 0 || !(e instanceof LivingEntity g)) {
				tick++;
				return;
			}
			Take.Frame f = take.frames.get(Math.min(tick, n - 1));
			g.setPos(f.x(), f.y(), f.z());
			g.setYRot(f.yaw());
			g.setXRot(f.pitch());
			g.setYHeadRot(f.headYaw());
			g.setYBodyRot(f.bodyYaw());
			g.setDeltaMovement(Vec3.ZERO);
			if (g.getHealth() < g.getMaxHealth()) {
				g.setHealth(g.getMaxHealth());
			}

			if (tick < n) {
				ItemStack[] eq = take.equipment.get(tick);
				if (eq != null) {
					for (int i = 0; i < Take.SLOTS.length; i++) {
						g.setItemSlot(Take.SLOTS[i], eq[i].copy());
					}
				}
				if (f.pose() != lastPose) {
					lastPose = f.pose();
					run(server, level, "data merge entity " + ghost + " {pose:\"" + Take.POSES[lastPose] + "\"}");
				}
				if (f.swing() != 0) {
					run(server, level, "swing " + ghost + (f.swing() == 2 ? " offhand" : " mainhand"));
					if (hits && f.swing() == 1) {
						tryHit(server, level, f);
					}
				}
			}
			tick++;
		}

		/** A replayed swing that lines up with a live player hurts them (flash + knockback), then heals it back. */
		void tryHit(MinecraftServer server, ServerLevel level, Take.Frame f) {
			double yaw = Math.toRadians(f.headYaw());
			double pitch = Math.toRadians(f.pitch());
			double lx = -Math.sin(yaw) * Math.cos(pitch);
			double ly = -Math.sin(pitch);
			double lz = Math.cos(yaw) * Math.cos(pitch);
			double ex = f.x(), ey = f.y() + (Take.POSES[f.pose()].equals("crouching") ? 1.27 : 1.62), ez = f.z();

			ServerPlayer best = null;
			double bestDist = 3.6;
			for (ServerPlayer p : server.getPlayerList().getPlayers()) {
				if (p.level() != level || p.isSpectator() || !p.isAlive()) continue;
				double dx = p.getX() - ex, dy = (p.getY() + p.getBbHeight() * 0.5) - ey, dz = p.getZ() - ez;
				double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
				if (dist > bestDist || dist < 1.0e-4) continue;
				double cos = (dx * lx + dy * ly + dz * lz) / dist;
				if (cos < 0.55) continue; // roughly a 56 degree cone in front of the ghost
				best = p;
				bestDist = dist;
			}
			if (best == null) return;
			float before = best.getHealth();
			run(server, level, "damage " + best.getUUID() + " 1 minecraft:mob_attack by " + ghost);
			if (best.isAlive() && best.getHealth() < before) {
				best.setHealth(before);
			}
		}
	}

	static int poseIndex(Pose pose) {
		return switch (pose.name()) {
			case "CROUCHING" -> 1;
			case "SWIMMING" -> 2;
			case "FALL_FLYING" -> 3;
			case "SLEEPING" -> 4;
			default -> 0;
		};
	}

	// ============================================================================================
	// Start / stop
	// ============================================================================================

	static void begin(ServerPlayer viewer, Runnable action) {
		if (countdownSeconds <= 0) {
			action.run();
			return;
		}
		pending = action;
		countdownTicks = countdownSeconds * 20;
		countdownViewer = viewer.getUUID();
	}

	static Playback spawn(MinecraftServer server, ServerLevel level, Take t) {
		UUID id = UUID.randomUUID();
		Take.Frame f = t.frames.get(0);
		StringBuilder nbt = new StringBuilder("{");
		nbt.append("UUID:").append(uuidArray(id));
		nbt.append(",NoGravity:1b,hide_description:1b");
		nbt.append(",Tags:[\"").append(GHOST_TAG).append("\"]");
		nbt.append(",attributes:[{id:\"minecraft:max_health\",base:1024.0d}],Health:1024.0f");
		if (t.skinTexture != null) {
			nbt.append(",profile:{texture:").append(q(t.skinTexture));
			if (t.slim) nbt.append(",model:\"slim\"");
			nbt.append("}");
		} else if (t.skinName != null) {
			nbt.append(",profile:{name:").append(q(t.skinName)).append("}");
		}
		if (t.displayName != null && !t.displayName.isEmpty()) {
			nbt.append(",CustomName:").append(q(t.displayName)).append(",CustomNameVisible:1b");
		}
		nbt.append("}");
		run(server, level, String.format(Locale.ROOT, "summon minecraft:mannequin %.4f %.4f %.4f %s",
			f.x(), f.y(), f.z(), nbt));
		if (level.getEntity(id) == null) {
			LOGGER.warn("Ghost for take {} did not spawn. Command NBT: {}", t.name, nbt);
		}
		Playback p = new Playback(t, id, level);
		PLAYBACKS.add(p);
		return p;
	}

	static Take finishRecording(MinecraftServer server) {
		Recording r = recording;
		recording = null;
		if (r == null) return null;
		Take t = r.take;
		try {
			t.save(takeFile(server, t.name), server.registryAccess());
			CACHE.put(t.name, t);
		} catch (IOException ex) {
			LOGGER.error("Could not save take {}", t.name, ex);
		}
		ServerPlayer p = server.getPlayerList().getPlayer(r.player);
		if (p != null) {
			actionbar(server, r.player, "Saved " + t.name + " (" + t.seconds() + ")", "green");
		}
		return t;
	}

	static void removeGhosts(MinecraftServer server) {
		for (Playback p : PLAYBACKS) {
			Entity e = p.level.getEntity(p.ghost);
			if (e != null) {
				e.discard();
			}
		}
		PLAYBACKS.clear();
	}

	static void stopAll(MinecraftServer server) {
		pending = null;
		finishRecording(server);
		removeGhosts(server);
	}

	// ============================================================================================
	// Takes on disk
	// ============================================================================================

	static Path takesDir(MinecraftServer server) {
		return server.getWorldPath(LevelResource.ROOT).resolve("hollowfall_ghosts");
	}

	static Path takeFile(MinecraftServer server, String name) {
		return takesDir(server).resolve(name + ".json.gz");
	}

	static Take getTake(MinecraftServer server, String name) {
		Take t = CACHE.get(name);
		if (t != null) return t;
		Path file = takeFile(server, name);
		if (!Files.exists(file)) return null;
		try {
			t = Take.load(file, server.registryAccess());
			CACHE.put(name, t);
			return t;
		} catch (IOException | RuntimeException ex) {
			LOGGER.error("Could not load take {}", name, ex);
			return null;
		}
	}

	static List<String> listTakes(MinecraftServer server) {
		Path dir = takesDir(server);
		if (!Files.isDirectory(dir)) return List.of();
		try (Stream<Path> s = Files.list(dir)) {
			return s.map(p -> p.getFileName().toString())
				.filter(n -> n.endsWith(".json.gz"))
				.map(n -> n.substring(0, n.length() - ".json.gz".length()))
				.sorted()
				.toList();
		} catch (IOException ex) {
			return List.of();
		}
	}

	static void saveQuietly(MinecraftServer server, Take t) {
		try {
			t.save(takeFile(server, t.name), server.registryAccess());
		} catch (IOException ex) {
			LOGGER.error("Could not save take {}", t.name, ex);
		}
	}

	static boolean validName(String s) {
		return s.matches("[a-z0-9_\\-]{1,48}");
	}

	// ============================================================================================
	// Commands
	// ============================================================================================

	static final SuggestionProvider<CommandSourceStack> TAKES =
		(ctx, b) -> SharedSuggestionProvider.suggest(listTakes(ctx.getSource().getServer()), b);

	static void register(CommandDispatcher<CommandSourceStack> d) {
		d.register(Commands.literal("ghost")
			.then(Commands.literal("record")
				.then(Commands.argument("take", StringArgumentType.word()).suggests(TAKES)
					.executes(ctx -> record(ctx, StringArgumentType.getString(ctx, "take"), ""))
					.then(Commands.literal("with")
						.then(Commands.argument("others", StringArgumentType.greedyString()).suggests(TAKES)
							.executes(ctx -> record(ctx, StringArgumentType.getString(ctx, "take"),
								StringArgumentType.getString(ctx, "others")))))))
			.then(Commands.literal("play")
				.then(Commands.argument("takes", StringArgumentType.greedyString()).suggests(TAKES)
					.executes(ctx -> play(ctx, StringArgumentType.getString(ctx, "takes")))))
			.then(Commands.literal("stop").executes(GhostsMod::stop))
			.then(Commands.literal("list").executes(GhostsMod::list))
			.then(Commands.literal("delete")
				.then(Commands.argument("take", StringArgumentType.word()).suggests(TAKES)
					.executes(GhostsMod::delete)))
			.then(Commands.literal("skin")
				.then(Commands.argument("take", StringArgumentType.word()).suggests(TAKES)
					.then(Commands.literal("player")
						.then(Commands.argument("name", StringArgumentType.word())
							.executes(ctx -> skin(ctx, StringArgumentType.getString(ctx, "name"), null))))
					.then(Commands.literal("texture")
						.then(Commands.argument("id", StringArgumentType.greedyString())
							.executes(ctx -> skin(ctx, null, StringArgumentType.getString(ctx, "id")))))
					.then(Commands.literal("slim")
						.then(Commands.argument("slim", BoolArgumentType.bool())
							.executes(GhostsMod::slim)))))
			.then(Commands.literal("name")
				.then(Commands.argument("take", StringArgumentType.word()).suggests(TAKES)
					.then(Commands.literal("none").executes(ctx -> name(ctx, "")))
					.then(Commands.literal("set")
						.then(Commands.argument("text", StringArgumentType.greedyString())
							.executes(ctx -> name(ctx, StringArgumentType.getString(ctx, "text")))))))
			.then(Commands.literal("hits")
				.then(Commands.argument("on", BoolArgumentType.bool())
					.executes(ctx -> {
						hits = BoolArgumentType.getBool(ctx, "on");
						ok(ctx, "Ghost hits " + (hits ? "on: replayed swings hurt (flash + knockback, no real damage)." : "off."));
						return 1;
					})))
			.then(Commands.literal("countdown")
				.then(Commands.argument("seconds", IntegerArgumentType.integer(0, 10))
					.executes(ctx -> {
						countdownSeconds = IntegerArgumentType.getInteger(ctx, "seconds");
						ok(ctx, "Countdown set to " + countdownSeconds + "s.");
						return 1;
					})))
			.then(Commands.literal("clear").executes(ctx -> {
				MinecraftServer server = ctx.getSource().getServer();
				stopAll(server);
				for (ServerLevel level : server.getAllLevels()) {
					run(server, level, "kill @e[type=minecraft:mannequin,tag=" + GHOST_TAG + "]");
				}
				ok(ctx, "Stopped everything and removed all ghosts.");
				return 1;
			})));
	}

	static int record(CommandContext<CommandSourceStack> ctx, String name, String others) throws CommandSyntaxException {
		CommandSourceStack src = ctx.getSource();
		ServerPlayer player = src.getPlayerOrException();
		MinecraftServer server = src.getServer();
		if (!validName(name)) return fail(ctx, "Take names use lowercase letters, numbers, _ and - only.");
		if (recording != null || pending != null) return fail(ctx, "Already recording. Use /ghost stop first.");

		List<Take> partners = new ArrayList<>();
		for (String o : others.trim().split("\\s+")) {
			if (o.isEmpty()) continue;
			if (o.equals(name)) return fail(ctx, "You can't play " + o + " while re-recording it.");
			Take t = getTake(server, o);
			if (t == null || t.frames.isEmpty()) return fail(ctx, "No take called " + o + ".");
			partners.add(t);
		}

		removeGhosts(server);
		Take old = getTake(server, name);
		Take take = new Take(name);
		if (old != null) {
			take.skinName = old.skinName;
			take.skinTexture = old.skinTexture;
			take.slim = old.slim;
			take.displayName = old.displayName;
		} else {
			take.skinName = player.getScoreboardName();
			take.displayName = player.getScoreboardName();
		}
		ServerLevel level = (ServerLevel) player.level();
		UUID id = player.getUUID();
		begin(player, () -> {
			for (Take t : partners) spawn(server, level, t);
			recording = new Recording(id, take);
		});
		ok(ctx, "Recording " + name + (partners.isEmpty() ? "" : " with " + partners.size() + " ghost(s)")
			+ ". /ghost stop when the take is done.");
		return 1;
	}

	static int play(CommandContext<CommandSourceStack> ctx, String names) throws CommandSyntaxException {
		CommandSourceStack src = ctx.getSource();
		ServerPlayer player = src.getPlayerOrException();
		MinecraftServer server = src.getServer();
		if (recording != null || pending != null) return fail(ctx, "Already running. Use /ghost stop first.");
		List<Take> takes = new ArrayList<>();
		for (String o : names.trim().split("\\s+")) {
			if (o.isEmpty()) continue;
			Take t = getTake(server, o);
			if (t == null || t.frames.isEmpty()) return fail(ctx, "No take called " + o + ".");
			takes.add(t);
		}
		if (takes.isEmpty()) return fail(ctx, "Name at least one take.");
		removeGhosts(server);
		ServerLevel level = (ServerLevel) player.level();
		begin(player, () -> {
			for (Take t : takes) spawn(server, level, t);
		});
		ok(ctx, "Playing " + takes.size() + " take(s). /ghost stop to remove the ghosts.");
		return 1;
	}

	static int stop(CommandContext<CommandSourceStack> ctx) {
		MinecraftServer server = ctx.getSource().getServer();
		boolean wasPending = pending != null;
		Take saved = finishRecording(server);
		pending = null;
		int ghosts = PLAYBACKS.size();
		removeGhosts(server);
		if (saved != null) {
			ok(ctx, "Saved take " + saved.name + " (" + saved.seconds() + "). Removed " + ghosts + " ghost(s).");
		} else if (wasPending) {
			ok(ctx, "Cancelled.");
		} else {
			ok(ctx, "Removed " + ghosts + " ghost(s).");
		}
		return 1;
	}

	static int list(CommandContext<CommandSourceStack> ctx) {
		MinecraftServer server = ctx.getSource().getServer();
		List<String> names = listTakes(server);
		if (names.isEmpty()) {
			ok(ctx, "No takes yet. Start with /ghost record <name>.");
			return 0;
		}
		StringBuilder sb = new StringBuilder("Takes:");
		for (String n : names) {
			Take t = getTake(server, n);
			sb.append("\n  ").append(n);
			if (t != null) {
				sb.append("  ").append(t.seconds());
				sb.append("  skin: ").append(t.skinTexture != null ? t.skinTexture : t.skinName);
				if (t.displayName != null && !t.displayName.isEmpty()) sb.append("  name: ").append(t.displayName);
			}
		}
		ok(ctx, sb.toString());
		return names.size();
	}

	static int delete(CommandContext<CommandSourceStack> ctx) {
		MinecraftServer server = ctx.getSource().getServer();
		String name = StringArgumentType.getString(ctx, "take");
		if (!validName(name)) return fail(ctx, "Bad take name.");
		CACHE.remove(name);
		try {
			if (!Files.deleteIfExists(takeFile(server, name))) return fail(ctx, "No take called " + name + ".");
		} catch (IOException ex) {
			return fail(ctx, "Could not delete: " + ex.getMessage());
		}
		ok(ctx, "Deleted " + name + ".");
		return 1;
	}

	static int skin(CommandContext<CommandSourceStack> ctx, String player, String texture) {
		MinecraftServer server = ctx.getSource().getServer();
		Take t = getTake(server, StringArgumentType.getString(ctx, "take"));
		if (t == null) return fail(ctx, "No take with that name.");
		if (texture != null) {
			texture = texture.trim();
			if (!texture.contains(":")) texture = "hollowfall:skin/" + texture;
			t.skinTexture = texture;
		} else {
			t.skinTexture = null;
			t.skinName = player;
		}
		saveQuietly(server, t);
		ok(ctx, t.name + " now wears " + (t.skinTexture != null ? "texture " + t.skinTexture : "the skin of " + t.skinName)
			+ ". Takes effect the next time it plays.");
		return 1;
	}

	static int slim(CommandContext<CommandSourceStack> ctx) {
		MinecraftServer server = ctx.getSource().getServer();
		Take t = getTake(server, StringArgumentType.getString(ctx, "take"));
		if (t == null) return fail(ctx, "No take with that name.");
		t.slim = BoolArgumentType.getBool(ctx, "slim");
		saveQuietly(server, t);
		ok(ctx, t.name + " uses the " + (t.slim ? "slim" : "wide") + " arm model (texture skins only).");
		return 1;
	}

	static int name(CommandContext<CommandSourceStack> ctx, String text) {
		MinecraftServer server = ctx.getSource().getServer();
		Take t = getTake(server, StringArgumentType.getString(ctx, "take"));
		if (t == null) return fail(ctx, "No take with that name.");
		t.displayName = text;
		saveQuietly(server, t);
		ok(ctx, text.isEmpty() ? t.name + " has no name tag now." : t.name + " is shown as " + text + ".");
		return 1;
	}

	// ============================================================================================
	// Helpers
	// ============================================================================================

	static void ok(CommandContext<CommandSourceStack> ctx, String msg) {
		ctx.getSource().sendSuccess(() -> Component.literal(msg), false);
	}

	static int fail(CommandContext<CommandSourceStack> ctx, String msg) {
		ctx.getSource().sendFailure(Component.literal(msg));
		return 0;
	}

	static void run(MinecraftServer server, ServerLevel level, String command) {
		CommandSourceStack src = server.createCommandSourceStack().withLevel(level).withSuppressedOutput();
		server.getCommands().performPrefixedCommand(src, command);
	}

	static void actionbar(MinecraftServer server, UUID player, String text, String color) {
		if (player == null) return;
		run(server, server.overworld(), "title " + player + " actionbar {text:" + q(text) + ",color:\"" + color + "\"}");
	}

	static String q(String s) {
		return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
	}

	static String uuidArray(UUID id) {
		long m = id.getMostSignificantBits(), l = id.getLeastSignificantBits();
		return "[I;" + (int) (m >> 32) + "," + (int) m + "," + (int) (l >> 32) + "," + (int) l + "]";
	}
}

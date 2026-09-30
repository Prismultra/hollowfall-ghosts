package com.prismultra.ghosts;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.Locale;
import java.util.UUID;

/**
 * The hunter: an AI stalker for chase scenes (tobinbeans).
 *
 * It is two entities at the same spot. The "brain" is an invisible, silent vindicator that does the real
 * work: vanilla pathfinding over terrain, jumping, swimming, opening doors, and melee attacks. The "body"
 * is a mannequin wearing the character's skin that copies the brain every tick, so the camera sees a
 * player. On top of vanilla AI the hunter always knows where its target is, blinks along the target's
 * trail when it gets stuck, and can stay invisible until it's close.
 */
public final class Hunter {
	static final String TAG = "hf_hunter";
	static final int TRAIL = 200;          // ticks of target positions kept (10 s)

	// settings (persist while the game runs)
	static String skin = "hollowfall:skin/tobinbeans";
	static String name = "tobinbeans";
	static double speed = 0.34;            // vindicator default 0.35; a sprinting player is roughly 0.3-ish in mob terms
	static double damage = 3.0;
	static double health = 20.0;
	static double reveal = 12.0;           // invisible beyond this distance; 0 = always visible
	static boolean blink = true;
	static String weapon = "minecraft:iron_sword";

	// the one active hunter
	static UUID brain, body, target;
	static ServerLevel level;
	static boolean paused;
	static Boolean visible;
	static LivingEntity.SwingDescription lastSwing;
	static final ArrayDeque<Vec3> trail = new ArrayDeque<>();
	static Vec3 stuckCheckPos;
	static int ticks;

	private Hunter() {}

	// ============================================================================================

	static void tick(MinecraftServer server) {
		if (brain == null || level == null) return;
		Entity be = level.getEntity(brain);
		Entity bo = level.getEntity(body);
		if (be == null && bo == null) return; // chunk not loaded
		if (!(be instanceof Mob b) || !b.isAlive()) {
			// brain gone or dead: the body dies too
			if (bo != null && bo.isAlive()) GhostsMod.run(server, level, "kill " + body);
			clear();
			return;
		}
		ticks++;
		ServerPlayer t = target == null ? null : server.getPlayerList().getPlayer(target);

		if (t != null && t.level() == level) {
			trail.addLast(new Vec3(t.getX(), t.getY(), t.getZ()));
			while (trail.size() > TRAIL) trail.removeFirst();
			if (!paused && b.getTarget() != t) b.setTarget(t);
		}

		// body follows brain
		if (bo instanceof LivingEntity m) {
			m.setPos(b.getX(), b.getY(), b.getZ());
			m.setYRot(b.getYRot());
			m.setXRot(b.getXRot());
			m.setYHeadRot(b.getYHeadRot());
			m.setYBodyRot(b.yBodyRot);
			m.setDeltaMovement(Vec3.ZERO);
			if (m.getHealth() < m.getMaxHealth()) m.setHealth(m.getMaxHealth());

			LivingEntity.SwingDescription s = b.isSwinging() ? b.getCurrentSwing() : null;
			if (s != null && s != lastSwing) GhostsMod.run(server, level, "swing " + body + " mainhand");
			lastSwing = s;

			boolean vis = reveal <= 0 || t == null || b.distanceTo(t) <= reveal;
			if (visible == null || vis != visible) {
				visible = vis;
				GhostsMod.run(server, level, vis
					? "effect clear " + body + " minecraft:invisibility"
					: "effect give " + body + " minecraft:invisibility infinite 0 true");
			}
		}

		// stuck? jump along the target's trail, out of sight behind them
		if (blink && !paused && t != null && t.level() == level && ticks % 60 == 0) {
			Vec3 here = new Vec3(b.getX(), b.getY(), b.getZ());
			if (stuckCheckPos != null && here.distanceTo(stuckCheckPos) < 1.5 && b.distanceTo(t) > 5.0
				&& trail.size() >= 100) {
				Vec3 back = trail.toArray(new Vec3[0])[trail.size() - 100]; // where the target was ~5 s ago
				if (back.distanceTo(new Vec3(t.getX(), t.getY(), t.getZ())) >= 5.0) {
					GhostsMod.run(server, level, String.format(Locale.ROOT, "tp %s %.3f %.3f %.3f", brain, back.x, back.y, back.z));
				}
			}
			stuckCheckPos = here;
		}
	}

	/** Player hit the hunter's body: pass the hit to the brain so the fight is real. */
	static void onAttack(Player player, Entity entity) {
		if (body == null || level == null || !entity.getUUID().equals(body)) return;
		MinecraftServer server = level.getServer();
		if (server == null) return;
		double dmg = Math.max(1.0, player.getAttributeValue(Attributes.ATTACK_DAMAGE));
		GhostsMod.run(server, level, String.format(Locale.ROOT, "damage %s %.2f minecraft:player_attack by %s",
			brain, dmg, player.getUUID()));
	}

	/** Brain got hurt (by any source): make the body flinch too. */
	static void onBrainDamaged(LivingEntity entity) {
		if (brain == null || level == null || !entity.getUUID().equals(brain)) return;
		MinecraftServer server = level.getServer();
		if (server != null) GhostsMod.run(server, level, "damage " + body + " 1 minecraft:generic");
	}

	static void remove(MinecraftServer server) {
		if (level != null) {
			Entity b = level.getEntity(brain);
			Entity m = level.getEntity(body);
			if (b != null) b.discard();
			if (m != null) m.discard();
		}
		clear();
	}

	static void clear() {
		brain = body = target = null;
		level = null;
		paused = false;
		visible = null;
		lastSwing = null;
		trail.clear();
		stuckCheckPos = null;
		ticks = 0;
	}

	// ============================================================================================

	static void register(CommandDispatcher<CommandSourceStack> d) {
		d.register(Commands.literal("hunter")
			.then(Commands.literal("spawn")
				.then(Commands.argument("target", EntityArgument.player())
					.executes(Hunter::spawn)))
			.then(Commands.literal("target")
				.then(Commands.argument("target", EntityArgument.player())
					.executes(ctx -> {
						target = EntityArgument.getPlayer(ctx, "target").getUUID();
						GhostsMod.ok(ctx, "The hunter is now after " + EntityArgument.getPlayer(ctx, "target").getScoreboardName() + ".");
						return 1;
					})))
			.then(Commands.literal("stop").executes(ctx -> {
				remove(ctx.getSource().getServer());
				GhostsMod.ok(ctx, "Hunter removed.");
				return 1;
			}))
			.then(Commands.literal("pause").executes(ctx -> setPaused(ctx, true)))
			.then(Commands.literal("resume").executes(ctx -> setPaused(ctx, false)))
			.then(Commands.literal("skin")
				.then(Commands.argument("id", StringArgumentType.greedyString())
					.executes(ctx -> {
						String id = StringArgumentType.getString(ctx, "id").trim();
						skin = id.contains(":") ? id : "hollowfall:skin/" + id;
						GhostsMod.ok(ctx, "Hunter skin: " + skin + " (next spawn).");
						return 1;
					})))
			.then(Commands.literal("name")
				.then(Commands.literal("none").executes(ctx -> { name = ""; GhostsMod.ok(ctx, "No name tag (next spawn)."); return 1; }))
				.then(Commands.literal("set")
					.then(Commands.argument("text", StringArgumentType.greedyString())
						.executes(ctx -> { name = StringArgumentType.getString(ctx, "text"); GhostsMod.ok(ctx, "Name: " + name + " (next spawn)."); return 1; }))))
			.then(Commands.literal("weapon")
				.then(Commands.argument("item", StringArgumentType.greedyString())
					.executes(ctx -> { weapon = StringArgumentType.getString(ctx, "item").trim(); GhostsMod.ok(ctx, "Weapon: " + weapon + " (next spawn)."); return 1; })))
			.then(Commands.literal("speed")
				.then(Commands.argument("value", DoubleArgumentType.doubleArg(0.05, 1.0))
					.executes(ctx -> { speed = DoubleArgumentType.getDouble(ctx, "value"); applyAttributes(ctx); GhostsMod.ok(ctx, "Speed " + speed + "."); return 1; })))
			.then(Commands.literal("damage")
				.then(Commands.argument("value", DoubleArgumentType.doubleArg(0.0, 100.0))
					.executes(ctx -> { damage = DoubleArgumentType.getDouble(ctx, "value"); applyAttributes(ctx); GhostsMod.ok(ctx, "Damage " + damage + " per hit."); return 1; })))
			.then(Commands.literal("health")
				.then(Commands.argument("value", DoubleArgumentType.doubleArg(1.0, 1024.0))
					.executes(ctx -> { health = DoubleArgumentType.getDouble(ctx, "value"); GhostsMod.ok(ctx, "Health " + health + " (next spawn)."); return 1; })))
			.then(Commands.literal("reveal")
				.then(Commands.argument("blocks", DoubleArgumentType.doubleArg(0.0, 256.0))
					.executes(ctx -> {
						reveal = DoubleArgumentType.getDouble(ctx, "blocks");
						visible = null;
						GhostsMod.ok(ctx, reveal <= 0 ? "Hunter always visible." : "Hunter invisible until within " + reveal + " blocks.");
						return 1;
					})))
			.then(Commands.literal("blink")
				.then(Commands.argument("on", BoolArgumentType.bool())
					.executes(ctx -> { blink = BoolArgumentType.getBool(ctx, "on"); GhostsMod.ok(ctx, "Blink when stuck: " + blink + "."); return 1; }))));
	}

	static int spawn(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		CommandSourceStack src = ctx.getSource();
		MinecraftServer server = src.getServer();
		ServerPlayer t = EntityArgument.getPlayer(ctx, "target");
		remove(server);
		ServerLevel lvl = src.getLevel();
		Vec3 pos = src.getPosition();
		UUID b = UUID.randomUUID();
		UUID m = UUID.randomUUID();
		String xyz = String.format(Locale.ROOT, "%.3f %.3f %.3f", pos.x, pos.y, pos.z);

		String brainNbt = "{UUID:" + GhostsMod.uuidArray(b)
			+ ",Silent:1b,PersistenceRequired:1b,CanJoinRaid:0b,Tags:[\"" + TAG + "\"]"
			+ ",DeathLootTable:\"minecraft:empty\""
			+ ",active_effects:[{id:\"minecraft:invisibility\",duration:-1,amplifier:0b,show_particles:0b}]"
			+ String.format(Locale.ROOT, ",attributes:[{id:\"minecraft:follow_range\",base:256.0d},"
				+ "{id:\"minecraft:movement_speed\",base:%.3fd},{id:\"minecraft:attack_damage\",base:%.2fd},"
				+ "{id:\"minecraft:max_health\",base:%.1fd}],Health:%.1ff", speed, damage, health, health)
			+ "}";
		GhostsMod.run(server, lvl, "summon minecraft:vindicator " + xyz + " " + brainNbt);

		StringBuilder bodyNbt = new StringBuilder("{UUID:").append(GhostsMod.uuidArray(m));
		bodyNbt.append(",NoGravity:1b,hide_description:1b,Tags:[\"").append(TAG).append("\"]");
		bodyNbt.append(",attributes:[{id:\"minecraft:max_health\",base:1024.0d}],Health:1024.0f");
		bodyNbt.append(",profile:{texture:").append(GhostsMod.q(skin)).append("}");
		if (!name.isEmpty()) bodyNbt.append(",CustomName:").append(GhostsMod.q(name)).append(",CustomNameVisible:1b");
		bodyNbt.append("}");
		GhostsMod.run(server, lvl, "summon minecraft:mannequin " + xyz + " " + bodyNbt);

		Entity be = lvl.getEntity(b);
		if (!(be instanceof Mob mob)) {
			return GhostsMod.fail(ctx, "Couldn't spawn the hunter here.");
		}
		// the brain is invisible, but held items would still show: empty its hands
		mob.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
		mob.setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
		if (!weapon.isEmpty()) GhostsMod.run(server, lvl, "item replace entity " + m + " weapon.mainhand with " + weapon);

		brain = b;
		body = m;
		target = t.getUUID();
		level = lvl;
		GhostsMod.ok(ctx, "The hunter is after " + t.getScoreboardName() + ". Survival mode only: mobs ignore Creative players. /hunter stop to remove it.");
		return 1;
	}

	static int setPaused(CommandContext<CommandSourceStack> ctx, boolean p) {
		if (brain == null || level == null) return GhostsMod.fail(ctx, "No hunter out. /hunter spawn <player> first.");
		paused = p;
		MinecraftServer server = ctx.getSource().getServer();
		GhostsMod.run(server, level, "data merge entity " + brain + " {NoAI:" + (p ? "1b" : "0b") + "}");
		if (p && level.getEntity(brain) instanceof Mob mob) mob.setTarget(null);
		GhostsMod.ok(ctx, p ? "Hunter frozen." : "Hunter hunting again.");
		return 1;
	}

	static void applyAttributes(CommandContext<CommandSourceStack> ctx) {
		if (brain == null || level == null) return;
		MinecraftServer server = ctx.getSource().getServer();
		GhostsMod.run(server, level, String.format(Locale.ROOT, "attribute %s minecraft:movement_speed base set %.3f", brain, speed));
		GhostsMod.run(server, level, String.format(Locale.ROOT, "attribute %s minecraft:attack_damage base set %.2f", brain, damage));
	}
}

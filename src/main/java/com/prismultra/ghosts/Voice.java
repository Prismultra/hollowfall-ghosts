package com.prismultra.ghosts;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Voice for ghosts, via Simple Voice Chat (optional). This class never touches voice chat classes itself:
 * the voice chat plugin (voice/VoicePlugin) plugs a {@link Sink} in when voice chat is installed.
 */
public final class Voice {
	/** One 20 ms Opus packet from the microphone, at its time since the take started. */
	public record Frame(int ms, byte[] opus) {}

	/** Implemented by the voice chat plugin. */
	public interface Sink {
		/** Start speaking these frames from the entity, in real time. Returns a handle to stop it. */
		Runnable play(List<Frame> frames, Entity speaker, ServerLevel level);
	}

	static volatile Sink sink;
	static volatile UUID recordingPlayer;
	static volatile long recordingStart;
	static final List<Frame> captured = new ArrayList<>();

	private Voice() {}

	public static void setSink(Sink s) {
		sink = s;
		GhostsMod.LOGGER.info("Hollowfall Ghosts: Simple Voice Chat found, ghosts can talk.");
	}

	public static boolean available() {
		return sink != null;
	}

	/** Called by the voice plugin for every microphone packet (on the voice chat thread). */
	public static void onMicrophone(UUID player, byte[] opus) {
		UUID rec = recordingPlayer;
		if (rec == null || !rec.equals(player) || opus == null || opus.length == 0) return;
		int ms = (int) ((System.nanoTime() - recordingStart) / 1_000_000L);
		synchronized (captured) {
			captured.add(new Frame(ms, opus.clone()));
		}
	}

	static void startCapture(UUID player) {
		synchronized (captured) {
			captured.clear();
		}
		recordingStart = System.nanoTime();
		recordingPlayer = player;
	}

	static List<Frame> stopCapture() {
		recordingPlayer = null;
		synchronized (captured) {
			List<Frame> out = new ArrayList<>(captured);
			captured.clear();
			return out;
		}
	}

	static Runnable play(List<Frame> frames, Entity speaker, ServerLevel level) {
		Sink s = sink;
		if (s == null || frames == null || frames.isEmpty() || speaker == null) return null;
		try {
			return s.play(frames, speaker, level);
		} catch (Throwable t) {
			GhostsMod.LOGGER.warn("Ghost voice playback failed", t);
			return null;
		}
	}

	// ---- files: <take>.voice next to the take -------------------------------------------------

	static void save(Path file, List<Frame> frames) throws IOException {
		if (frames == null || frames.isEmpty()) {
			Files.deleteIfExists(file);
			return;
		}
		try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(Files.newOutputStream(file)))) {
			out.writeInt(1);
			out.writeInt(frames.size());
			for (Frame f : frames) {
				out.writeInt(f.ms());
				out.writeShort(f.opus().length);
				out.write(f.opus());
			}
		}
	}

	static List<Frame> load(Path file) {
		List<Frame> frames = new ArrayList<>();
		if (!Files.exists(file)) return frames;
		try (DataInputStream in = new DataInputStream(new GZIPInputStream(Files.newInputStream(file)))) {
			in.readInt();
			int n = in.readInt();
			for (int i = 0; i < n; i++) {
				int ms = in.readInt();
				byte[] data = new byte[in.readUnsignedShort()];
				in.readFully(data);
				frames.add(new Frame(ms, data));
			}
		} catch (IOException e) {
			GhostsMod.LOGGER.warn("Could not read voice file {}", file, e);
		}
		return frames;
	}
}

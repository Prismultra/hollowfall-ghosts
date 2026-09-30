package com.prismultra.ghosts.voice;

import com.prismultra.ghosts.Voice;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.audiochannel.EntityAudioChannel;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.MicrophonePacketEvent;
import de.maxhenkel.voicechat.api.events.VoicechatServerStartedEvent;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** Loaded by Simple Voice Chat through the "voicechat" entrypoint. Only runs when voice chat is installed. */
public class VoicePlugin implements VoicechatPlugin {
	private static volatile VoicechatServerApi api;

	@Override
	public String getPluginId() {
		return "hollowfall_ghosts";
	}

	@Override
	public void registerEvents(EventRegistration registration) {
		registration.registerEvent(VoicechatServerStartedEvent.class, e -> {
			api = e.getVoicechat();
			Voice.setSink(VoicePlugin::play);
		});
		registration.registerEvent(MicrophonePacketEvent.class, e -> {
			VoicechatConnection sender = e.getSenderConnection();
			if (sender == null || sender.getPlayer() == null) return;
			Voice.onMicrophone(sender.getPlayer().getUuid(), e.getPacket().getOpusEncodedData());
		});
	}

	/** Speak the frames from the entity on a background thread, keeping their original timing. */
	private static Runnable play(List<Voice.Frame> frames, net.minecraft.world.entity.Entity speaker,
								 net.minecraft.server.level.ServerLevel level) {
		VoicechatServerApi a = api;
		if (a == null) return null;
		EntityAudioChannel channel = a.createEntityAudioChannel(UUID.randomUUID(), a.fromEntity(speaker));
		if (channel == null) return null;
		AtomicBoolean stop = new AtomicBoolean(false);
		Thread t = new Thread(() -> {
			long start = System.nanoTime();
			for (Voice.Frame f : frames) {
				if (stop.get()) break;
				long wait = f.ms() - (System.nanoTime() - start) / 1_000_000L;
				if (wait > 0) {
					try {
						Thread.sleep(wait);
					} catch (InterruptedException ex) {
						break;
					}
				}
				if (stop.get()) break;
				channel.send(f.opus());
			}
			channel.flush();
		}, "hollowfall-ghost-voice");
		t.setDaemon(true);
		t.start();
		return () -> stop.set(true);
	}
}

package org.weaw.network.client;

import org.joml.Vector3f;
import org.weaw.network.protocol.NetworkPlayerState;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class RemotePlayerStore {
    private static final long INTERPOLATION_DELAY_NANOS = 100_000_000L;
    private static final int MAX_SAMPLES = 20;

    private final Map<Long, Track> tracks = new HashMap<>();

    public void accept(long localPlayerId, List<NetworkPlayerState> states, long receivedNanos) {
        for (NetworkPlayerState state : states) {
            if (state.playerId() == localPlayerId) {
                continue;
            }
            Track track = tracks.computeIfAbsent(state.playerId(), ignored -> new Track());
            track.samples.addLast(new TimedState(receivedNanos, state));
            while (track.samples.size() > MAX_SAMPLES) {
                track.samples.removeFirst();
            }
        }
    }

    public void remove(long playerId) {
        tracks.remove(playerId);
    }

    public List<RenderedRemotePlayer> sample(long nowNanos) {
        long target = nowNanos - INTERPOLATION_DELAY_NANOS;
        List<RenderedRemotePlayer> result = new ArrayList<>(tracks.size());
        for (Track track : tracks.values()) {
            RenderedRemotePlayer player = track.sample(target);
            if (player != null) {
                result.add(player);
            }
        }
        return List.copyOf(result);
    }

    public int size() {
        return tracks.size();
    }

    public record RenderedRemotePlayer(long playerId, String name, Vector3f position, float yaw) {
        public RenderedRemotePlayer {
            position = new Vector3f(position);
        }

        @Override
        public Vector3f position() {
            return new Vector3f(position);
        }
    }

    private static final class Track {
        private final ArrayDeque<TimedState> samples = new ArrayDeque<>();

        private RenderedRemotePlayer sample(long targetNanos) {
            if (samples.isEmpty()) {
                return null;
            }
            while (samples.size() > 2) {
                TimedState second = samples.stream().skip(1).findFirst().orElseThrow();
                if (second.receivedNanos > targetNanos) {
                    break;
                }
                samples.removeFirst();
            }
            TimedState first = samples.getFirst();
            TimedState last = samples.getLast();
            if (first == last || targetNanos <= first.receivedNanos) {
                return rendered(first.state);
            }
            float alpha = Math.clamp(
                    (targetNanos - first.receivedNanos) / (float) (last.receivedNanos - first.receivedNanos),
                    0.0f,
                    1.0f
            );
            NetworkPlayerState a = first.state;
            NetworkPlayerState b = last.state;
            Vector3f position = a.position().lerp(b.position(), alpha);
            float yaw = a.yaw() + shortestAngleDelta(a.yaw(), b.yaw()) * alpha;
            return new RenderedRemotePlayer(b.playerId(), b.name(), position, yaw);
        }

        private static RenderedRemotePlayer rendered(NetworkPlayerState state) {
            return new RenderedRemotePlayer(state.playerId(), state.name(), state.position(), state.yaw());
        }

        private static float shortestAngleDelta(float from, float to) {
            float delta = (to - from) % 360.0f;
            if (delta >= 180.0f) delta -= 360.0f;
            if (delta < -180.0f) delta += 360.0f;
            return delta;
        }
    }

    private record TimedState(long receivedNanos, NetworkPlayerState state) {
    }
}

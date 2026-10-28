import com.google.gson.Gson;
import java.io.IOException;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.websocket.OnClose;
import javax.websocket.OnError;
import javax.websocket.OnMessage;
import javax.websocket.OnOpen;
import javax.websocket.Session;
import javax.websocket.server.PathParam;
import javax.websocket.server.ServerEndpoint;

@ServerEndpoint("/leaderboard/{resortID}")
public class LeaderboardEndpoint {

  // resortID -> sessions subscribed to that resort
  private static final Map<String, Set<Session>> subscribers = new ConcurrentHashMap<>();
  private static final ScheduledExecutorService pusher = Executors.newSingleThreadScheduledExecutor();

  static {
    // Dummy: every 500ms, push a synthetic top-N to each resort's subscribers.
    pusher.scheduleAtFixedRate(LeaderboardEndpoint::pushAll, 500, 500, TimeUnit.MILLISECONDS);
  }

  @OnOpen
  public void onOpen(Session session, @PathParam("resortID") String resortID) {
    subscribers.computeIfAbsent(resortID, k -> ConcurrentHashMap.newKeySet()).add(session);

    System.out.println("Opened: " + session.getId() + " with " + resortID);

    // Register the connection
    subscribers.computeIfAbsent(
        resortID, key -> ConcurrentHashMap.newKeySet()
    ).add(session);
  }

  @OnClose
  public void onClose(Session session, @PathParam("resortID") String resortID) {
    System.out.println("Closed: " + session.getId() + " with " + resortID);

    // Remove the connection
    Optional.ofNullable(subscribers.get(resortID)).ifPresent(s -> s.remove(session));
  }

  @OnError
  public void onError(Session session, Throwable error) {
    System.out.println("WebSocket Error: " + error.getMessage());

    if (session != null) {
      // Clean up the subscribers
      for (Map.Entry<String, Set<Session>> entry : subscribers.entrySet()) {
        entry.getValue().remove(session);
      }
    }
  }

  private static void pushAll() {
    for (Map.Entry<String, Set<Session>> e : subscribers.entrySet()) {
      String json = syntheticLeaderboard(e.getKey()); // canned/random top-N + serverTimestamp
      for (Session s : e.getValue()) {
        if (s.isOpen()) s.getAsyncRemote().sendText(json);
      }
    }
  }

  private static String syntheticLeaderboard(String resortID) {
    // Build a leaderboard containing:
    // resortID, a fresh serverTimestamp, and a fixed top List
    Map<String, Object> message = Map.of(
        "resortID", resortID,
        "serverTimestamp", Instant.now().toString(),
        "top", List.of(
            Map.of("rank", 1, "skierID", 123, "vertical", 44000),
            Map.of("rank", 2, "skierID", 456, "vertical", 42000)
        )
    );

    return new Gson().toJson(message);
  }
}

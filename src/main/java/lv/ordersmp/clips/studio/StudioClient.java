package lv.ordersmp.clips.studio;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.function.LongConsumer;

/**
 * Talks to OrderSMP Studio's API for the mod: the device link (the player approves this PC in the
 * browser and the mod receives a token) and the resumable upload, which sends a clip in parts and
 * can continue from the byte Studio already has after a dropped connection.
 */
public final class StudioClient {

  /** A failed request, with Studio's error code and the message it gave for the player. */
  public static final class StudioException extends Exception {

    private static final long serialVersionUID = 1L;

    private final int status;
    private final String code;
    private final long receivedBytes;

    StudioException(int status, String code, String message, long receivedBytes) {
      super(message);
      this.status = status;
      this.code = code;
      this.receivedBytes = receivedBytes;
    }

    /** The HTTP status, or 0 when Studio could not be reached. */
    public int status() {
      return status;
    }

    public String code() {
      return code;
    }

    /** For a part sent at the wrong position: where Studio wants the upload to continue, else -1. */
    public long receivedBytes() {
      return receivedBytes;
    }
  }

  /**
   * A started link.
   *
   * @param deviceCode secret the mod polls with
   * @param userCode short code the player can type on the Studio page
   * @param approveUrl Studio page with the code already filled in
   * @param expiresIn seconds the code is valid
   * @param interval seconds to wait between polls
   */
  public record Link(String deviceCode, String userCode, String approveUrl, int expiresIn, int interval) {}

  /**
   * The answer to a poll: a token once the player approved, otherwise the reason to keep waiting or stop.
   *
   * @param token the access token, or null
   * @param expiresAt when the token stops working
   * @param accountName the Studio account it belongs to
   * @param error {@code authorization_pending}, {@code slow_down}, {@code expired_token} or {@code access_denied}
   */
  public record Poll(String token, Instant expiresAt, String accountName, String error) {}

  /**
   * An upload Studio is ready to receive.
   *
   * @param id upload id
   * @param partBytes largest part Studio accepts
   * @param receivedBytes bytes Studio already has
   */
  public record Upload(String id, long partBytes, long receivedBytes) {}

  private static final Gson GSON = new Gson();
  private static final Duration CALL_TIMEOUT = Duration.ofSeconds(30);
  private static final Duration PART_TIMEOUT = Duration.ofMinutes(5);
  private static final String USER_AGENT = "OrderSMP-Clips";

  private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
  private final String baseUrl;

  public StudioClient(String baseUrl) {
    this.baseUrl = baseUrl;
  }

  /** Starts linking this PC; the player then approves it on {@link Link#approveUrl()}. */
  public Link startLink() throws StudioException {
    JsonObject body = call("POST", "/link/start", null, Map.of("name", "OrderSMP Clips", "client", "clips"));
    return new Link(text(body, "device_code"), text(body, "user_code"), text(body, "verification_uri_complete"),
        body.get("expires_in").getAsInt(), Math.max(1, body.get("interval").getAsInt()));
  }

  /** Asks whether the player approved yet. */
  public Poll poll(String deviceCode) throws StudioException {
    HttpResponse<String> response = send(json("POST", "/link/token", null, Map.of("device_code", deviceCode), CALL_TIMEOUT));
    JsonObject body = parse(response.body());
    if (response.statusCode() == 400 && body.has("error")) {
      return new Poll(null, null, null, text(body, "error"));
    }
    if (response.statusCode() != 200) {
      throw failure(response.statusCode(), body);
    }
    return new Poll(text(body, "access_token"), Instant.parse(text(body, "expires_at")), text(body, "account_name"), null);
  }

  /** The name of the Studio account {@code token} belongs to; also checks the token still works. */
  public String accountName(String token) throws StudioException {
    return text(call("GET", "/api/clips/me", token, null), "accountName");
  }

  /** Tells Studio a clip of {@code sizeBytes} is coming. */
  public Upload createUpload(String token, String title, long sizeBytes) throws StudioException {
    JsonObject body = call("POST", "/api/clips/uploads", token, Map.of("title", title, "sizeBytes", sizeBytes));
    return new Upload(text(body, "id"), body.get("partBytes").getAsLong(), body.get("receivedBytes").getAsLong());
  }

  /**
   * Sends {@code length} bytes from {@code data} as the part starting at {@code offset}, reporting
   * every byte that leaves to {@code progress}. Returns how many bytes Studio now has.
   */
  public long sendPart(String token, String id, long offset, InputStream data, long length, LongConsumer progress)
      throws StudioException {
    HttpRequest.BodyPublisher counted = HttpRequest.BodyPublishers.fromPublisher(
        HttpRequest.BodyPublishers.ofInputStream(() -> new Counting(data, progress)), length);
    HttpRequest request = request("/api/clips/uploads/" + id + "?offset=" + offset, token, PART_TIMEOUT)
        .header("Content-Type", "application/octet-stream")
        .PUT(counted)
        .build();
    HttpResponse<String> response = send(request);
    JsonObject body = parse(response.body());
    if (response.statusCode() != 200) {
      throw failure(response.statusCode(), body);
    }
    return body.get("receivedBytes").getAsLong();
  }

  /** Tells Studio the last byte arrived; returns the address of the clip in Studio. */
  public String finish(String token, String id) throws StudioException {
    return text(call("POST", "/api/clips/uploads/" + id + "/finish", token, Map.of()), "url");
  }

  /** Revokes {@code token} on Studio's side. */
  public void unlink(String token) throws StudioException {
    HttpResponse<String> response = send(json("POST", "/api/clips/unlink", token, Map.of(), CALL_TIMEOUT));
    if (response.statusCode() != 204 && response.statusCode() != 200 && response.statusCode() != 401) {
      throw failure(response.statusCode(), parse(response.body()));
    }
  }

  private JsonObject call(String method, String path, String token, Map<String, ?> body) throws StudioException {
    HttpRequest request = body == null
        ? request(path, token, CALL_TIMEOUT).method(method, HttpRequest.BodyPublishers.noBody()).build()
        : json(method, path, token, body, CALL_TIMEOUT);
    HttpResponse<String> response = send(request);
    JsonObject parsed = parse(response.body());
    if (response.statusCode() / 100 != 2) {
      throw failure(response.statusCode(), parsed);
    }
    return parsed;
  }

  private HttpRequest json(String method, String path, String token, Map<String, ?> body, Duration timeout) {
    return request(path, token, timeout)
        .header("Content-Type", "application/json")
        .method(method, HttpRequest.BodyPublishers.ofString(GSON.toJson(body)))
        .build();
  }

  private HttpRequest.Builder request(String path, String token, Duration timeout) {
    HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
        .timeout(timeout)
        .header("User-Agent", USER_AGENT)
        .header("Accept", "application/json");
    if (token != null) {
      builder.header("Authorization", "Bearer " + token);
    }
    return builder;
  }

  private HttpResponse<String> send(HttpRequest request) throws StudioException {
    try {
      return http.send(request, HttpResponse.BodyHandlers.ofString());
    } catch (IOException e) {
      throw new StudioException(0, "OFFLINE", "Studio could not be reached.", -1);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new StudioException(0, "CANCELLED", "The upload was stopped.", -1);
    }
  }

  private static StudioException failure(int status, JsonObject body) {
    String code = body.has("code") ? text(body, "code") : "HTTP_" + status;
    String message = body.has("message") ? text(body, "message") : "Studio answered with an error (" + status + ").";
    long received = body.has("receivedBytes") ? body.get("receivedBytes").getAsLong() : -1;
    return new StudioException(status, code, message, received);
  }

  private static JsonObject parse(String body) {
    if (body == null || body.isBlank()) {
      return new JsonObject();
    }
    try {
      JsonObject parsed = GSON.fromJson(body, JsonObject.class);
      return parsed == null ? new JsonObject() : parsed;
    } catch (JsonParseException e) {
      return new JsonObject();
    }
  }

  private static String text(JsonObject body, String key) {
    return body.has(key) && !body.get(key).isJsonNull() ? body.get(key).getAsString() : null;
  }

  /** Passes reads through and reports each byte read, which is each byte handed to the network. */
  private static final class Counting extends FilterInputStream {

    private final LongConsumer progress;

    private Counting(InputStream in, LongConsumer progress) {
      super(in);
      this.progress = progress;
    }

    @Override
    public int read() throws IOException {
      int value = super.read();
      if (value >= 0) {
        progress.accept(1);
      }
      return value;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
      int count = super.read(buffer, offset, length);
      if (count > 0) {
        progress.accept(count);
      }
      return count;
    }

    @Override
    public void close() {
    }
  }
}

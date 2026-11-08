package games.strategy.triplea.ai.proplus.strategia.llm;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/** The HTTP POST the API clients use (replaced by a fake in the tests: no network there). */
@FunctionalInterface
public interface Http {
  record Esito(int stato, String corpo) {}

  Esito post(String url, Map<String, String> headers, String body, Duration timeout)
      throws IOException, InterruptedException;

  /** The real client (java.net.http). */
  Http REALE =
      new Http() {
        private final HttpClient client =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();

        @Override
        public Esito post(
            final String url,
            final Map<String, String> headers,
            final String body,
            final Duration timeout)
            throws IOException, InterruptedException {
          final HttpRequest.Builder request =
              HttpRequest.newBuilder(URI.create(url))
                  .timeout(timeout)
                  .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
          headers.forEach(request::header);
          final HttpResponse<String> response =
              client.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
          return new Esito(response.statusCode(), response.body());
        }
      };
}

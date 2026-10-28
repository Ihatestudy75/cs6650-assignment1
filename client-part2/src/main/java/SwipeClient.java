import com.google.gson.JsonParser;
import java.io.BufferedWriter;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.SplittableRandom;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Sends lift rides over HTTP while listening for leaderboard updates over WebSocket.
 * Run with --smoke for a small check, or --help to see all options.
 */
public class SwipeClient {
    private static final int MAX_RETRIES = 5;
    private final Config config;
    // One shared HTTP client reuses HTTP/1.1 keep-alive connections across senders.
    private final HttpClient http = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(5)).build();
    // Several sender threads add measurements here at the same time.
    private final ConcurrentLinkedQueue<Attempt> attempts = new ConcurrentLinkedQueue<>();
    private final List<Subscriber> subscribers = new ArrayList<>();
    private final AtomicBoolean watching = new AtomicBoolean(true);
    private ArrayBlockingQueue<Swipe> queue;
    // Each event has its own array slot, indexed by its generated ID.
    private Outcome[] outcomes;
    private long runStart;
    private double runSeconds;

    public static void main(String[] args) throws Exception {
        // Keep decimal points consistent in CSV files, regardless of the Mac locale.
        Locale.setDefault(Locale.US);
        Config config = new Config(args);
        if (config.help) {
            System.out.println("SwipeClient [--base-url URL] [--threads 128] [--resorts 10]"
                + " [--subscribers 10] [--season 2026] [--day 1] [--output results/run-name]"
                + " [--probe-only] [--smoke]\n"
                + "Default: 500000 swipes, 32 x 1000 warmup, 128 main senders.\n"
                + "--smoke: 200 swipes, 4 x 10 warmup; NOT an official 500K run.\n"
                + "--probe-only: measure 20 separate sequential requests and exit.");
            return;
        }
        try {
            new SwipeClient(config).run();
        } catch (Exception error) {
            System.err.println("Run did not complete: " + error);
            throw error;
        }
    }

    private SwipeClient(Config config) { this.config = config; }

    // IDs go in the URL; the ride time and lift ID go in the JSON body.
    private HttpRequest request(Swipe swipe) {
        String path = "/skiers/" + swipe.resort + "/seasons/" + config.season
            + "/days/" + config.day + "/skiers/" + swipe.skier;
        String body = "{\"time\":" + swipe.minute + ",\"liftID\":" + swipe.lift + "}";
        return HttpRequest.newBuilder(URI.create(config.base + path))
            .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body)).build();
    }

    private void run() throws Exception {
        Files.createDirectories(config.output);
        System.out.println("Server: " + config.base);
        // Measure the baseline first. These extra requests do not count toward 500,000.
        String probe = probe();
        Files.writeString(config.output.resolve("prediction.txt"), probe);
        System.out.print(probe);
        if (config.probeOnly) return;

        // Fill the queue before sending. This uses more memory, but avoids waiting for data.
        // Only one thread generates rides; its time is reported separately.
        queue = new ArrayBlockingQueue<>(config.total);
        outcomes = new Outcome[config.total];
        long generationStart = System.nanoTime();
        Thread generator = new Thread(() -> {
            // A fixed seed keeps the generated workload the same between test runs.
            SplittableRandom random = new SplittableRandom(6650);
            for (int i = 0; i < config.total; i++) {
                // nextInt includes the lower bound and excludes the upper bound.
                queue.add(new Swipe(i, random.nextInt(1, config.resorts + 1),
                    random.nextInt(1, 100001), random.nextInt(1, 41), random.nextInt(1, 361)));
            }
        }, "swipe-generator");
        generator.start();
        generator.join(); // Wait until every ride is in the queue.
        if (queue.size() != config.total) throw new IllegalStateException("Generation incomplete");
        double generationSeconds = secondsSince(generationStart);
        System.out.printf("Generated %,d events in %.3f s%n", config.total, generationSeconds);

        double warmSeconds;
        double mainSeconds;
        try {
            for (int i = 0; i < config.subscriberCount; i++) {
                Subscriber subscriber = new Subscriber(i, i % config.resorts + 1);
                subscribers.add(subscriber);
                subscriber.thread.start();
            }
            // Wait for the initial subscriptions so ingest does not start without them.
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(25);
            for (Subscriber subscriber : subscribers) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0 || !subscriber.ready.await(remaining, TimeUnit.NANOSECONDS)) {
                    throw new IOException("WebSocket subscriber " + subscriber.id
                        + " did not connect. Verify /leaderboard/{resortID} and the base URL.");
                }
            }
            // Start timing here, after generation and initial WebSocket connections.
            runStart = System.nanoTime();
            System.out.println("Warmup starting...");
            warmSeconds = phase(config.warmThreads, config.warmCount(), "warmup");
            System.out.printf("Warmup complete: %,d events, %.3f s%n", config.warmCount(), warmSeconds);
            System.out.println("Main phase starting with " + config.threads + " sender threads...");
            // Warmup is part of the total: 500,000 - 32,000 = 468,000 rides remain.
            mainSeconds = phase(config.threads, config.total - config.warmCount(), "main");
            runSeconds = secondsSince(runStart);
        } finally {
            watching.set(false);
            for (Subscriber subscriber : subscribers) subscriber.stop();
            for (Subscriber subscriber : subscribers) subscriber.thread.join(3000);
        }
        writeResults(probe, generationSeconds, warmSeconds, mainSeconds);
    }

    // Separate the first request from later requests, since setup adds overhead.
    private String probe() throws Exception {
        double cold = 0;
        double sum = 0;
        for (int i = 0; i < 20; i++) {
            long start = System.nanoTime();
            int status = http.send(request(new Swipe(-1, 1, 1, 1, 1)),
                HttpResponse.BodyHandlers.discarding()).statusCode();
            double elapsed = secondsSince(start);
            if (status != 201) throw new IOException("Probe returned HTTP " + status
                + ". Verify the swipe URL. No load run started.");
            if (i == 0) cold = elapsed;
            else sum += elapsed;
        }
        double steady = sum / 19;
        return String.format("Single-request probe: 20 EXTRA requests, excluded from 500K.\n"
            + "First request including initial connection setup: %.3f ms\n"
            + "Mean of next 19 reused-client requests: %.3f ms\n"
            + "Little's Law estimates (assumes one in-flight request per sender):\n"
            + "Warmup concurrency %d / RTT = %.2f swipes/s\n"
            + "Main concurrency %d / RTT = %.2f swipes/s\n"
            + "These are baseline estimates; RTT may increase under load.\n",
            cold * 1000, steady * 1000, config.warmThreads, config.warmThreads / steady,
            config.threads, config.threads / steady);
    }

    private double phase(int threads, int count, String phase) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<?>> jobs = new ArrayList<>();
        long start = System.nanoTime();
        try {
            for (int i = 0; i < threads; i++) {
                // Split work evenly. The first few workers take any leftover rides.
                // For official warmup, every worker gets exactly 1,000.
                final int quota = count / threads + (i < count % threads ? 1 : 0);
                jobs.add(pool.submit(() -> {
                    for (int j = 0; j < quota; j++) {
                        Swipe swipe = queue.poll();
                        if (swipe == null) throw new IllegalStateException("Unexpected empty queue");
                        try { send(swipe, phase); }
                        catch (InterruptedException error) {
                            Thread.currentThread().interrupt();
                            throw new RuntimeException(error);
                        }
                    }
                }));
            }
            // Do not move to the next phase until every sender has finished.
            for (Future<?> job : jobs) job.get();
        } finally {
            pool.shutdownNow();
            if (!pool.awaitTermination(15, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Sender pool did not stop");
            }
        }
        return secondsSince(start);
    }

    private void send(Swipe swipe, String phase) throws InterruptedException {
        HttpRequest request = request(swipe);
        long logicalStart = System.nanoTime();
        int status = 0;
        int count = 0;
        // retry = 0 is the first attempt; five retries means six attempts at most.
        for (int retry = 0; retry <= MAX_RETRIES; retry++) {
            // Wall-clock time labels the CSV row; nanoTime measures elapsed duration.
            long timestamp = System.currentTimeMillis();
            long start = System.nanoTime();
            String error = "";
            try {
                status = http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
            } catch (IOException failure) {
                status = 0; // No HTTP response: connection error or request timeout.
                error = failure.getClass().getSimpleName();
            }
            long end = System.nanoTime();
            attempts.add(new Attempt(swipe.id, swipe.resort, phase, ++count, timestamp,
                System.currentTimeMillis(), (end - start) / 1e6, status, error));
            if (status == 201) break;
            // Left shift doubles the wait: 100, 200, 400, 800, then 1,600 ms.
            if (retry < MAX_RETRIES) Thread.sleep(100L << retry);
        }
        // Count one final outcome per ride, even when it required multiple HTTP attempts.
        outcomes[swipe.id] = new Outcome(swipe.resort, phase, status == 201, status, count,
            secondsSince(runStart), secondsSince(logicalStart) * 1000);
    }

    /** One coordinator per subscription; network callbacks run on HttpClient threads. */
    private class Subscriber {
        final int id;
        final int resort;
        final CountDownLatch ready = new CountDownLatch(1);
        final ConcurrentLinkedQueue<Push> pushes = new ConcurrentLinkedQueue<>();
        final ConcurrentLinkedQueue<Double> establishment = new ConcurrentLinkedQueue<>();
        final AtomicInteger connections = new AtomicInteger();
        final AtomicInteger connectionFailures = new AtomicInteger();
        final AtomicInteger malformed = new AtomicInteger();
        final Thread thread;
        // The coordinator and callback threads both need to see the current connection.
        volatile WebSocket socket;
        volatile String lastError = "";

        Subscriber(int id, int resort) {
            this.id = id;
            this.resort = resort;
            thread = new Thread(this::listen, "subscriber-" + id);
            thread.setDaemon(true);
        }

        void listen() {
            int failures = 0;
            while (watching.get()) {
                // This future lets the coordinator wait until a callback reports a disconnect.
                CompletableFuture<Void> closed = new CompletableFuture<>();
                long start = System.nanoTime();
                CompletableFuture<WebSocket> connecting = null;
                try {
                    // Convert http to ws (or https to wss), keeping the same host and context.
                    URI uri = URI.create(config.base.replaceFirst("^http", "ws")
                        + "/leaderboard/" + resort);
                    connecting = http.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(5))
                        .buildAsync(uri, new WebSocket.Listener() {
                            final StringBuilder fragments = new StringBuilder();
                            @Override public void onOpen(WebSocket ws) {
                                socket = ws;
                                if (!watching.get()) { ws.abort(); closed.complete(null); return; }
                                establishment.add(secondsSince(start) * 1000);
                                connections.incrementAndGet();
                                ready.countDown();
                                ws.request(1); // Ask the WebSocket API to deliver a message.
                            }
                            @Override public CompletionStage<?> onText(WebSocket ws,
                                    CharSequence text, boolean last) {
                                // One JSON message can arrive in several text fragments.
                                fragments.append(text);
                                if (last) {
                                    Instant received = Instant.now();
                                    try {
                                        var json = JsonParser.parseString(fragments.toString()).getAsJsonObject();
                                        Instant sent = Instant.parse(json.get("serverTimestamp").getAsString());
                                        String messageResort = json.get("resortID").getAsString();
                                        if (!messageResort.equals(Integer.toString(resort))) {
                                            throw new IllegalArgumentException("Wrong resort in push");
                                        }
                                        // These clocks are on different machines; keep negative samples too.
                                        pushes.add(new Push(received, sent,
                                            Duration.between(sent, received).toNanos() / 1e6));
                                    } catch (RuntimeException error) {
                                        malformed.incrementAndGet();
                                        lastError = error.toString();
                                    }
                                    fragments.setLength(0);
                                }
                                ws.request(1);
                                return CompletableFuture.completedFuture(null);
                            }
                            @Override public CompletionStage<?> onClose(WebSocket ws,
                                    int code, String reason) {
                                closed.complete(null);
                                return CompletableFuture.completedFuture(null);
                            }
                            @Override public void onError(WebSocket ws, Throwable error) {
                                lastError = error.toString();
                                closed.complete(null);
                            }
                        });
                    WebSocket ws = connecting.get(10, TimeUnit.SECONDS);
                    if (!watching.get()) { ws.abort(); break; }
                    failures = 0;
                    closed.get(); // Stay connected until onClose or onError signals us.
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception error) {
                    connectionFailures.incrementAndGet();
                    lastError = error.toString();
                    if (connecting != null) connecting.cancel(true);
                } finally {
                    WebSocket ws = socket;
                    if (ws != null) ws.abort();
                    socket = null;
                }
                // Reconnect only while the test is active, with a longer wait after failures.
                if (watching.get()) {
                    try { Thread.sleep(Math.min(5000, 250L << Math.min(failures++, 4))); }
                    catch (InterruptedException error) { Thread.currentThread().interrupt(); break; }
                }
            }
        }

        void stop() {
            WebSocket ws = socket;
            if (ws != null) ws.abort();
            thread.interrupt();
        }
    }

    // Export after sending finishes, so CSV writes do not slow down the measured workload.
    private void writeResults(String probe, double generation, double warm, double main)
            throws IOException {
        int success = 0, warmSuccess = 0;
        long[] perResort = new long[config.resorts + 1];
        int bucketCount = Math.max(1, (int) Math.ceil(runSeconds / 10));
        long[][] buckets = new long[bucketCount][2];
        double[] logical = new double[outcomes.length];
        for (int i = 0; i < outcomes.length; i++) {
            Outcome outcome = outcomes[i];
            if (outcome == null) throw new IllegalStateException("Missing outcome for event " + i);
            logical[i] = outcome.latency;
            // Group completed rides into 0-10 s, 10-20 s, and so on.
            int bucket = Math.min(bucketCount - 1, (int) (outcome.completed / 10));
            buckets[bucket][outcome.ok ? 0 : 1]++;
            if (outcome.ok) {
                success++;
                perResort[outcome.resort]++;
                if (outcome.phase.equals("warmup")) warmSuccess++;
            }
        }
        try (BufferedWriter csv = Files.newBufferedWriter(config.output.resolve("swipes.csv"));
             BufferedWriter detail = Files.newBufferedWriter(config.output.resolve("attempts.csv"))) {
            csv.write("timestamp,requestType,latency,statusCode,resortID\n");
            detail.write("eventID,phase,attempt,startEpochMs,endEpochMs,latencyMs,statusCode,resortID,error\n");
            for (Attempt a : attempts) {
                csv.write(String.format("%d,SWIPE,%.6f,%d,%d%n", a.start, a.latency, a.status, a.resort));
                detail.write(String.format("%d,%s,%d,%d,%d,%.6f,%d,%d,%s%n",
                    a.id, a.phase, a.number, a.start, a.end, a.latency, a.status, a.resort, a.error));
            }
        }
        try (BufferedWriter csv = Files.newBufferedWriter(config.output.resolve("outcomes.csv"))) {
            csv.write("eventID,phase,resortID,success,finalStatus,attempts,totalLatencyMs,completionSeconds\n");
            for (int i = 0; i < outcomes.length; i++) {
                Outcome o = outcomes[i];
                csv.write(String.format("%d,%s,%d,%s,%d,%d,%.6f,%.6f%n",
                    i, o.phase, o.resort, o.ok, o.status, o.attempts, o.latency, o.completed));
            }
        }
        try (BufferedWriter csv = Files.newBufferedWriter(config.output.resolve("throughput.csv"))) {
            csv.write("startSeconds,endSeconds,successfulSwipes,failedSwipes,swipesPerSecond\n");
            for (int i = 0; i < bucketCount; i++) {
                // The final bucket may be shorter than ten seconds.
                double end = Math.min(runSeconds, (i + 1) * 10.0);
                csv.write(String.format("%.3f,%.3f,%d,%d,%.6f%n", i * 10.0, end,
                    buckets[i][0], buckets[i][1], buckets[i][0] / (end - i * 10.0)));
            }
        }
        StringBuilder report = new StringBuilder();
        report.append(config.smoke ? "SMOKE TEST ONLY: NOT an official assignment run\n" : "FULL 500K RUN\n");
        report.append("Server: ").append(config.base).append('\n').append(probe);
        report.append(String.format("Generation: %.3f s (excluded from load runtime)\n"
            + "Successful swipes: %d\nFailed swipes: %d\n"
            + "HTTP attempts: %d; retries: %d\n"
            + "Warmup: %d events, %d successful, %.3f s, %.2f successful swipes/s\n"
            + "Main: %d events, %d successful, %d threads, %.3f s, %.2f successful swipes/s\n"
            + "Total load wall-clock time: %.3f s\nOverall throughput: %.2f successful swipes/s\n",
            generation, success, config.total - success, attempts.size(), attempts.size() - config.total,
            config.warmCount(), warmSuccess, warm, warmSuccess / warm,
            config.total - config.warmCount(), success - warmSuccess, config.threads,
            main, (success - warmSuccess) / main, runSeconds, success / runSeconds));
        report.append(stats("HTTP attempt latency (includes failed attempts)",
            attempts.stream().mapToDouble(a -> a.latency).toArray()));
        report.append(stats("Logical swipe latency (includes retries/backoff)", logical));
        for (int resort = 1; resort <= config.resorts; resort++) {
            report.append(String.format("Resort %d: %d successful, %.2f swipes/s%n",
                resort, perResort[resort], perResort[resort] / runSeconds));
        }
        List<Double> pushLatencies = new ArrayList<>();
        int totalConnections = 0, reconnects = 0;
        try (BufferedWriter csv = Files.newBufferedWriter(config.output.resolve("pushes.csv"));
             BufferedWriter connections = Files.newBufferedWriter(config.output.resolve("connections.csv"))) {
            csv.write("subscriberID,resortID,receiveTime,serverTimestamp,latencyMs\n");
            connections.write("subscriberID,resortID,connectionNumber,establishmentMs\n");
            for (Subscriber sub : subscribers) {
                int n = sub.connections.get();
                totalConnections += n;
                reconnects += Math.max(0, n - 1);
                report.append(String.format("Subscriber %d, resort %d: %d updates, %d connections, "
                    + "%d reconnects, %d connection failures, %d malformed messages%n",
                    sub.id, sub.resort, sub.pushes.size(), n, Math.max(0, n - 1),
                    sub.connectionFailures.get(), sub.malformed.get()));
                report.append(stats("  Establishment time", sub.establishment.stream().mapToDouble(x -> x).toArray()));
                if (!sub.lastError.isEmpty()) report.append("  Last error: ").append(sub.lastError).append('\n');
                int number = 0;
                for (double elapsed : sub.establishment) {
                    connections.write(String.format("%d,%d,%d,%.6f%n", sub.id, sub.resort, ++number, elapsed));
                }
                for (Push push : sub.pushes) {
                    pushLatencies.add(push.latency);
                    csv.write(String.format("%d,%d,%s,%s,%.6f%n",
                        sub.id, sub.resort, push.received, push.sent, push.latency));
                }
            }
        }
        report.append(String.format("Total leaderboard updates: %d\nWebSocket connections: %d; reconnects: %d\n",
            pushLatencies.size(), totalConnections, reconnects));
        report.append(stats("Push latency", pushLatencies.stream().mapToDouble(x -> x).toArray()));
        report.append("Push latency compares different machines' wall clocks and includes clock skew. "
            + "Negative values are retained, not clamped.\n"
            + "HTTP requests share a keep-alive HttpClient; its public API does not expose actual "
            + "TCP connection counts. Reported connections/reconnections refer to WebSockets.\n"
            + "Load runtime excludes probe, event generation, initial WS setup and result export.\n"
            + "CSV rows are unordered under concurrency. swipes.csv has one row per HTTP attempt; "
            + "outcomes.csv has exactly one row per generated event. HTTP status 0 means no response.\n"
            + "A final partial throughput bucket uses its actual duration.\n");
        Files.writeString(config.output.resolve("summary.txt"), report.toString());
        System.out.print(report);
        System.out.println("Results saved to: " + config.output.toAbsolutePath());
    }

    private static String stats(String label, double[] values) {
        if (values.length == 0) return label + ": no samples\n";
        Arrays.sort(values);
        return String.format("%s (ms): mean=%.3f median=%.3f p95=%.3f p99=%.3f min=%.3f max=%.3f%n",
            label, Arrays.stream(values).average().orElse(0), percentile(values, .5),
            percentile(values, .95), percentile(values, .99), values[0], values[values.length - 1]);
    }

    // If a percentile falls between two sorted values, interpolate between them.
    private static double percentile(double[] sorted, double fraction) {
        double index = fraction * (sorted.length - 1);
        int low = (int) index;
        int high = Math.min(low + 1, sorted.length - 1);
        return sorted[low] + (sorted[high] - sorted[low]) * (index - low);
    }

    private static double secondsSince(long start) { return (System.nanoTime() - start) / 1e9; }

    // Defaults match the full assignment run; command-line arguments can override them.
    private static class Config {
        String base = "http://localhost:8080/ski_servlet_war_exploded2";
        int threads = 128, resorts = 10, subscriberCount = 10, season = 2026, day = 1;
        int total = 500000, warmThreads = 32, warmEach = 1000;
        boolean probeOnly, smoke, help;
        Path output = Path.of("results", "run-" + System.currentTimeMillis());
        Config(String[] args) {
            boolean customSubscribers = false;
            for (int i = 0; i < args.length; i++) {
                String key = args[i];
                if (key.equals("--help")) { help = true; continue; }
                if (key.equals("--probe-only")) { probeOnly = true; continue; }
                if (key.equals("--smoke")) { smoke = true; continue; }
                if (i + 1 >= args.length) throw new IllegalArgumentException("Missing value: " + key);
                String value = args[++i];
                switch (key) {
                    case "--base-url": base = value.replaceAll("/+$", ""); break;
                    case "--threads": threads = Integer.parseInt(value); break;
                    case "--resorts": resorts = Integer.parseInt(value); break;
                    case "--subscribers": subscriberCount = Integer.parseInt(value); customSubscribers = true; break;
                    case "--season": season = Integer.parseInt(value); break;
                    case "--day": day = Integer.parseInt(value); break;
                    case "--output": output = Path.of(value); break;
                    default: throw new IllegalArgumentException("Unknown option: " + key);
                }
            }
            if (!customSubscribers) subscriberCount = resorts;
            if (smoke) { total = 200; warmThreads = 4; warmEach = 10; }
            if (threads < 1 || resorts < 1 || resorts > 100000 || subscriberCount < 1
                    || season < 1 || day < 1) throw new IllegalArgumentException("Invalid numeric option");
            URI uri = URI.create(base);
            if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
                    || uri.getHost() == null || uri.getQuery() != null || uri.getFragment() != null) {
                throw new IllegalArgumentException("Base URL must be http(s)://host:port/application-context");
            }
        }
        int warmCount() { return warmThreads * warmEach; }
    }

    // A generated ride. Its ID links HTTP attempts to the final outcome.
    private static class Swipe {
        final int id, resort, skier, lift, minute;
        Swipe(int id, int resort, int skier, int lift, int minute) {
            this.id = id; this.resort = resort; this.skier = skier; this.lift = lift; this.minute = minute;
        }
    }
    // One HTTP send, including a retry if the first send failed.
    private static class Attempt {
        final int id, resort, number, status;
        final String phase, error;
        final long start, end;
        final double latency;
        Attempt(int id, int resort, String phase, int number, long start, long end,
                double latency, int status, String error) {
            this.id=id; this.resort=resort; this.phase=phase; this.number=number;
            this.start=start; this.end=end; this.latency=latency; this.status=status; this.error=error;
        }
    }
    // Final result of a ride; total latency includes retries and waiting between retries.
    private static class Outcome {
        final int resort, status, attempts;
        final String phase;
        final boolean ok;
        final double completed, latency;
        Outcome(int resort, String phase, boolean ok, int status, int attempts, double completed, double latency) {
            this.resort=resort; this.phase=phase; this.ok=ok; this.status=status;
            this.attempts=attempts; this.completed=completed; this.latency=latency;
        }
    }
    // One complete leaderboard message and its timing measurement.
    private static class Push {
        final Instant received, sent;
        final double latency;
        Push(Instant received, Instant sent, double latency) {
            this.received=received; this.sent=sent; this.latency=latency;
        }
    }
}

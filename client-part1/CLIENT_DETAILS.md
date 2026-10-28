# SwipeClient: running the load client

This file describes implementation choices and run instructions, not measured performance.

## IntelliJ

1. Keep Tomcat running with the latest server deployed.
2. Open `src/main/java/SwipeClient.java` and run its `main` method. It needs Java 11 and the project's existing Gson dependency; no new Maven dependency is required.
3. First edit the SwipeClient run configuration's **Program arguments** to:

```
--smoke --threads 8 --output results/local-smoke
```

The smoke test makes 20 separate probe requests followed by 200 load events. Its reduced warmup is 4 threads x 10 events. It is NOT an official assignment result.

4. For the actual 500,000-event run, remove `--smoke`:

```
--threads 128 --output results/local-500k
```

5. For EC2, set the base URL to the remote application context (do not include `/skiers` or `/health`):

```
--base-url http://YOUR_EC2_IP:8080/assignment1 --threads 128 --output results/ec2-500k
```

The default local base URL is `http://localhost:8080/ski_servlet_war_exploded2`.

Use a NEW output directory for each run; reusing one overwrites its result files. IntelliJ's working directory controls where the relative `results` folder is created. For the full run, optionally set VM options to `-Xmx1g` if the IDE chooses a small heap.

## Options

- `--probe-only`: send 20 sequential requests, save `prediction.txt`, and exit before load generation/subscription.
- `--threads N`: main-phase sender threads; default 128. Official warmup remains 32 threads x 1,000 swipes.
- `--resorts N`: random resort range 1..N; default 10.
- `--subscribers N`: persistent WebSocket subscriptions; default one per resort. Extra subscribers cycle through resorts.
- `--season N`, `--day N`: positive fixed IDs for the run; defaults 2026 and 1.
- `--output PATH`: output folder.
- `--help`: print usage without making network requests.

## Behavior and measurements

- The 20 probe requests are EXTRA and excluded from the 500,000 load events. Their first-request time includes initial connection setup; the next 19 use the shared HTTP client. Predictions assume one in-flight request per sender and baseline RTT remaining constant. They are not a claim that the target is achievable under load.
- One dedicated generator pre-fills a thread-safe queue with all events before senders start, so senders never wait for generation. This trades memory for predictable sending. Generation is measured separately.
- Warmup consumes 32,000 load events. The main phase sends the remaining 468,000, using configurable threads. A logical event completes after a 201 response or exhaustion of its retries.
- All HTTP sender threads share one Java HttpClient (HTTP/1.1, keep-alive). Each attempt has a 10-second timeout. Failures get up to FIVE retries AFTER the initial attempt (six attempts maximum), waiting 100, 200, 400, 800 and 1,600 ms between attempts.
- Each subscriber connects to its resort before load starts, receives JSON messages, and reconnects after errors/closure with capped exponential backoff. All requested initial subscriptions must connect within a shared 25-second startup deadline, otherwise the run aborts rather than silently testing HTTP alone.
- Load wall-clock runtime includes warmup and main phases, but excludes the probe, generation, initial subscription setup and result-file export. Logical swipe throughput is successful events / load runtime. Retries never count as new swipes.
- HTTP latency uses a monotonic clock. Push latency uses receipt wall time minus serverTimestamp; it includes clock skew. Negative samples are preserved. Subscriber metrics cover the subscription lifetime, including the brief setup before ingest.
- Percentiles use linear interpolation between sorted samples. All HTTP attempts, including failures, contribute to HTTP attempt latency; logical event latency (including retry waits) is reported separately.
- WebSocket connection attempts that fail, successful establishments, reconnects and per-subscriber establishment times are recorded. Java HttpClient's public API does NOT expose actual HTTP TCP connection/reconnection counts; no fabricated HTTP counts are printed. Obtain transport-level HTTP counts separately if required by the instructor.
- Metrics are buffered in memory, then written after the measured load. The server's per-request logging may affect performance; document the actual environment and any tuning.

## Output files

- `prediction.txt`: measured single-request timings and Little's Law estimates, written before the load test.
- `summary.txt`: success/failure counts, warmup/main runtime and throughput, per-resort throughput, latency statistics, subscriber counts and connection statistics.
- `swipes.csv`: the required five columns (`timestamp,requestType,latency,statusCode,resortID`), ONE row per HTTP attempt. Timestamp is epoch milliseconds; latency is milliseconds. Status 0 means no HTTP response.
- `attempts.csv`: event IDs, phase, attempt number, send/response timestamps and errors. Use eventID to relate retries to one generated event.
- `outcomes.csv`: exactly one row per logical load event, with final status, total attempts, elapsed latency including retries, and completion time relative to ingest start.
- `pushes.csv`: each subscriber's received and server timestamps and approximate one-way latency.
- `connections.csv`: successful WebSocket connection establishment times by subscriber and connection number.
- `throughput.csv`: successful/failed event counts in 10-second buckets over the whole load run. The final partial bucket uses its actual duration.

CSV records from concurrent senders are not chronological; sort by timestamp for analyses that require ordering.

## Throughput chart

After a run, use Python 3 (no extra package required for SVG output):

```
python3 plot_throughput.py results/ec2-500k
```

It creates `throughput.svg`, which you can open in a browser or insert into a document. If matplotlib is installed, it also creates `throughput.png` and `throughput.pdf`. A short run may produce just one bucket; it is plotted as a point.

## Remaining submission work

This client does not deploy the server, create genuine benchmark results before running, or write the design report. Complete EC2 deployment, run the official workload, inspect success/failure counts and subscriber messages, compare actual throughput with predictions, and capture screenshots. Organize the final repository as required (`server`, `client-part1`, `client-part2`, `results`) and provide the two-page design document plus submission PDF. This working project currently keeps the client in the existing server project for easy IDE execution; that is not the final repository layout.

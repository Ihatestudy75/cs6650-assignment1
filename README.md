# CS6650 Assignment 1 — SkiTrak

Repository: https://github.khoury.northeastern.edu/yufanwang/cs6650-assignment1

## Repository layout

- `server/`: Java 11 / Tomcat 9 application, packaged as a single WAR.
- `client-part1/`: HTTP load client plus persistent leaderboard subscribers and metrics.
- `client-part2/`: the same measured client implementation with full analysis exports, plus the throughput plotting script. Both directories intentionally use the same implementation rather than claiming separate historical versions.
- `results/`: original EC2 full-run data, throughput chart and supporting test summaries.

Read each module's README for build/run instructions. The original development project is preserved outside this submission folder. This folder is the Git repository root.

## Recorded EC2 run

The client ran on the student's Mac against `http://44.244.89.76:8080/assignment1`, using 128 main sender threads and 10 WebSocket subscribers. The address identifies the recorded run; EC2 public IPs may change after an instance restart.

| Metric | Recorded value |
| --- | ---: |
| Successful / failed load events | 500,000 / 0 |
| Retries | 0 |
| Warmup | 32 threads x 1,000 events, 46.501 s |
| Main phase | 468,000 events, 203.486 s |
| Overall elapsed load time | 249.991 s |
| Overall throughput | 2,000.08 successful swipes/s |
| Main throughput | 2,299.92 successful swipes/s |
| HTTP latency mean / median | 54.133 / 42.129 ms |
| HTTP latency p95 / p99 | 126.961 / 179.331 ms |
| Leaderboard messages | 5,000 (500 per subscriber) |
| WebSocket connections / reconnects | 10 / 0 |

Twenty additional sequential probe requests are excluded from the 500,000 load events. See `results/ec2-500k-01/summary.txt` and `prediction.txt` for definitions and values. The recorded main-phase Little's Law estimate was 4,305.52 swipes/s; actual main throughput reached about 53.4% of that baseline estimate. This is one measured configuration, not proof of an optimal thread count.

The primary run is the later run (249.991 s, 5,000 messages). The earlier run (205.884 s, 4,120 messages) is preserved under `results/archive/ec2-500k-earlier/`. Do not mix its statistics or screenshots with the primary run.

The console screenshot identifies the server instance as **t3.micro** in **us-west-2a**. This records the actual environment rather than assuming the t2.micro example in the assignment.

## Measurement limitations

Push timestamps compare different machines' clocks. Mean measured push latency was -9.169 ms, showing that clock offset affects the estimate; these samples cannot be interpreted as true negative network latency. Raw values are retained. The code measures WebSocket connections/reconnections; Java HttpClient's public API does not expose actual HTTP TCP connection counts. Generation, probes, initial subscriber setup and export are excluded from load runtime. See client documentation for complete measurement definitions.

## Plot

From the repository root:

```
python3 client-part2/plot_throughput.py results/ec2-500k-01
```

This always creates SVG, and also PNG/PDF if matplotlib is available. The recorded run already includes all three chart formats.

## Submission materials

Four actual screenshots are saved in `results/screenshots/`, including the primary run's latency output (5,000 leaderboard messages). The submission PDF should include the repository URL above, a design document of at most two pages, and test results/screenshots/chart.

The endpoint source and client source are copied from the completed working project. This organization step does not create new benchmark results.

## AI assistance disclosure

I used OpenAI Codex during this assignment to explain Java, HTTP, WebSockets, and the assignment requirements, and to help debug build and deployment problems. Codex generated the full load-client implementation in `SwipeClient.java` and the throughput plotting script. It also provided server code examples and fixes, added code comments, organized the submission directories, and drafted README files and report text.

I ran the application and load tests, deployed the server to EC2, and captured the screenshots. The submitted CSV files and benchmark results came from those actual runs; they were not generated or fabricated by AI.

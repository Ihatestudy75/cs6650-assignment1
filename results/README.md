# Test results

## Primary run: ec2-500k-01

The later EC2 test completed 500,000 successful events, zero failures and zero retries in 249.991 seconds. Overall throughput was 2,000.08 swipes/s; main throughput was 2,299.92 swipes/s. Ten subscribers received 500 messages each (5,000 total). Its CSV, summary, prediction and regenerated SVG/PNG/PDF charts are copied unchanged from the working project.

- `swipes.csv`: one row per HTTP attempt (500,000 in this run).
- `attempts.csv`: send/receive timestamps, event IDs and retry metadata.
- `outcomes.csv`: one row per logical event.
- `throughput.csv`: successful completions per 10-second bucket; actual duration for the last partial bucket.
- `pushes.csv`: raw clock-skew-sensitive push measurements (5,000 rows).
- `connections.csv`: successful WebSocket establishment times.

## History

`archive/ec2-500k-earlier/` preserves the earlier run: 205.884 seconds, 4,120 messages. Its screenshot and data belong to that historical run and are not the primary submission figures. Other supporting folders contain summaries; original raw supporting CSVs remain in the working project.

## Screenshots

Four actual screenshots are saved in `screenshots/`: current basic metrics, current latency metrics, EC2 console and remote WebSocket. The current latency screenshot shows 5,000 messages and matches the primary run. The earlier 4,120-message screenshot remains archived separately.

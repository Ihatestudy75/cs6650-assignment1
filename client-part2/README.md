# client-part2

This standalone Maven module contains the measured Java 11 load-client implementation.
Both client directories intentionally contain the same implementation: it satisfies basic ingest/subscribe metrics and also exports detailed analysis. They are not claimed to be separate historical benchmark versions. The saved EC2 run was produced by this implementation in the original working project.

## Run

Open this folder's `pom.xml` in IntelliJ, select JDK 11, and run `SwipeClient.main()` with these program arguments:

```
--base-url http://YOUR_EC2_IP:8080/assignment1 --smoke --threads 8 --output ../results/new-smoke
```

For the official workload, remove `--smoke` and choose a new result directory:

```
--base-url http://YOUR_EC2_IP:8080/assignment1 --threads 128 --output ../results/new-ec2-500k
```

Set the IntelliJ working directory to this module folder. Do not overwrite `results/ec2-500k-01`, which contains the saved measured results.

Alternatively, from this module folder on macOS/Linux:

```
mvn clean package
java -Xmx1g -cp "target/classes:target/dependency/*" SwipeClient --base-url http://YOUR_EC2_IP:8080/assignment1 --threads 128 --output ../results/new-ec2-500k
```

Windows uses `;` instead of `:` in the Java classpath. First-time Maven builds require downloading dependencies.

See `CLIENT_DETAILS.md` for options, timing definitions, retry behavior, CSV schema and limitations. Its references to the original combined IntelliJ project describe the development setup; this directory is the standalone submission copy.

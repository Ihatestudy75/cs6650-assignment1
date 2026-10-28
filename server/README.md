# SkiTrak server

Java 11, Tomcat 9, one WAR with REST swipe ingest, health and synthetic WebSocket leaderboard endpoints. Database persistence and real aggregation are outside Assignment 1.

## Build and deploy

From this folder:

```
mvn clean package
```

The build produces `target/assignment1.war`. Upload it to your own EC2 instance in us-west-2 (Amazon Linux example):

```
scp -i /path/to/key.pem target/assignment1.war ec2-user@YOUR_EC2_IP:/tmp/assignment1.war
ssh -i /path/to/key.pem ec2-user@YOUR_EC2_IP
cp /tmp/assignment1.war /opt/tomcat9/webapps/assignment1.war
```

Use `sudo cp` only if the destination requires it. On a fresh/stopped Tomcat, start it with `/opt/tomcat9/bin/startup.sh`. For an already running Tomcat, wait for automatic deployment and inspect `/opt/tomcat9/logs/catalina.out`. Follow the lab's screen/systemd instructions to keep Tomcat running when the SSH session closes. Allow inbound 8080 and SSH only from the testing machine's IP, as specified in the lab.

The application context is `/assignment1` because the deployed file is `assignment1.war`. In IntelliJ, configure a Tomcat 9 WAR-exploded artifact using this module's `web` directory and an application context of `/assignment1`.

## Verify

```
curl -i http://YOUR_EC2_IP:8080/assignment1/health
curl -i -X POST http://YOUR_EC2_IP:8080/assignment1/skiers/7/seasons/2026/days/1/skiers/123 -H 'Content-Type: application/json' -d '{"time":217,"liftID":21}'
```

Health returns 200 and `{"status":"UP"}`. Valid swipes return 201. Invalid IDs, out-of-range time/liftID or malformed bodies return 400. Test the lower/upper boundaries and invalid cases separately.

In Postman create a WebSocket request to `ws://YOUR_EC2_IP:8080/assignment1/leaderboard/7`. Connect without sending a message; expect JSON pushes every 500 ms containing resortID, a fresh serverTimestamp and a fixed top list. Disconnect/reconnect to check lifecycle logging.

## Measured-run provenance

The recorded EC2 test used the WAR built in the original combined Lab 2-derived project, uploaded under the name `assignment1.war`. This standalone server copy preserves its endpoint source and web resources, while its Maven package excludes the client/echo exercises and their client-only dependency. The repackaged WAR has not been redeployed or benchmarked as part of this directory cleanup; the saved performance results belong to the original deployment.

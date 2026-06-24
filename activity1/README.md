I did this with Java 21 because the newer Java on my machine kept throwing some
"Unsupported class file major version" error with the starter Gradle

Run the threaded server:
```bash
gradle runThreadedServer
```

Then start a client:
```bash
gradle runClient
```

I switched the protocol over from JSON to protobuf so it uses the generated
classes from `task.proto` and sends it as binary instead. I also made the
server threaded so each client runs on its own thread and they don't
have to wait for each other. Since the task list is shared now I had to make it
thread safe so I used a synchronized list and synchronized the methods that
touch it otherwise two clients could write at the same time and mess it up.

I left a delay in there during testing so the
threading was easier to see on the video

Video: https://youtu.be/JdawwSPXYwU

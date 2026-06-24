Start the server:
```bash
gradle runServer
```

Then start a client:
```bash
gradle runClient
```

For testing you start the server in grading mode and run the tests in a second
terminal:
```bash
gradle runServer --args="--grading"
gradle test
```

I added a thread pool so the server can handle a few clients at the same time
without them blocking each other. Players register a name first and I don't let
two active players use the same name, then they join and play against the bots.
The bidding checks the reserve price and picks a winner per item, and at the end
it saves the final score to the leaderboard so it sticks around between restarts.
I synchronized the leaderboard part too so two games finishing at once don't
overwrite each other. I also added a few more protocol tests on top of the
starter ones, basically for joining, a bad bid, and the leaderboard.

The main thing that got me was the order I added things in, a few cases wouldn't
build until I went back and added the helper methods they needed. Other than
that I don't think there are any issues, I tested one player, a few players at
once, and the leaderboard staying after a restart and it all worked.

Video: https://youtu.be/JdawwSPXYwU

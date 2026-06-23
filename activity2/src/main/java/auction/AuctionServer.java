package auction;

import buffers.*;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Auction Game Server - Players compete against bot opponents.
 * Each player plays independently against 3 bots.
 */
public class AuctionServer 
{
    private static final int DEFAULT_PORT = 8889;
    private static final String SCORES_FILE = "scores.txt";

    private static final int initialGold = 150;

    // Shared leaderboard
    private static LeaderboardManager leaderboard;

    // Track connected player names (to prevent duplicates)
    private static Set<String> activePlayerNames = Collections.synchronizedSet(new HashSet<>());

    // Grading mode flag
    private static boolean gradingMode = false;

    // Bot opponent name pool
    private static final String[] BOT_NAMES = {
            "Alaric", "Brynn", "Cedric", "Daphne",
            "Elara", "Finn", "Gwen", "Hugo",
            "Isolde", "Jasper"
    };
    private static Random botNameRandom = new Random();

    public static void main(String[] args) {
        int port = DEFAULT_PORT;

        // Parse command line arguments
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--grading")) {
                gradingMode = true;
                System.out.println("Running in grading mode (deterministic results)");
            } else {
                try {
                    port = Integer.parseInt(args[i]);
                } catch (NumberFormatException e) {
                    System.err.println("Invalid port number: " + args[i]);
                }
            }
        }

        // Initialize leaderboard
        leaderboard = new LeaderboardManager(SCORES_FILE);
        System.out.println("Leaderboard loaded with " + leaderboard.size() + " scores");


        ExecutorService pool = Executors.newFixedThreadPool(10);

        try (ServerSocket serverSocket = new ServerSocket(port)) {
            System.out.println("Auction Server started on port " + port);
            System.out.println("Waiting for connections...");

            int clientId = 0;
            while (true) {
                try {
                    Socket clientSocket = serverSocket.accept();
                    clientId++;
                    final int id = clientId;
                    System.out.println("Client " + id + " connected from " +
                            clientSocket.getInetAddress().getHostAddress());

                    pool.submit(() -> processConnection(clientSocket, id));
                } catch (IOException e) {
                    System.err.println("Error accepting client: " + e.getMessage());
                }
            }
        } catch (IOException e) {
            System.err.println("Server error: " + e.getMessage());
        }
    }

    /**
     * Handle a client connection (runs in thread pool).
     */
    private static void processConnection(Socket clientSocket, int clientId) {
        String playerName = null;
        PlayerGameState gameState = null;

        try (InputStream in = clientSocket.getInputStream();
             OutputStream out = clientSocket.getOutputStream()) {

            System.out.println("[Client " + clientId + "] Handler started");

            // Send initial welcome
            sendWelcome(out, "Welcome to the Auction Game! Please set your name.");

            // Read and process requests
            Request request;
            while ((request = Request.parseDelimitedFrom(in)) != null) {
                Request.RequestType type = request.getType();
                System.out.println("[Client " + clientId + "] Received: " + type);

                Response response = null;

                switch (type) {
                    case REGISTER:
                        String[] result = handleRegister(request, playerName);
                        playerName = result[0];
                        String message = result[1];
                        if (playerName != null) {
                            response = buildWelcome("Welcome, " + playerName + "! You have " + initialGold + " gold. " +
                                    "Type 'join' to start playing against bot opponents!");
                        } else {
                            response = buildError(message);
                        }
                        break;
                    case JOIN:
                        if (playerName == null)
                        {
                            response = buildError("Please register before joining a game.");
                        }
                        else if (gameState != null)
                        {
                            response = buildError("You are already in a game.");
                        }
                        else
                        {
                            gameState = new PlayerGameState(playerName, gradingMode);
                            response = buildGameJoined(gameState);
                        }
                        break;
                    case BID:
                        if (gameState == null)
                        {
                            response = buildError("Please join a game before bidding.");
                        }
                        else
                        {
                            response = handleBid(request, gameState);
                        }
                        break;
                    case LEADERBOARD:
                        response = Response.newBuilder()
                                .setType(Response.ResponseType.LEADERBOARD_RESPONSE)
                                .setOk(true)
                                .setMessage("Top scores:")
                                .setLeaderboard(Leaderboard.newBuilder()
                                        .addAllEntries(leaderboard.getTopScores(10))
                                        .build())
                                .build();
                        break;
                    case QUIT:
                        response = handleQuit(gameState);
                        if (response != null) {
                            response.writeDelimitedTo(out);
                        }
                        return; // Exit handler

                    default:
                        response = buildError("Unknown request type");
                }

                if (response != null)
                {
                    response.writeDelimitedTo(out);

                    if (response.getType() == Response.ResponseType.BID_RESULT
                            && response.getOk()
                            && !response.hasNextItem()
                            && gameState != null)
                    {
                        buildGameOver(gameState).writeDelimitedTo(out);
                        gameState = null;
                    }
                }
            }

            System.out.println("[Client " + clientId + "] Disconnected");

        } catch (IOException e) {
            System.err.println("[Client " + clientId + "] Error: " + e.getMessage());
        } finally {
            // Cleanup
            if (playerName != null) {
                activePlayerNames.remove(playerName);
                System.out.println("[Client " + clientId + "] Removed player: " + playerName);
            }
            try {
                clientSocket.close();
            } catch (IOException e) {
                // Ignore
            }
        }
    }

    /**
     * Handle REGISTER request - set player name.
     * Returns [playerName, errorMessage] - playerName is null if error.
     */
    private static String[] handleRegister(Request request, String currentName) {
        String name = request.getName().trim();

        if (currentName != null) 
        {
            return new String[]{null, "You are already registered."};
        }

        if (name.isEmpty()) {
            return new String[]{null, "Name cannot be empty"};
        }

        synchronized (activePlayerNames)
        {
            if (activePlayerNames.contains(name)) {
                return new String[]{null, "Name already taken. Please choose another."};
            }

            // Add new name
            activePlayerNames.add(name);
        }
        return new String[]{name, null};
    }

    /**
     * Handle QUIT request.
     */
    private static Response handleQuit(PlayerGameState gameState) {
        String message = "Thanks for playing!";
        if (gameState != null) {
            message += " Final score: " + gameState.getPlayerScore() + ".";
        }
        message += " Goodbye!";

        return Response.newBuilder()
                .setType(Response.ResponseType.FAREWELL)
                .setOk(true)
                .setMessage(message)
                .build();
    }

    /**
     * Handle BID request - run one auction round against bots.
     */
    private static Response handleBid(Request request, PlayerGameState gameState)
    {
        String error = gameState.validateBid(request.getItemId(), request.getBidAmount());
        if (error != null)
        {
            return buildError(error);
        }

        Item item = gameState.getCurrentItem();
        int reservePrice = item.getMinValue() / 2;
        int playerBid = request.getBidAmount() == -1 ? 0 : request.getBidAmount();

        BotOpponent bot1 = gameState.getBot1();
        BotOpponent bot2 = gameState.getBot2();
        BotOpponent bot3 = gameState.getBot3();

        Map<String, Integer> bids = new HashMap<>();
        bids.put(gameState.getPlayerName(), playerBid);
        bids.put(bot1.getName(), bot1.decideBid(item, reservePrice));
        bids.put(bot2.getName(), bot2.decideBid(item, reservePrice));
        bids.put(bot3.getName(), bot3.decideBid(item, reservePrice));

        String winnerName = "(unsold)";
        int winningBid = 0;

        for (Map.Entry<String, Integer> bid : bids.entrySet())
        {
            int amount = bid.getValue();
            String name = bid.getKey();

            if (amount >= reservePrice && (amount > winningBid || (amount == winningBid && name.compareTo(winnerName) < 0)))
            {
                winnerName = name;
                winningBid = amount;
            }
        }

        if (winnerName.equals(gameState.getPlayerName()))
        {
            gameState.awardItemToPlayer(item, winningBid);
        }
        else if (winnerName.equals(bot1.getName()))
        {
            bot1.awardItem(item, winningBid);
        }
        else if (winnerName.equals(bot2.getName()))
        {
            bot2.awardItem(item, winningBid);
        }
        else if (winnerName.equals(bot3.getName()))
        {
            bot3.awardItem(item, winningBid);
        }

        List<PlayerBid> allBids = new ArrayList<>();
        for (Map.Entry<String, Integer> bid : bids.entrySet())
        {
            allBids.add(PlayerBid.newBuilder().setPlayerName(bid.getKey()).setBidAmount(bid.getValue()).build());
        }

        AuctionResult result = AuctionResult.newBuilder()
                .setItem(itemToProto(item))
                .setActualValue(item.getActualValue())
                .setWinnerName(winnerName)
                .setWinningBid(winningBid)
                .addAllAllBids(allBids)
                .build();

        boolean hasMoreItems = gameState.moveToNextItem();

        Response.Builder response = Response.newBuilder()
                .setType(Response.ResponseType.BID_RESULT)
                .setOk(true)
                .setMessage(hasMoreItems ? "Auction complete!" : "Auction complete! Calculating final scores...")
                .setResult(result)
                .setPlayerStatus(PlayerStatus.newBuilder()
                        .setPlayerName(gameState.getPlayerName())
                        .setGoldRemaining(gameState.getGold())
                        .setItemsValue(gameState.getInventoryValue())
                        .setTotalScore(gameState.getPlayerScore())
                        .build());

        if (hasMoreItems)
        {
            response.setNextItem(itemToProto(gameState.getCurrentItem()));
        }

        return response.build();
    }

    /**
     * Helper: send welcome response.
     */
    private static void sendWelcome(OutputStream out, String message) throws IOException {
        buildWelcome(message).writeDelimitedTo(out);
    }

    /**
     * Helper: build welcome response.
     */
    private static Response buildWelcome(String message) {
        return Response.newBuilder()
                .setType(Response.ResponseType.WELCOME)
                .setOk(true)
                .setMessage(message)
                .build();
    }

    /**
     * Helper: build game over response.
     */
    private static Response buildGameOver(PlayerGameState gameState)
    {
        int playerScore = gameState.getPlayerScore();
        int leaderboardRank = leaderboard.addScore(gameState.getPlayerName(), playerScore);

        BotOpponent bot1 = gameState.getBot1();
        BotOpponent bot2 = gameState.getBot2();
        BotOpponent bot3 = gameState.getBot3();

        // build all four PlayerStatus entries
        PlayerStatus playerStatus = PlayerStatus.newBuilder()
                .setPlayerName(gameState.getPlayerName())
                .setGoldRemaining(gameState.getGold())
                .setItemsValue(gameState.getInventoryValue())
                .setTotalScore(playerScore)
                .addAllItemsWon(gameState.getItemNames())
                .build();

        PlayerStatus bot1Status = PlayerStatus.newBuilder()
                .setPlayerName(bot1.getName())
                .setGoldRemaining(bot1.getGold())
                .setItemsValue(bot1.getInventoryValue())
                .setTotalScore(bot1.getTotalScore())
                .addAllItemsWon(bot1.getItemNames())
                .build();

        PlayerStatus bot2Status = PlayerStatus.newBuilder()
                .setPlayerName(bot2.getName())
                .setGoldRemaining(bot2.getGold())
                .setItemsValue(bot2.getInventoryValue())
                .setTotalScore(bot2.getTotalScore())
                .addAllItemsWon(bot2.getItemNames())
                .build();

        PlayerStatus bot3Status = PlayerStatus.newBuilder()
                .setPlayerName(bot3.getName())
                .setGoldRemaining(bot3.getGold())
                .setItemsValue(bot3.getInventoryValue())
                .setTotalScore(bot3.getTotalScore())
                .addAllItemsWon(bot3.getItemNames())
                .build();

        // find overall winner by highest total score
        String winnerName = gameState.getPlayerName();
        int highScore = playerScore;

        if (bot1.getTotalScore() > highScore || (bot1.getTotalScore() == highScore && bot1.getName().compareTo(winnerName) < 0))
        {
            winnerName = bot1.getName();
            highScore = bot1.getTotalScore();
        }
        if (bot2.getTotalScore() > highScore || (bot2.getTotalScore() == highScore && bot2.getName().compareTo(winnerName) < 0))
        {
            winnerName = bot2.getName();
            highScore = bot2.getTotalScore();
        }
        if (bot3.getTotalScore() > highScore || (bot3.getTotalScore() == highScore && bot3.getName().compareTo(winnerName) < 0))
        {
            winnerName = bot3.getName();
            highScore = bot3.getTotalScore();
        }

        GameResult gameResult = GameResult.newBuilder()
                .addPlayerScores(playerStatus)
                .addPlayerScores(bot1Status)
                .addPlayerScores(bot2Status)
                .addPlayerScores(bot3Status)
                .setWinnerName(winnerName)
                .setLeaderboardPosition(leaderboardRank)
                .build();

        return Response.newBuilder()
                .setType(Response.ResponseType.GAME_OVER)
                .setOk(true)
                .setMessage("Game over! Winner: " + winnerName + ". Your rank: #" + leaderboardRank)
                .setGameResult(gameResult)
                .build();
    }

    /**
     * Helper: build game joined response.
     */
    private static Response buildGameJoined(PlayerGameState gameState)
    {
        return Response.newBuilder()
                .setType(Response.ResponseType.GAME_JOINED)
                .setOk(true)
                .setMessage("Game started! You are bidding against 3 bot opponents.")
                .setNextItem(itemToProto(gameState.getCurrentItem()))
                .setPlayerStatus(PlayerStatus.newBuilder()
                        .setPlayerName(gameState.getPlayerName())
                        .setGoldRemaining(gameState.getGold())
                        .setItemsValue(gameState.getInventoryValue())
                        .setTotalScore(gameState.getPlayerScore())
                        .build())
                .build();
    }

    /**
     * Helper: build error response.
     */
    private static Response buildError(String message) {
        return Response.newBuilder()
                .setType(Response.ResponseType.ERROR)
                .setOk(false)
                .setMessage(message)
                .build();
    }

    /**
     * Helper: convert Item to protobuf AuctionItem.
     * Includes reserve_price calculated as 50% of min_value.
     */
    private static AuctionItem itemToProto(Item item) {
        return AuctionItem.newBuilder()
                .setId(item.getId())
                .setName(item.getName())
                .setCategory(item.getCategory())
                .setMinValue(item.getMinValue())
                .setMaxValue(item.getMaxValue())
                .setReservePrice(item.getMinValue() / 2)
                .build();
    }

    /**
     * Helper: get random bot name.
     */
    private static String getRandomBotName() {
        return BOT_NAMES[botNameRandom.nextInt(BOT_NAMES.length)];
    }

    /**
     * Inner class to track player game state.
     */
    private static class PlayerGameState {
        private String playerName;
        private int gold;
        private List<Item> inventory;
        private List<Item> items;
        private int currentItemIndex;
        private BotOpponent bot1;
        private BotOpponent bot2;
        private BotOpponent bot3;

        public PlayerGameState(String playerName, boolean gradingMode) {
            this.playerName = playerName;
            this.gold = initialGold;
            this.inventory = new ArrayList<>();

            // Load items
            this.items = ItemLoader.loadItems(gradingMode);
            this.currentItemIndex = 0;

            // Create 3 bot opponents with unique names
            Set<String> usedNames = new HashSet<>();
            this.bot1 = createUniqueBot(usedNames, gradingMode);
            this.bot2 = createUniqueBot(usedNames, gradingMode);
            this.bot3 = createUniqueBot(usedNames, gradingMode);
        }

        private BotOpponent createUniqueBot(Set<String> usedNames, boolean gradingMode) {
            String name;
            do {
                name = getRandomBotName();
            } while (usedNames.contains(name));
            usedNames.add(name);
            return new BotOpponent(name, gradingMode);
        }

        /**
         * Validate a bid.
         * Returns null if valid, error message if invalid.
         * bid_amount of -1 means skip (treated as bid of 0).
         * Bids > 0 must meet the reserve price.
         */
        public String validateBid(int itemId, int bidAmount) {
            Item currentItem = getCurrentItem();

            if (currentItem.getId() != itemId) {
                return "Invalid item ID. Current item is #" + currentItem.getId();
            }

            // -1 means skip
            if (bidAmount == -1) {
                return null; // Valid skip
            }

            if (bidAmount < 0) {
                return "Bid cannot be negative (use -1 to skip)";
            }

            if (bidAmount > gold) {
                return "Insufficient gold. You have " + gold + " gold.";
            }

            // Check reserve price (bids > 0 must meet reserve)
            int reservePrice = currentItem.getMinValue() / 2;
            if (bidAmount > 0 && bidAmount < reservePrice) {
                return "Bid must meet reserve price of " + reservePrice + " gold.";
            }

            return null; // Valid
        }

        public void awardItemToPlayer(Item item, int bidAmount) {
            inventory.add(item);
            gold -= bidAmount;
        }

        public boolean moveToNextItem() {
            currentItemIndex++;
            return currentItemIndex < items.size();
        }

        public Item getCurrentItem() {
            return items.get(currentItemIndex);
        }

        public int getInventoryValue() {
            int total = 0;
            for (Item item : inventory) {
                total += item.getActualValue();
            }
            return total;
        }

        public int getPlayerScore() {
            return gold + getInventoryValue();
        }

        public List<String> getItemNames() {
            List<String> names = new ArrayList<>();
            for (Item item : inventory) {
                names.add(item.getName());
            }
            return names;
        }

        // Getters
        public String getPlayerName() { return playerName; }
        public int getGold() { return gold; }
        public List<Item> getInventory() { return new ArrayList<>(inventory); }
        public BotOpponent getBot1() { return bot1; }
        public BotOpponent getBot2() { return bot2; }
        public BotOpponent getBot3() { return bot3; }
    }
}

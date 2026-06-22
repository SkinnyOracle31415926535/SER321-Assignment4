package taskone;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * Task Management Server.
 * This server accepts client connections and handles requests.
 *
 * Convert this to a Multi-threaded server (one thread per client)
 */
public class Server {
    private static final int DEFAULT_PORT = 8888;
    private static TaskList taskList = new TaskList();

    public static void main(String[] args) {
        int port = DEFAULT_PORT;

        // Parse port from command line if provided
        if (args.length > 0) {
            try {
                port = Integer.parseInt(args[0]);
            } catch (NumberFormatException e) {
                System.err.println("Invalid port number. Using default port: " + DEFAULT_PORT);
                port = DEFAULT_PORT;
            }
        }

        System.out.println("Task Management Server starting on port " + port);
        System.out.println("Waiting for clients...");

        try (ServerSocket serverSocket = new ServerSocket(port)) {
            while (true) {
                // Accept connection (blocks until client connects)
                Socket clientSocket = serverSocket.accept();
                System.out.println("Client connected: " + clientSocket.getInetAddress().getHostAddress());

                // Handle client request (BLOCKS - single-threaded)
                Performer performer = new Performer(clientSocket, taskList);
                performer.doPerform();

                // Close connection
                clientSocket.close();
                System.out.println("Client disconnected");
            }
        } catch (IOException e) {
            System.err.println("Server error: " + e.getMessage());
            e.printStackTrace();
        }
    }
}

package taskone;

import java.io.*;
import java.net.Socket;
import java.util.List;

import taskone.proto.Request;
import taskone.proto.Response;
import taskone.proto.TaskProto;

/**
 * Performer class handles client requests using Protobuf protocol.
 */
public class Performer {
    private final Socket clientSocket;
    private final TaskList taskList;

    private InputStream inStream;
    private OutputStream outStream;

    public Performer(Socket clientSocket, TaskList taskList) {
        this.clientSocket = clientSocket;
        this.taskList = taskList;
    }

    /**
     * Main method to process client requests using Protobuf.
     */
    public void doPerform() {
        try {
            inStream = clientSocket.getInputStream();
            outStream = clientSocket.getOutputStream();

            // Send welcome message
            Response.newBuilder()
                    .setType(Response.ResponseType.SUCCESS)
                    .setMessage("Connected to Proto Task Management Server")
                    .build()
                    .writeDelimitedTo(outStream);
            outStream.flush();

            // Process requests
            while (true) {
                Request request = Request.parseDelimitedFrom(inStream);
                if (request == null) break;

                System.out.println(request.getType());

                // simulate a slow operation so concurrent clients are obvious in the demo
                try { Thread.sleep(20000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }

                Response response;
                switch (request.getType()) {
                    case ADD:
                        response = handleAdd(request);
                        break;
                    case LIST:
                        response = handleList(request);
                        break;
                    case FINISH:
                        response = handleFinish(request);
                        break;
                    case QUIT:
                        response = handleQuit();
                        break;
                    default:
                        response = Response.newBuilder()
                                .setType(Response.ResponseType.ERROR)
                                .setMessage("Unknown request type")
                                .build();
                }

                response.writeDelimitedTo(outStream);
                outStream.flush();

                if (request.getType() == Request.RequestType.QUIT) {
                    break;
                }
            }
        } catch (IOException e) {
            System.err.println("Error handling client: " + e.getMessage());
        }
    }

    private Response handleAdd(Request request) {
        String description = request.getDescription();
        String category = request.getCategory();

        Task task = taskList.addTask(description, category);

        TaskProto taskProto = TaskProto.newBuilder()
                .setId(task.getId())
                .setDescription(task.getDescription())
                .setCategory(task.getCategory())
                .setAssignee(task.getAssignee())
                .setFinished(task.isFinished())
                .build();

        return Response.newBuilder()
                .setType(Response.ResponseType.SUCCESS)
                .setMessage("Task added successfully")
                .setTask(taskProto)
                .build();
    }

    private Response handleList(Request request) {
        String filter = request.getFilter();
        if (filter.isEmpty()) filter = "all";

        List<Task> tasks;
        switch (filter) {
            case "all":
                tasks = taskList.listAllTasks();
                break;
            case "pending":
                tasks = taskList.listPendingTasks();
                break;
            case "finished":
                tasks = taskList.listFinishedTasks();
                break;
            default:
                return Response.newBuilder()
                        .setType(Response.ResponseType.ERROR)
                        .setMessage("Invalid filter value. Must be 'all', 'pending', or 'finished'")
                        .build();
        }

        taskone.proto.TaskList.Builder taskListBuilder = taskone.proto.TaskList.newBuilder();
        for (Task task : tasks) {
            TaskProto tp = TaskProto.newBuilder()
                    .setId(task.getId())
                    .setDescription(task.getDescription())
                    .setCategory(task.getCategory())
                    .setAssignee(task.getAssignee())
                    .setFinished(task.isFinished())
                    .build();
            taskListBuilder.addTasks(tp);
        }
        taskListBuilder.setCount(tasks.size());

        return Response.newBuilder()
                .setType(Response.ResponseType.SUCCESS)
                .setMessage("Tasks retrieved")
                .setTaskList(taskListBuilder.build())
                .build();
    }

    private Response handleFinish(Request request) {
        int id = request.getId();
        boolean success = taskList.finishTask(id);

        if (success) {
            return Response.newBuilder()
                    .setType(Response.ResponseType.SUCCESS)
                    .setMessage("Task #" + id + " marked as finished")
                    .build();
        } else {
            return Response.newBuilder()
                    .setType(Response.ResponseType.ERROR)
                    .setMessage("Task not found with ID: " + id)
                    .build();
        }
    }

    private Response handleQuit() {
        return Response.newBuilder()
                .setType(Response.ResponseType.SUCCESS)
                .setMessage("Goodbye!")
                .build();
    }
}

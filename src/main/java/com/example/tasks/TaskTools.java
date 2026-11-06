package com.example.tasks;

import java.io.ByteArrayInputStream;
import java.util.Base64;

import org.mcpjava.server.tools.Tool;
import org.mcpjava.server.tools.ToolArg;

import io.openliberty.mcp.tools.ToolCallException;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import jakarta.servlet.http.HttpServletRequest;

@ApplicationScoped
public class TaskTools {

    @Inject
    TaskService taskService;

    @Inject
    HttpServletRequest httpRequest;

    @Tool(description = "Create a task")
    public String createTask(
            @ToolArg(name = "title", description = "Task title") String title,
            @ToolArg(name = "description", description = "Task description") String description) {

        String taskOwnerId = httpRequest.getUserPrincipal().getName();
        String displayName = getDisplayName();
        Task task = taskService.create(title, description, taskOwnerId, displayName);
        return "Created task " + task.getId() + " \"" + task.getTitle() + "\" for " + displayName + ".";
    }

    @Tool(description = "List your tasks (admins see all tasks)")
    public String listTasks() {
        String currentSub = httpRequest.getUserPrincipal().getName();
        String currentDisplayName = getDisplayName();
        boolean isAdmin = httpRequest.isUserInRole("taskadmin");

        String result = "Tasks:";
        for (Task task : taskService.list()) {
            boolean isOwner = (task.getTaskOwnerId() != null && task.getTaskOwnerId().equals(currentSub))
                    || (task.getDisplayName() != null && task.getDisplayName().equals(currentDisplayName))
                    || (task.getTaskOwnerId() != null && task.getTaskOwnerId().equals(currentDisplayName));
            if (isAdmin || isOwner) {
                result += "\n" + task.getId() + ". " + task.getTitle() + " [" + task.getStatus() + "] (owner: " + task.getDisplayName() + ")";
            }
        }
        return result;
    }

    @Tool(description = "Change a task's status")
    public String updateTask(
            @ToolArg(name = "id", description = "Task id") long id,
            @ToolArg(name = "status", description = "New status") Task.Status status) {

        Task task = taskService.get(id);
        if (task == null) {
            throw new ToolCallException("No task found with id " + id + ".");
        }

        String currentSub = httpRequest.getUserPrincipal().getName();
        String currentDisplayName = getDisplayName();
        boolean isAdmin = httpRequest.isUserInRole("taskadmin");
        boolean isOwner = (task.getTaskOwnerId() != null && task.getTaskOwnerId().equals(currentSub))
                || (task.getDisplayName() != null && task.getDisplayName().equals(currentDisplayName))
                || (task.getTaskOwnerId() != null && task.getTaskOwnerId().equals(currentDisplayName));

        if (!isAdmin && !isOwner) {
            throw new ToolCallException("Not authorized. Only the task owner or a taskadmin may update it.");
        }

        task.setStatus(status);
        return "Task " + id + " is now " + status + ".";
    }

    @Tool(description = "Delete a task (admin only)")
    @RolesAllowed("taskadmin")
    public String deleteTask(
            @ToolArg(name = "id", description = "Task id") long id) {

        if (!taskService.delete(id)) {
            throw new ToolCallException("No task found with id " + id + ".");
        }
        return "Task " + id + " has been deleted.";
    }

    private String getDisplayName() {
        String authHeader = httpRequest.getHeader("Authorization");
        if (authHeader != null && authHeader.regionMatches(true, 0, "Bearer ", 0, 7)) {
            String token = authHeader.substring(7).trim();
            String[] parts = token.split("\\.");
            if (parts.length >= 2) {
                try {
                    byte[] decoded = Base64.getUrlDecoder().decode(parts[1]);
                    try (JsonReader reader = Json.createReader(new ByteArrayInputStream(decoded))) {
                        JsonObject payload = reader.readObject();
                        if (payload.containsKey("preferred_username")) {
                            return payload.getString("preferred_username");
                        }
                    }
                } catch (Exception ignored) {
                }
            }
        }
        return httpRequest.getUserPrincipal() != null ? httpRequest.getUserPrincipal().getName() : "unknown";
    }
}

package com.example.tasks;

import java.util.stream.Collectors;

import org.mcpjava.server.tools.Tool;
import org.mcpjava.server.tools.ToolArg;

import io.openliberty.mcp.tools.ToolCallException;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
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

        String owner = httpRequest.getUserPrincipal().getName();
        Task task = taskService.create(title, description, owner);
        return "Created task " + task.getId() + " \"" + task.getTitle() + "\" for " + owner + ".";
    }

    @Tool(description = "List all tasks")
    public String listTasks() {
        return taskService.list().stream()
                .map(task -> task.getId() + ". " + task.getTitle() + " [" + task.getStatus() + "] (owner: " + task.getOwner() + ")")
                .collect(Collectors.joining("\n", "Tasks:\n", ""));
    }

    @Tool(description = "Change a task's status")
    public String updateTask(
            @ToolArg(name = "id", description = "Task id") long id,
            @ToolArg(name = "status", description = "New status") Task.Status status) {

        Task task = taskService.get(id);
        if (task == null) {
            throw new ToolCallException("No task found with id " + id + ".");
        }

        String currentUser = httpRequest.getUserPrincipal().getName();
        boolean isAdmin = httpRequest.isUserInRole("taskadmin");
        if (!isAdmin && !task.getOwner().equals(currentUser)) {
            throw new ToolCallException("Not authorised. Only the task owner or a taskadmin may update it.");
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

    @Tool(description = "Show the current user (admin only)")
    @RolesAllowed("taskadmin")
    public String printUser() {
        String currentUser = httpRequest.getUserPrincipal().getName();
        return "printUser executed for " + currentUser + ".";
    }
}

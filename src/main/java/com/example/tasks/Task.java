package com.example.tasks;

public class Task {

    public enum Status {
        TODO,        // Task is pending / not started
        IN_PROGRESS, // Task is currently being worked on
        DONE         // Task has been completed
    }

    private final long displayId;      // Human-readable sequential task identifier (e.g. 1, 2, 3)
    private final String taskOwnerId;  // Immutable security principal identifier (OIDC 'sub' UUID)
    private final String displayName;  // Human-readable user name for display (e.g. 'preferred_username')
    private String title;              // Short summary of the task
    private String description;        // Detailed explanation of the task
    private Status status;             // Current completion state of the task

    public Task(long displayId, String title, String description, Status status, String taskOwnerId, String displayName) {
        this.displayId = displayId;
        this.title = title;
        this.description = description;
        this.status = status;
        this.taskOwnerId = taskOwnerId;
        this.displayName = displayName;
    }

    public long getDisplayId() {
        return displayId;
    }

    public long getDisplayID() {
        return displayId;
    }

    public long getId() {
        return displayId;
    }

    public String getTaskOwnerId() {
        return taskOwnerId;
    }

    public String getOwnerId() {
        return taskOwnerId;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getOwnerDisplayName() {
        return displayName;
    }

    public String getOwner() {
        return displayName != null ? displayName : taskOwnerId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }
}

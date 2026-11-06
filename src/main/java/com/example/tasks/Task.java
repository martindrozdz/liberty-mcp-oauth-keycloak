package com.example.tasks;

public class Task {

    public enum Status {
        TODO,
        IN_PROGRESS,
        DONE
    }

    private final long id;
    private final String taskOwnerId;
    private final String displayName;
    private String title;
    private String description;
    private Status status;

    public Task(long id, String title, String description, Status status, String taskOwnerId, String displayName) {
        this.id = id;
        this.title = title;
        this.description = description;
        this.status = status;
        this.taskOwnerId = taskOwnerId;
        this.displayName = displayName;
    }

    public long getId() {
        return id;
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

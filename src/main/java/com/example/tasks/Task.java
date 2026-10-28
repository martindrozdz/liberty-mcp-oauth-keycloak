package com.example.tasks;

public class Task {

    public enum Status {
        TODO,
        IN_PROGRESS,
        DONE
    }

    private final long id;
    private final String owner;
    private String title;
    private String description;
    private Status status;

    public Task(long id, String title, String description, Status status, String owner) {
        this.id = id;
        this.title = title;
        this.description = description;
        this.status = status;
        this.owner = owner;
    }

    public long getId() {
        return id;
    }

    public String getOwner() {
        return owner;
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

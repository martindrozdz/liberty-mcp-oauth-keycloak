package com.example.tasks;

import java.util.Collection;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class TaskService {

    private final Map<Long, Task> tasks = new ConcurrentHashMap<>();
    private final AtomicLong nextId = new AtomicLong(1);

    @PostConstruct
    public void init() {
        create("Write release notes", "Summarise the changes for the next release.", "testuser");
        create("Fix login redirect", "Users are sent to the wrong page after OAuth login.", "testuser")
                .setStatus(Task.Status.IN_PROGRESS);
        create("Review pull requests", "Review the open PRs on the main repository.", "testadmin")
                .setStatus(Task.Status.IN_PROGRESS);
        create("Set up CI pipeline", "Build and test every commit automatically.", "testadmin")
                .setStatus(Task.Status.DONE);
    }

    public Task create(String title, String description, String owner) {
        long id = nextId.getAndIncrement();
        Task task = new Task(id, title, description, Task.Status.TODO, owner);
        tasks.put(id, task);
        return task;
    }

    public Task get(long id) {
        return tasks.get(id);
    }

    public Collection<Task> list() {
        return tasks.values().stream()
                .sorted(Comparator.comparingLong(Task::getId))
                .toList();
    }

    public boolean delete(long id) {
        return tasks.remove(id) != null;
    }
}

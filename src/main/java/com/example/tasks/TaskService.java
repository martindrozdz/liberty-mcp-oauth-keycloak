package com.example.tasks;

import java.util.Collection;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class TaskService {

    private final Map<Long, Task> tasks = new ConcurrentHashMap<>();
    private final AtomicLong nextDisplayId = new AtomicLong(1);

    public Task create(String title, String description, String taskOwnerId, String displayName) {
        long displayId = nextDisplayId.getAndIncrement();
        Task task = new Task(displayId, title, description, Task.Status.TODO, taskOwnerId, displayName);
        tasks.put(displayId, task);
        return task;
    }

    public Task create(String title, String description, String owner) {
        return create(title, description, owner, owner);
    }

    public Task get(long displayId) {
        return tasks.get(displayId);
    }

    public Collection<Task> list() {
        return tasks.values().stream()
                .sorted(Comparator.comparingLong(Task::getDisplayId))
                .toList();
    }

    public boolean delete(long displayId) {
        return tasks.remove(displayId) != null;
    }
}

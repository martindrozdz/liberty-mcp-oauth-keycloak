package com.example.rooms;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class RoomService {

    private final Map<String, Room> rooms = new LinkedHashMap<>();

    @PostConstruct
    public void init() {
        rooms.put("Boardroom A", new Room("Boardroom A"));
        rooms.put("Boardroom B", new Room("Boardroom B"));
        rooms.put("Meeting Room 1", new Room("Meeting Room 1"));
        rooms.put("Meeting Room 2", new Room("Meeting Room 2"));
    }

    public Collection<Room> getAllRooms() {
        return Collections.unmodifiableCollection(rooms.values());
    }

    public Room getRoom(String name) {
        return rooms.get(name);
    }

    public Collection<Room> getAvailableRooms(LocalDateTime start, LocalDateTime end) {
        return rooms.values().stream()
                .filter(room -> room.isAvailable(start, end))
                .toList();
    }

    public boolean bookRoom(String roomName, LocalDateTime start, LocalDateTime end, String bookedBy) {
        Room room = rooms.get(roomName);
        if (room == null) {
            return false;
        }
        return room.book(start, end, bookedBy);
    }

    public Optional<Room.BookingSlot> findBooking(String roomName, LocalDateTime start, LocalDateTime end) {
        Room room = rooms.get(roomName);
        if (room == null) {
            return Optional.empty();
        }
        return room.getBookings().stream()
                .filter(slot -> slot.getStart().equals(start) && slot.getEnd().equals(end))
                .findFirst();
    }

    public record UserBooking(String roomName, Room.BookingSlot slot) {}

    public List<UserBooking> getBookingsForUser(String username) {
        return rooms.entrySet().stream()
                .flatMap(entry -> entry.getValue().getBookings().stream()
                        .filter(slot -> slot.getBookedBy().equals(username))
                        .map(slot -> new UserBooking(entry.getKey(), slot)))
                .toList();
    }

    public boolean cancelRoom(String roomName, LocalDateTime start, LocalDateTime end) {
        Room room = rooms.get(roomName);
        if (room == null) {
            return false;
        }
        return room.cancel(start, end);
    }
}

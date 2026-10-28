package com.example.rooms;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class Room {

    private final String name;
    private final List<BookingSlot> bookings = new ArrayList<>();

    public Room(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    public List<BookingSlot> getBookings() {
        return Collections.unmodifiableList(bookings);
    }

    public boolean isAvailable(LocalDateTime start, LocalDateTime end) {
        for (BookingSlot slot : bookings) {
            if (slot.overlaps(start, end)) {
                return false;
            }
        }
        return true;
    }

    public boolean cancel(LocalDateTime start, LocalDateTime end) {
        return bookings.removeIf(slot -> slot.getStart().equals(start) && slot.getEnd().equals(end));
    }

    public boolean book(LocalDateTime start, LocalDateTime end, String bookedBy) {
        if (!isAvailable(start, end)) {
            return false;
        }
        bookings.add(new BookingSlot(start, end, bookedBy));
        return true;
    }

    public static class BookingSlot {
        private final LocalDateTime start;
        private final LocalDateTime end;
        private final String bookedBy;

        BookingSlot(LocalDateTime start, LocalDateTime end, String bookedBy) {
            this.start = start;
            this.end = end;
            this.bookedBy = bookedBy;
        }

        public LocalDateTime getStart() {
            return start;
        }

        public LocalDateTime getEnd() {
            return end;
        }

        public String getBookedBy() {
            return bookedBy;
        }

        boolean overlaps(LocalDateTime otherStart, LocalDateTime otherEnd) {
            return otherStart.isBefore(end) && otherEnd.isAfter(start);
        }
    }
}

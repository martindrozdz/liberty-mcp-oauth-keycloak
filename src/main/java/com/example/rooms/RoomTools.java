package com.example.rooms;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import org.mcpjava.server.tools.Tool;
import org.mcpjava.server.tools.ToolArg;

import io.openliberty.mcp.annotations.Schema;
import io.openliberty.mcp.tools.ToolCallException;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.servlet.http.HttpServletRequest;

@ApplicationScoped
public class RoomTools {

    private static final String DATE_TIME = """
            {
                "type": "string",
                "format": "date-time"
            }
            """;

    private static final String DATE = """
            {
                "type": "string",
                "format": "date"
            }
            """;

    @Inject
    RoomService roomService;

    @Inject
    HttpServletRequest httpRequest;

    @Tool(description = "Book a room for a given time period",
          annotations = @Tool.Annotations(destructiveHint = false, openWorldHint = false))
    public String bookRoom(
            @ToolArg(name = "roomName", description = "Name of the room to book") String roomName,
            @ToolArg(name = "start", description = "Start date and time of the booking (ISO-8601, e.g. 2025-06-01T09:00)") @Schema(DATE_TIME) LocalDateTime start,
            @ToolArg(name = "end", description = "End date and time of the booking (ISO-8601, e.g. 2025-06-01T10:00)") @Schema(DATE_TIME) LocalDateTime end) {

        String bookedBy = httpRequest.getUserPrincipal().getName();

        boolean success = roomService.bookRoom(roomName, start, end, bookedBy);

        if (success) {
            return "Room \"" + roomName + "\" successfully booked for " + bookedBy
                    + " from " + start + " to " + end + ".";
        } else {
            throw new ToolCallException("Failed to book room \"" + roomName + "\". "
                    + "The room may not exist or the requested time slot is already taken.");
        }
    }

    @Tool(description = "Find all rooms available for a given time period",
          annotations = @Tool.Annotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false))
    public String getAvailableRooms(
            @ToolArg(name = "start", description = "Start date and time of the desired period (ISO-8601, e.g. 2025-06-01T09:00)") @Schema(DATE_TIME) LocalDateTime start,
            @ToolArg(name = "end", description = "End date and time of the desired period (ISO-8601, e.g. 2025-06-01T10:00)") @Schema(DATE_TIME) LocalDateTime end) {

        Collection<Room> available = roomService.getAvailableRooms(start, end);

        if (available.isEmpty()) {
            return "No rooms are available between " + start + " and " + end + ".";
        }

        String roomList = available.stream()
                .map(Room::getName)
                .collect(Collectors.joining(", "));

        return "Rooms available between " + start + " and " + end + ": " + roomList + ".";
    }

    @Tool(description = "Show all bookings for a room on a particular day",
          annotations = @Tool.Annotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false))
    public String getRoomBookingsOnDay(
            @ToolArg(name = "roomName", description = "Name of the room") String roomName,
            @ToolArg(name = "day", description = "The day to check (ISO-8601 date, e.g. 2025-06-01)") @Schema(DATE) LocalDate day) {

        Room room = roomService.getRoom(roomName);
        if (room == null) {
            return "Room \"" + roomName + "\" does not exist.";
        }

        boolean isAdmin = httpRequest.isUserInRole("roomadmin");

        List<Room.BookingSlot> bookingsOnDay = room.getBookings().stream()
                .filter(slot -> slot.getStart().toLocalDate().equals(day)
                             || slot.getEnd().toLocalDate().equals(day))
                .sorted(Comparator.comparing(Room.BookingSlot::getStart))
                .collect(Collectors.toList());

        if (bookingsOnDay.isEmpty()) {
            return "Room \"" + roomName + "\" has no bookings on " + day + ".";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Bookings for room \"").append(roomName).append("\" on ").append(day).append(":\n");
        for (Room.BookingSlot slot : bookingsOnDay) {
            sb.append("  ").append(slot.getStart().toLocalTime())
              .append(" - ").append(slot.getEnd().toLocalTime());
            if (isAdmin) {
                sb.append(" (booked by: ").append(slot.getBookedBy()).append(")");
            } else if (slot.getBookedBy().equals(httpRequest.getUserPrincipal().getName())) {
                sb.append(" (booked by you)");
            }
            sb.append("\n");
        }
        return sb.toString().trim();
    }

    @Tool(description = "List all room bookings for the currently authenticated user",
          annotations = @Tool.Annotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false))
    public String getMyBookings() {
        String currentUser = httpRequest.getUserPrincipal().getName();

        List<RoomService.UserBooking> bookings = roomService.getBookingsForUser(currentUser);

        if (bookings.isEmpty()) {
            return "You have no room bookings.";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Your bookings:\n");
        for (RoomService.UserBooking booking : bookings) {
            sb.append("  ").append(booking.roomName())
              .append(": ").append(booking.slot().getStart())
              .append(" - ").append(booking.slot().getEnd())
              .append("\n");
        }
        return sb.toString().trim();
    }

    @Tool(description = "Print a message to the server log. Restricted to users with the roomadmin role.",
          annotations = @Tool.Annotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false))
    @RolesAllowed("roomadmin")
    public String printUser() {
        String currentUser = httpRequest.getUserPrincipal().getName();
        return "printUser executed for " + currentUser + ".";
    }

    @Tool(description = "Cancel a room booking. Only the user who made the booking or a roomadmin may cancel it.",
          annotations = @Tool.Annotations(openWorldHint = false))
    public String cancelBooking(
            @ToolArg(name = "roomName", description = "Name of the room") String roomName,
            @ToolArg(name = "start", description = "Start date and time of the booking to cancel (ISO-8601, e.g. 2025-06-01T09:00)") @Schema(DATE_TIME) LocalDateTime start,
            @ToolArg(name = "end", description = "End date and time of the booking to cancel (ISO-8601, e.g. 2025-06-01T10:00)") @Schema(DATE_TIME) LocalDateTime end) {

        Optional<Room.BookingSlot> slot = roomService.findBooking(roomName, start, end);
        if (slot.isEmpty()) {
            throw new ToolCallException("No booking found for room \"" + roomName + "\" from " + start + " to " + end + ".");
        }

        String currentUser = httpRequest.getUserPrincipal().getName();
        boolean isAdmin = httpRequest.isUserInRole("roomadmin");

        if (!isAdmin && !slot.get().getBookedBy().equals(currentUser)) {
            throw new ToolCallException("Not authorised. Only the user who made the booking or a roomadmin may cancel it.");
        }

        roomService.cancelRoom(roomName, start, end);
        return "Booking for room \"" + roomName + "\" from " + start + " to " + end + " has been cancelled.";
    }
}

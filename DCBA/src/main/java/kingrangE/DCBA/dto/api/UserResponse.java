package kingrangE.DCBA.dto.api;

import java.time.LocalDateTime;

import kingrangE.DCBA.domain.User;

public record UserResponse(
        Long id,
        String name,
        String slackId,
        LocalDateTime createdAt,
        boolean admin) {

    public static UserResponse from(User user, boolean admin) {
        return new UserResponse(user.getId(), user.getName(), user.getSlackId(), user.getCreatedAt(), admin);
    }
}

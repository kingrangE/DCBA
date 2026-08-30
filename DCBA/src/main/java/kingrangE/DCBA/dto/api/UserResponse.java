package kingrangE.DCBA.dto.api;

import java.time.LocalDateTime;

import kingrangE.DCBA.domain.User;

public record UserResponse(
        Long id,
        String name,
        String slackId,
        LocalDateTime createdAt) {

    public static UserResponse from(User user) {
        return new UserResponse(user.getId(), user.getName(), user.getSlackId(), user.getCreatedAt());
    }
}

package kingrangE.DCBA.dto.api;
import java.util.List;
public record PageResponse<T>(List<T> content, boolean hasPrevious, boolean hasNext,
                              Long previousCursor, Long nextCursor) {}

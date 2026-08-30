package kingrangE.DCBA.dto.api;

import kingrangE.DCBA.domain.Level;
import kingrangE.DCBA.domain.Subject;

public record GenerationRequest(Subject subject, Level level) {
}

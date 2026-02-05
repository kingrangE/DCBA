package kingrangE.DCBA.domain;

import lombok.*;

@Getter
@Builder
@ToString
@AllArgsConstructor
public class Exercise {
    private Integer num;
    private String question;
    private String answer;
    private Subject subject;
    private Level level;
}

package kingrangE.DCBA.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import kingrangE.DCBA.domain.Exercise;
import kingrangE.DCBA.domain.Level;
import kingrangE.DCBA.domain.Subject;
import org.hibernate.Session;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.SliceImpl;
import org.springframework.stereotype.Component;

import java.util.HashMap;

@Component
class OrderedPostgresSearch {
    private static final int PROBE_ROWS = 128;

    @PersistenceContext
    private EntityManager entityManager;

    Slice<Exercise> fullPage(long userId, boolean saved, Subject subject, Level level,
                             String pattern, Long before, Long after, Pageable page) {
        if (pattern == null) {
            return null;
        }
        String orderedId = saved ? "s.exercise_id" : "e.id";
        String relation = saved
                ? "selected_exercise s JOIN exercise e ON e.id = s.exercise_id"
                : "exercise e";
        String direction = after == null ? " DESC" : " ASC";
        StringBuilder sql = new StringBuilder("SELECT recent.* FROM (SELECT e.*, ")
                .append(orderedId).append(" AS ordered_id FROM ").append(relation).append(" ")
                .append(saved ? "WHERE s.user_id = :userId" : """
                        WHERE NOT EXISTS (
                            SELECT 1 FROM banned_exercise b WHERE b.user_id = :userId AND b.exercise_id = e.id
                        )
                        """);
        var parameters = new HashMap<String, Object>();
        parameters.put("userId", userId);
        if (subject != null) {
            sql.append(" AND e.subject = :subject");
            parameters.put("subject", subject.name());
        }
        if (level != null) {
            sql.append(" AND e.level = :level");
            parameters.put("level", level.name());
        }
        if (before != null) {
            sql.append(" AND ").append(orderedId).append(" < :cursor");
            parameters.put("cursor", before);
        } else if (after != null) {
            sql.append(" AND ").append(orderedId).append(" > :cursor");
            parameters.put("cursor", after);
        }
        sql.append(" ORDER BY ").append(orderedId).append(direction)
                .append(" LIMIT :probeRows) recent WHERE lower(recent.question) LIKE lower(:pattern) ESCAPE '!'")
                .append(" ORDER BY recent.ordered_id").append(direction).append(" LIMIT :pageRows");
        var query = entityManager.unwrap(Session.class).createNativeQuery(sql.toString(), Exercise.class);
        parameters.forEach(query::setParameter);
        query.setParameter("pattern", pattern);
        query.setParameter("probeRows", PROBE_ROWS);
        query.setParameter("pageRows", page.getPageSize() + 1);
        var rows = query.getResultList();
        // A full slice proves all returned matches precede anything outside the probe.
        // Otherwise the existing unrestricted query determines content and hasNext.
        return rows.size() > page.getPageSize()
                ? new SliceImpl<>(rows.subList(0, page.getPageSize()), page, true)
                : null;
    }
}

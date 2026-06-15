package com.example.epub.core.repository.catalog;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

public class RecommendationRepository {
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;
    public RecommendationRepository(JdbcTemplate jdbc) {this.jdbc=jdbc;named=new NamedParameterJdbcTemplate(jdbc);}
    public record History(UUID id,String shelf,boolean favorite,Instant savedAt,Instant likedAt,Instant viewedAt) { }
    private static Instant instant(java.sql.ResultSet r,String column) throws java.sql.SQLException {
        var value=r.getTimestamp(column);return value==null?null:value.toInstant();
    }
    public List<History> history(UUID user) {
        if(user==null)return List.of();
        return jdbc.query("""
            SELECT s.* FROM personal_book_state s JOIN books b ON b.id=s.book_id
            WHERE s.user_id=? AND (s.favorite OR s.shelf IN ('want','reading') OR s.viewed_at IS NOT NULL)
            AND %s
            ORDER BY greatest(CASE WHEN s.favorite THEN s.liked_at END,
                CASE WHEN s.shelf IN ('want','reading') THEN s.saved_at END,s.viewed_at) DESC,s.book_id
            LIMIT 100
            """.formatted(CatalogRepository.PUBLIC),(r,n)->new History(r.getObject("book_id",UUID.class),r.getString("shelf"),r.getBoolean("favorite"),instant(r,"saved_at"),instant(r,"liked_at"),instant(r,"viewed_at")),user);
    }
    /** Three bounded sources: recent 80, popular 60, affinity 60. All exclude owned shelf/likes. */
    public Map<UUID,Double> candidates(UUID user,Instant now,Set<UUID> categories,Set<UUID> authors,Set<String> tags) {
        Map<String,Object> params=new HashMap<>();params.put("user",user);params.put("now",Timestamp.from(now));params.put("since",Timestamp.from(now.minus(java.time.Duration.ofDays(30))));
        // Typed sentinel collections keep empty IN clauses valid without matching real metadata.
        params.put("categories",categories.isEmpty()?List.of(new UUID(0,0)):categories);
        params.put("authors",authors.isEmpty()?List.of(new UUID(0,0)):authors);
        // The candidate prefilter uses the catalog's accent fold, independent of DB locale.
        // Final scoring still compares exact normalized tags, retaining accents.
        params.put("tags",tags.isEmpty()?List.of(""):tags.stream().map(RecommendationRepository::candidateTag).toList());
        String sql="""
            WITH views AS (
                SELECT book_id,count(*) AS views FROM book_interactions
                WHERE kind='VIEW' AND occurred_at>=:since AND occurred_at<=:now GROUP BY book_id
            ), likes AS (
                SELECT s.book_id,count(*) AS likes FROM personal_book_state s JOIN users u ON u.id=s.user_id
                WHERE s.favorite AND s.liked_at>=:since AND s.liked_at<=:now AND u.status='ACTIVE' GROUP BY s.book_id
            ), eligible AS (
                SELECT b.id,b.created_at,coalesce(v.views,0)+5.0*coalesce(l.likes,0) AS popularity,
                    CASE WHEN EXISTS(SELECT 1 FROM book_categories bc WHERE bc.book_id=b.id AND bc.category_id IN (:categories)) THEN 1 ELSE 0 END+
                    CASE WHEN EXISTS(SELECT 1 FROM book_authors ba WHERE ba.book_id=b.id AND ba.author_id IN (:authors)) THEN 1 ELSE 0 END+
                    CASE WHEN EXISTS(SELECT 1 FROM book_tags bt WHERE bt.book_id=b.id AND regexp_replace(trim(catalog_fold_v1(normalize(bt.tag,NFKC))),'\\s+',' ','g') IN (:tags)) THEN 1 ELSE 0 END AS affinity
                FROM books b LEFT JOIN views v ON v.book_id=b.id LEFT JOIN likes l ON l.book_id=b.id
                WHERE %s AND NOT EXISTS(SELECT 1 FROM personal_book_state own WHERE own.user_id=CAST(:user AS uuid)
                    AND own.book_id=b.id AND (own.shelf IS NOT NULL OR own.favorite))
            ), recent AS (SELECT * FROM eligible ORDER BY created_at DESC,id LIMIT 80),
            popular AS (SELECT * FROM eligible WHERE popularity>0 ORDER BY popularity DESC,id LIMIT 60),
            related AS (SELECT * FROM eligible WHERE affinity>0 ORDER BY affinity DESC,created_at DESC,id LIMIT 60)
            SELECT id,popularity FROM recent UNION SELECT id,popularity FROM popular UNION SELECT id,popularity FROM related
            """.formatted(CatalogRepository.PUBLIC);
        Map<UUID,Double> result=new LinkedHashMap<>();
        named.query(sql,params,(org.springframework.jdbc.core.RowCallbackHandler)r->result.put(r.getObject("id",UUID.class),r.getDouble("popularity")));
        return result;
    }
    private static String candidateTag(String tag) {
        return java.text.Normalizer.normalize(java.text.Normalizer.normalize(tag,java.text.Normalizer.Form.NFKC),java.text.Normalizer.Form.NFD)
            .replaceAll("[\\u0300-\\u036f]", "").replace('đ','d').replace('Đ','d').toLowerCase(Locale.ROOT).trim().replaceAll("\\s+", " ");
    }
}

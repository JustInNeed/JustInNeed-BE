package com.justinneed.community;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import org.hibernate.resource.jdbc.spi.StatementInspector;

/**
 * Hibernate 가 보내는 SQL 을 그대로 모은다. 커뮤니티 조회가 쓰기 · 잠금을 하지 않는지 확인할 때 쓴다.
 * spring.jpa.properties.hibernate.session_factory.statement_inspector 로 켠다.
 */
public class SqlCapture implements StatementInspector {

    private static final List<String> STATEMENTS = new CopyOnWriteArrayList<>();

    @Override
    public String inspect(String sql) {
        STATEMENTS.add(sql.toLowerCase(Locale.ROOT));
        return sql;
    }

    public static void clear() {
        STATEMENTS.clear();
    }

    public static List<String> all() {
        return List.copyOf(STATEMENTS);
    }

    /** INSERT · UPDATE · DELETE 와 행 잠금(FOR UPDATE 류). */
    public static List<String> writesAndLocks() {
        return STATEMENTS.stream()
                .filter(sql -> sql.startsWith("insert") || sql.startsWith("update") || sql.startsWith("delete")
                        || sql.startsWith("merge") || sql.contains(" for update") || sql.contains(" for no key update")
                        || sql.contains(" for share"))
                .toList();
    }
}

package com.campusdash.it;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * 陷阱 5：手动 new 出来的对象没有代理，@Transactional 是一张废纸。
 * 这类代码常出现在"图省事在方法里 new 一个 helper"的场景。
 */
public class ManualNewService {

    private final JdbcTemplate jdbc;

    public ManualNewService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(rollbackFor = Exception.class)
    public void insertThenFail(String tag) {
        jdbc.update("INSERT INTO pitfall_probe (tag) VALUES (?)", tag);
        throw new IllegalStateException("故意失败");
    }
}

package com.campusdash.application.usecase.query;

import java.util.List;

/** 游标分页结果。nextCursor 为 null 表示已经到最后一页。 */
public record CursorPage<T>(List<T> items, String nextCursor) {}

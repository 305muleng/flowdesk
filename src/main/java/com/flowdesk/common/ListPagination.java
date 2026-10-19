package com.flowdesk.common;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.flowdesk.exception.BusinessException;

public final class ListPagination {

    private ListPagination() {}

    public static <T> Page<T> page(long page, long size) {
        if (page < 1) throw new BusinessException(400, "page 不能小于 1");
        if (size < 1 || size > 100) throw new BusinessException(400, "size 必须在 1 到 100 之间");
        Page<T> result = new Page<>(page, size);
        // 自定义任务查询的计数必须保留成员、账号和项目访问限制。
        result.setOptimizeJoinOfCountSql(false);
        return result;
    }
}

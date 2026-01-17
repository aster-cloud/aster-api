package io.aster.common.dto;

import java.util.List;

/**
 * 通用分页响应包装类（Phase 3.2）
 *
 * 用于封装分页查询结果，包含数据列表和分页元数据。
 *
 * @param <T> 数据项类型
 */
public class PagedResult<T> {

    /** 数据项列表 */
    public List<T> items;

    /** 总记录数 */
    public long total;

    /** 当前页码（从 0 开始） */
    public int page;

    /** 每页大小 */
    public int size;

    /** 是否有下一页 */
    public boolean hasMore;

    /**
     * 构造分页结果
     *
     * @param items 数据项列表
     * @param total 总记录数
     * @param page 当前页码（从 0 开始）
     * @param size 每页大小
     */
    public PagedResult(List<T> items, long total, int page, int size) {
        this.items = items;
        this.total = total;
        this.page = page;
        this.size = size;
        this.hasMore = (page + 1) * size < total;
    }
}

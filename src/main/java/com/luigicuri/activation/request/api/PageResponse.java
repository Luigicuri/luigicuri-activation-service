package com.luigicuri.activation.request.api;

import java.util.List;
import java.util.function.Function;

import org.springframework.data.domain.Page;

/** Stable JSON contract for pages, instead of serializing Spring's Page implementation. */
public record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages) {

    static <E, T> PageResponse<T> from(Page<E> page, Function<E, T> mapper) {
        return new PageResponse<>(page.getContent().stream().map(mapper).toList(),
                page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
    }
}

package com.example.epub.api.dto.common;

import java.util.List;

public record PageResult<T>(List<T> items,int page,int size,long total) {
    public PageResult {
        items=List.copyOf(items);
        if(page<0 || size<1 || size>100 || total<0 || items.size()>size || total<items.size()) throw new IllegalArgumentException("Invalid page result");
    }
}

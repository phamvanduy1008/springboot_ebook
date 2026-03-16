package com.example.epub.api.contract;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public record PageQuery(@Min(0) Integer page,@Min(1) @Max(100) Integer size,String sort) {
    public PageQuery { page=page==null?0:page;size=size==null?20:size;sort=sort==null?"id,asc":sort; }
    public long offset() { return (long)page*size; }
    public List<SortOrder> orders(Set<String> allowedFields) {
        String[] parts=sort.split(",",-1);
        if (parts.length!=2 || !allowedFields.contains(parts[0])) throw new IllegalArgumentException("Unsupported sort");
        Direction direction;
        try {direction=Direction.valueOf(parts[1].toUpperCase(Locale.ROOT));}
        catch (RuntimeException invalid) {throw new IllegalArgumentException("Unsupported sort");}
        SortOrder primary=new SortOrder(parts[0],direction);
        return parts[0].equals("id") ? List.of(primary) : List.of(primary,new SortOrder("id",Direction.ASC));
    }
    public enum Direction {ASC,DESC}
    public record SortOrder(String field,Direction direction) { }
}

package com.fast.knowledge.course;

import com.fast.knowledge.common.ApiException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.*;

record CourseDates(String from,String to,Timestamp start,Timestamp end) {
    static CourseDates parse(String from,String to) {
        try {
            LocalDate first=date(from),last=date(to);
            if(first!=null && last!=null && first.isAfter(last)) throw ApiException.invalid();
            // Stored course timestamps use Asia/Shanghai wall time; the end is exclusive.
            return new CourseDates(from,to,first==null?null:Timestamp.valueOf(first.atStartOfDay()),last==null?null:Timestamp.valueOf(last.plusDays(1).atStartOfDay()));
        } catch(Exception e) {throw ApiException.invalid();}
    }
    private static LocalDate date(String value) {
        if(value==null) return null;
        if(!value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}") || value.startsWith("0000")) throw ApiException.invalid();
        return LocalDate.parse(value);
    }
    String sql(String column) {return (start==null?"":" AND "+column+">=?")+(end==null?"":" AND "+column+"<?");}
    List<Object> arguments() {var args=new ArrayList<Object>();if(start!=null) args.add(start);if(end!=null) args.add(end);return args;}
}

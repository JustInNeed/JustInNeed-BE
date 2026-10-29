package com.justinneed.surfing;
import java.util.*;
public interface SurfModel {
    Result analyze(List<SurfEvent> events);
    record Result(String title, String markdown, List<String> tags, Map<String,SurfEvent.Behavior> behaviors) {}
}

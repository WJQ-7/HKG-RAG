package com.bistu.hkgrag.llm.agent;

import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class EvidenceStore {
    public final Map<String,String> store=new ConcurrentHashMap<>();

    public String save(String content){
        String id= UUID.randomUUID().toString();
        store.put(id,content);
        return id;
    }
    public String get(String id){
        return store.get(id);
    }
}

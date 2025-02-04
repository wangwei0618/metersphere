package io.metersphere.api.jmeter;

import org.apache.jmeter.engine.JMeterEngine;

import javax.websocket.Session;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class MessageCache {
    public static Map<String, ReportCounter> cache = new HashMap<>();

    public static ConcurrentHashMap<String, Session> reportCache = new ConcurrentHashMap<>();

    public static ConcurrentHashMap<String, JMeterEngine> runningEngine = new ConcurrentHashMap<>();

}

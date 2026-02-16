package com.jsoonworld.notification.infrastructure.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class EventDeserializer {

    private static final Logger log = LoggerFactory.getLogger(EventDeserializer.class);

    private final ObjectMapper objectMapper;

    public EventDeserializer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public JsonNode deserialize(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            log.error("Failed to deserialize event JSON: {}", e.getMessage());
            throw new DeserializationException("Failed to deserialize event: " + e.getMessage(), e);
        }
    }

    public String extractEventId(JsonNode node) {
        JsonNode eventIdNode = node.get("eventId");
        if (eventIdNode == null || eventIdNode.isNull()) {
            throw new DeserializationException("Missing eventId in event payload");
        }
        return eventIdNode.asText();
    }

    public String extractEventType(JsonNode node) {
        JsonNode eventTypeNode = node.get("eventType");
        if (eventTypeNode == null || eventTypeNode.isNull()) {
            throw new DeserializationException("Missing eventType in event payload");
        }
        return eventTypeNode.asText();
    }

    public JsonNode extractPayload(JsonNode node) {
        JsonNode payloadNode = node.get("payload");
        if (payloadNode == null || payloadNode.isNull()) {
            return objectMapper.createObjectNode();
        }
        return payloadNode;
    }

    public static class DeserializationException extends RuntimeException {
        public DeserializationException(String message) {
            super(message);
        }

        public DeserializationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
